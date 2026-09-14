package com.zerosum.inventory.proposal;

import static org.assertj.core.api.Assertions.assertThat;

import com.zerosum.inventory.domain.BasisRef;
import com.zerosum.inventory.domain.PostingException;
import com.zerosum.inventory.domain.PostingOutcome;
import com.zerosum.inventory.domain.Posted;
import com.zerosum.inventory.posting.PostingRequest;
import com.zerosum.inventory.posting.PostingService;
import com.zerosum.inventory.posting.Preconditions;
import com.zerosum.inventory.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;

/**
 * 제안 승인 코어의 동시성 (docs/08-testing-roadmap.md 4단계 완료 기준 ①: "에이전트 재시도·동시 승인에도
 * 실행 1회"). 순차 호출은 이미 커밋된 충돌 행을 다시 읽는 경로일 뿐이라 이 파일이 증명하려는 "커밋되지 않은
 * 충돌 행에서 대기하는" 경로를 만들지 못한다 (CountStartRaceTest 머리말과 같은 이유) — 전부 실제 스레드 +
 * 커넥션 + CountDownLatch 배리어로 돌린다.
 */
class ProposalConcurrentApprovalTest extends AbstractIntegrationTest {

    @Autowired
    private ProposalCreationService proposalCreationService;

    @Autowired
    private ProposalGateway proposalGateway;

    @Autowired
    private ProposalApprovalService approvalService;

    @Autowired
    private PostingService postingService;

    private static final String MOVE_PAYLOAD = """
            {"txnType":"MOVE","entries":[
               {"wh":"ICN01","loc":"A-01-01-2","sku":"SKU-200002","lot":"L20260910-B","qty":-20},
               {"wh":"ICN01","loc":"A-01-02-1","sku":"SKU-200002","lot":"L20260910-B","qty":20}]}
            """;

    @Test
    void fiveConnectionsApproveSameProposal_onlyOneExecuted() throws Exception {
        receiveColdBrew("A-01-01-2", 100);
        long id = createMoveProposal(MOVE_PAYLOAD);

        List<ApprovalOutcome> outcomes = Collections.synchronizedList(new ArrayList<>());
        runConcurrently(5, () -> outcomes.add(proposalGateway.approve(id, "user:choi.dw")));

        long executedCount = outcomes.stream().filter(o -> o instanceof Executed).count();
        long alreadyDecidedCount = outcomes.stream().filter(o -> o instanceof AlreadyDecided).count();
        assertThat(executedCount).as("실행은 정확히 1회").isEqualTo(1);
        assertThat(alreadyDecidedCount).as("나머지 4번은 AlreadyDecided").isEqualTo(4);

        assertThat(proposalStatusCount(id, "EXECUTED")).as("action_proposal.status='EXECUTED' 1건").isEqualTo(1);
        assertThat(idemKeyTxnCount("proposal:" + id)).as("거래는 정확히 1건").isEqualTo(1);
        assertReconciliationClean();
    }

    /**
     * 잠금 순서에 순환이 없다는 것을 보인다: 승인이 잠그는 순서(제안 행 → 로케이션 → 잔액, 오름차순)와
     * 순수 출고가 잠그는 순서(로케이션 → 잔액, 오름차순)가 겹치는 자원(잔액 행)에서는 항상 같은 순서를
     * 쓴다 — 게이트웨이의 재시도가 데드락을 가려버리면 "재시도로 넘어갔다"와 "애초에 데드락이 없었다"를
     * 구분할 수 없으므로, 여기서는 재시도가 없는 원본 서비스(ProposalApprovalService·PostingService)를
     * 직접 불러 데드락이면 즉시 예외로 드러나게 한다.
     */
    @Test
    void concurrentApprovalAndShipmentOnSameBalance_noDeadlock() throws Exception {
        receiveColdBrew("A-01-01-2", 1000);

        int proposalCount = 10;
        List<Long> proposalIds = new ArrayList<>();
        for (int i = 0; i < proposalCount; i++) {
            proposalIds.add(createMoveProposal(movePayload(5 + i)));
        }

        int shipmentCount = 10;
        int workers = proposalCount + shipmentCount;
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            CountDownLatch ready = new CountDownLatch(workers);
            CountDownLatch go = new CountDownLatch(1);
            List<Callable<Void>> tasks = new ArrayList<>();
            for (long proposalId : proposalIds) {
                tasks.add(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        approvalService.approve(proposalId, "user:choi.dw");
                    } catch (Throwable t) {
                        failures.add(t);
                    }
                    return null;
                });
            }
            for (int i = 0; i < shipmentCount; i++) {
                PostingRequest shipment = shipmentRequest(i, 3);
                tasks.add(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        postingService.post(shipment, Preconditions.none());
                    } catch (Throwable t) {
                        failures.add(t);
                    }
                    return null;
                });
            }

            List<Future<Void>> futures = tasks.stream().map(pool::submit).toList();
            ready.await();
            go.countDown();
            for (Future<Void> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdown();
        }

        for (Throwable t : failures) {
            assertThat(t).as("데드락(ConcurrencyFailureException)은 하나도 없어야 한다: %s", t)
                    .isNotInstanceOf(ConcurrencyFailureException.class);
            assertThat(t).as("허용되는 실패는 재고 부족뿐이다: %s", t)
                    .isInstanceOfSatisfying(PostingException.class,
                            e -> assertThat(e.code()).isEqualTo("INSUFFICIENT_STOCK"));
        }
        assertReconciliationClean();
    }

    @Test
    void executedTxnIdUniqueIsTheSecondGuarantee() {
        receiveColdBrew("A-01-01-2", 100);
        long id = createMoveProposal(MOVE_PAYLOAD);
        String idemKey = "proposal:" + id;

        PostingRequest request = new PostingRequest(idemKey, "MOVE", "USER", "user:choi.dw",
                List.of(
                        line("ICN01", "A-01-01-2", "SKU-200002", "L20260910-B", -20),
                        line("ICN01", "A-01-02-1", "SKU-200002", "L20260910-B", 20)),
                "PROPOSAL", idemKey, null, null, Instant.now(), List.of(), id);

        // 제안 행 잠금(ProposalRepository.lockForUpdate)을 전혀 거치지 않고 같은 멱등 키로 두 번 포스팅한다 —
        // 그래도 idem_key UNIQUE(V1)가 실행 1회를 보장한다는 것을 보여준다 (제안 잠금과는 독립된 두 번째 방어선).
        PostingOutcome first = postingGateway.post(request, Preconditions.none());
        PostingOutcome second = postingGateway.post(request, Preconditions.none());

        assertThat(first).isInstanceOf(Posted.class);
        long firstTxnId = ((Posted) first).txnId();
        assertThat(second).isInstanceOf(Posted.class);
        assertThat(((Posted) second).txnId()).as("두 번째 시도는 기존 거래 id를 그대로 돌려받는다").isEqualTo(firstTxnId);
        assertThat(idemKeyTxnCount(idemKey)).as("거래는 정확히 1건").isEqualTo(1);
        assertReconciliationClean();
    }

    // ── 픽스처 헬퍼 ──────────────────────────────────────────────────────────────────

    private void receiveColdBrew(String locationCode, int qty) {
        postAndExpectSuccess(request("receipt:PROP-CONC-" + locationCode + ":" + qty, "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-200002", "L20260910-B", -qty),
                line("ICN01", locationCode, "SKU-200002", "L20260910-B", qty)));
    }

    private long createMoveProposal(String payloadJson) {
        CreateProposalRequest request = new CreateProposalRequest("MOVE", payloadJson, "테스트 사유", "agent:test", null,
                List.of(BasisRef.balance("ICN01", "A-01-01-2", "SKU-200002", "L20260910-B")));
        CreateProposalOutcome outcome = proposalCreationService.create(request);
        return ((ProposalCreated) outcome).proposalId();
    }

    private static String movePayload(int qty) {
        return """
                {"txnType":"MOVE","entries":[
                   {"wh":"ICN01","loc":"A-01-01-2","sku":"SKU-200002","lot":"L20260910-B","qty":-%d},
                   {"wh":"ICN01","loc":"A-01-02-1","sku":"SKU-200002","lot":"L20260910-B","qty":%d}]}
                """.formatted(qty, qty);
    }

    private PostingRequest shipmentRequest(int index, int qty) {
        String idemKey = "ship:PROP-CONC-" + index;
        return new PostingRequest(idemKey, "SHIPMENT", "USER", "user:test",
                List.of(
                        line("ICN01", "A-01-01-2", "SKU-200002", "L20260910-B", -qty),
                        line("ICN01", "V-CUSTOMER", "SKU-200002", "L20260910-B", qty)),
                "ORDER", idemKey, null, null, Instant.now(), List.of());
    }

    private int proposalStatusCount(long id, String status) {
        return jdbcClient.sql("SELECT count(*) FROM action_proposal WHERE id = :id AND status = :status")
                .param("id", id)
                .param("status", status)
                .query(Integer.class)
                .single();
    }

    private int idemKeyTxnCount(String idemKey) {
        return jdbcClient.sql("SELECT count(*) FROM inventory_txn WHERE idem_key = :idemKey")
                .param("idemKey", idemKey)
                .query(Integer.class)
                .single();
    }

    /** workers개의 작업을 같은 순간에 동시 실행한다 (CountStartRaceTest·ProposalCreationRetryTest와 같은 패턴). */
    private void runConcurrently(int workers, ConcurrentTask task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            CountDownLatch ready = new CountDownLatch(workers);
            CountDownLatch go = new CountDownLatch(1);
            List<Callable<Void>> tasks = IntStream.range(0, workers).<Callable<Void>>mapToObj(i -> () -> {
                ready.countDown();
                go.await();
                task.run();
                return null;
            }).toList();
            List<Future<Void>> futures = tasks.stream().map(pool::submit).toList();
            ready.await();
            go.countDown();
            for (Future<Void> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdown();
        }
    }

    private interface ConcurrentTask {
        void run() throws Exception;
    }
}
