package com.zerosum.inventory.proposal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zerosum.inventory.domain.BasisRef;
import com.zerosum.inventory.domain.ProposalException;
import com.zerosum.inventory.support.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 제안 생성의 재시도 흡수 — uq_proposal_pending(V1)의 md5(command_payload::text)는 키 순서·공백만
 * 정규화하고 배열 순서·숫자 표기는 정규화하지 않는다(spec-create-stmt.md 실측). AiProposalRepository가
 * 정규화한 payload를 저장해야 이 구멍이 닫힌다는 것을 증명한다.
 */
class ProposalCreationRetryTest extends AbstractIntegrationTest {

    @Autowired
    private ProposalCreationService proposalCreationService;

    private static final String MOVE_PAYLOAD = """
            {"txnType":"MOVE","entries":[
               {"wh":"ICN01","loc":"A-01-01-2","sku":"SKU-200002","lot":"L20260910-B","qty":-20},
               {"wh":"ICN01","loc":"A-01-02-1","sku":"SKU-200002","lot":"L20260910-B","qty":20}]}
            """;

    // 키 순서·공백만 다르다 (같은 뜻)
    private static final String MOVE_PAYLOAD_KEY_ORDER_AND_WHITESPACE = """
            {  "entries" :[
                 { "lot":"L20260910-B",   "qty": -20, "wh":"ICN01", "loc":"A-01-01-2", "sku":"SKU-200002" },
                 {"wh":"ICN01","loc":"A-01-02-1","sku":"SKU-200002","lot":"L20260910-B","qty":20}
               ],
               "txnType" : "MOVE"  }
            """;

    // entries 배열 순서만 바뀌었다 (같은 이동)
    private static final String MOVE_PAYLOAD_ENTRIES_SWAPPED = """
            {"txnType":"MOVE","entries":[
               {"wh":"ICN01","loc":"A-01-02-1","sku":"SKU-200002","lot":"L20260910-B","qty":20},
               {"wh":"ICN01","loc":"A-01-01-2","sku":"SKU-200002","lot":"L20260910-B","qty":-20}]}
            """;

    // qty가 정수가 아니라 소수 표기다 (같은 수량)
    private static final String MOVE_PAYLOAD_DECIMAL_QTY = """
            {"txnType":"MOVE","entries":[
               {"wh":"ICN01","loc":"A-01-01-2","sku":"SKU-200002","lot":"L20260910-B","qty":-20.0},
               {"wh":"ICN01","loc":"A-01-02-1","sku":"SKU-200002","lot":"L20260910-B","qty":20.0}]}
            """;

    @Test
    void identicalPayloadRetryReturnsSameProposalId() {
        receiveColdBrew("A-01-01-2", 100);

        CreateProposalOutcome first = proposalCreationService.create(moveRequest(MOVE_PAYLOAD), "ICN01");
        long id = ((ProposalCreated) first).proposalId();

        CreateProposalOutcome second = proposalCreationService.create(moveRequest(MOVE_PAYLOAD), "ICN01");

        assertThat(second).isEqualTo(new ProposalDuplicate(id));
        assertThat(proposalCount()).as("제안은 1건").isEqualTo(1);
        assertReconciliationClean();
    }

    @Test
    void keyOrderAndWhitespaceDoNotCreateSecondProposal() {
        receiveColdBrew("A-01-01-2", 100);

        CreateProposalOutcome first = proposalCreationService.create(moveRequest(MOVE_PAYLOAD), "ICN01");
        long id = ((ProposalCreated) first).proposalId();

        CreateProposalOutcome second = proposalCreationService
                .create(moveRequest(MOVE_PAYLOAD_KEY_ORDER_AND_WHITESPACE), "ICN01");

        assertThat(second).isEqualTo(new ProposalDuplicate(id));
        assertThat(proposalCount()).as("제안은 1건").isEqualTo(1);
        assertReconciliationClean();
    }

    @Test
    void entryArrayOrderIsCanonicalized() {
        receiveColdBrew("A-01-01-2", 100);

        CreateProposalOutcome first = proposalCreationService.create(moveRequest(MOVE_PAYLOAD), "ICN01");
        long id = ((ProposalCreated) first).proposalId();

        CreateProposalOutcome second = proposalCreationService.create(moveRequest(MOVE_PAYLOAD_ENTRIES_SWAPPED),
                "ICN01");

        assertThat(second).isEqualTo(new ProposalDuplicate(id));
        assertThat(proposalCount()).as("제안은 1건").isEqualTo(1);
        assertReconciliationClean();
    }

    @Test
    void integerAndDecimalQtyCanonicalizeToSame() {
        receiveColdBrew("A-01-01-2", 100);

        CreateProposalOutcome first = proposalCreationService.create(moveRequest(MOVE_PAYLOAD), "ICN01");
        long id = ((ProposalCreated) first).proposalId();

        CreateProposalOutcome second = proposalCreationService.create(moveRequest(MOVE_PAYLOAD_DECIMAL_QTY),
                "ICN01");

        assertThat(second).isEqualTo(new ProposalDuplicate(id));
        assertThat(proposalCount()).as("제안은 1건").isEqualTo(1);
        assertReconciliationClean();
    }

    @Test
    void tenConnectionsCreateSameProposalConcurrently_onlyOnePending() throws Exception {
        receiveColdBrew("A-01-01-2", 100);
        CreateProposalRequest request = moveRequest(MOVE_PAYLOAD);

        int workers = 10;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Future<CreateProposalOutcome>> futures = new ArrayList<>();
        try {
            CountDownLatch ready = new CountDownLatch(workers);
            CountDownLatch go = new CountDownLatch(1);
            List<Callable<CreateProposalOutcome>> tasks = IntStream.range(0, workers)
                    .<Callable<CreateProposalOutcome>>mapToObj(i -> () -> {
                        ready.countDown();
                        go.await();
                        return proposalCreationService.create(request, "ICN01");
                    })
                    .toList();
            for (Callable<CreateProposalOutcome> task : tasks) {
                futures.add(pool.submit(task));
            }
            ready.await();
            go.countDown();
        } finally {
            pool.shutdown();
        }

        List<CreateProposalOutcome> outcomes = new ArrayList<>();
        for (Future<CreateProposalOutcome> f : futures) {
            outcomes.add(f.get());
        }

        long createdCount = outcomes.stream().filter(o -> o instanceof ProposalCreated).count();
        assertThat(createdCount).as("실제로 새로 생성된 제안은 정확히 1건").isEqualTo(1);
        assertThat(pendingCount()).as("PENDING 제안도 1건").isEqualTo(1);
        assertReconciliationClean();
    }

    @Test
    void executedProposalAllowsIdenticalNewProposal() {
        receiveColdBrew("A-01-01-2", 100);

        CreateProposalOutcome first = proposalCreationService.create(moveRequest(MOVE_PAYLOAD), "ICN01");
        long firstId = ((ProposalCreated) first).proposalId();

        long txnId = postAndExpectSuccess(request("move:PROP-EXEC-01", "MOVE", null, null,
                line("ICN01", "A-01-01-2", "SKU-200002", "L20260910-B", -20),
                line("ICN01", "A-01-02-1", "SKU-200002", "L20260910-B", 20)));

        jdbcClient.sql("""
                UPDATE action_proposal
                SET status = 'EXECUTED', decided_by = 'user:test', decided_at = now(), executed_txn_id = :txnId
                WHERE id = :id
                """)
                .param("txnId", txnId)
                .param("id", firstId)
                .update();

        CreateProposalOutcome second = proposalCreationService.create(moveRequest(MOVE_PAYLOAD), "ICN01");

        assertThat(second).isInstanceOf(ProposalCreated.class);
        assertThat(((ProposalCreated) second).proposalId()).isNotEqualTo(firstId);
        assertThat(proposalCount()).as("EXECUTED 1건 + 새 PENDING 1건").isEqualTo(2);
        assertReconciliationClean();
    }

    @Test
    void unsupportedProposalTypeIsRejectedAtCreation() {
        assertThatThrownBy(() -> proposalCreationService.create(requestOfType("TRANSFER", MOVE_PAYLOAD), "ICN01"))
                .isInstanceOf(ProposalException.class);
        assertThatThrownBy(
                () -> proposalCreationService.create(requestOfType("RECEIPT_DRAFT", MOVE_PAYLOAD), "ICN01"))
                .isInstanceOf(ProposalException.class);
        assertThatThrownBy(
                () -> proposalCreationService.create(requestOfType("RESOLVE_COUNT", MOVE_PAYLOAD), "ICN01"))
                .isInstanceOf(ProposalException.class);

        String adjustmentWithoutReasonCode = """
                {"txnType":"ADJUSTMENT","entries":[
                   {"wh":"ICN01","loc":"A-01-01-2","sku":"SKU-200002","lot":"L20260910-B","qty":-5}]}
                """;
        assertThatThrownBy(
                () -> proposalCreationService.create(requestOfType("ADJUSTMENT", adjustmentWithoutReasonCode),
                        "ICN01"))
                .isInstanceOf(ProposalException.class);

        assertThat(proposalCount()).as("거부된 제안은 하나도 저장되지 않는다").isZero();
        assertReconciliationClean();
    }

    // ── 픽스처 헬퍼 ──────────────────────────────────────────────────────────────────

    private void receiveColdBrew(String locationCode, int qty) {
        postAndExpectSuccess(request("receipt:PROP-" + locationCode + ":" + qty, "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-200002", "L20260910-B", -qty),
                line("ICN01", locationCode, "SKU-200002", "L20260910-B", qty)));
    }

    private static CreateProposalRequest moveRequest(String payloadJson) {
        return requestOfType("MOVE", payloadJson);
    }

    private static CreateProposalRequest requestOfType(String proposalType, String payloadJson) {
        return new CreateProposalRequest(proposalType, payloadJson, "테스트 사유", "agent:test", null,
                List.of(BasisRef.balance("ICN01", "A-01-01-2", "SKU-200002", "L20260910-B")));
    }

    private int proposalCount() {
        return jdbcClient.sql("SELECT count(*) FROM action_proposal").query(Integer.class).single();
    }

    private int pendingCount() {
        return jdbcClient.sql("SELECT count(*) FROM action_proposal WHERE status = 'PENDING'")
                .query(Integer.class)
                .single();
    }
}
