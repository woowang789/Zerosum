package com.zerosum.inventory.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zerosum.inventory.domain.AllocationId;
import com.zerosum.inventory.domain.IdempotencyConflictException;
import com.zerosum.inventory.posting.PostingRequest;
import com.zerosum.inventory.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * B: 할당 멱등 키의 회차. 키가 {@code allocate:{주문줄}}이던 동안은 "이 주문 줄을 할당한다"가
 * <b>반복되는 일</b>인데 대상만으로 키를 만든 꼴이라, 해제 뒤 재할당이 첫 할당의 재생이 됐다 —
 * 호출자는 200과 id 목록을 받아 예약이 잡혔다고 믿지만 {@code allocated_qty}는 0이고 allocation 행은
 * RELEASED 하나뿐이다(조용한 초과 판매). I5는 양쪽 다 0이라 정합 검증 ②도 이것을 보지 못한다.
 *
 * <p>키가 {@code allocate:{주문줄}:{회차}}가 된 뒤 지켜야 할 셋을 각각 테스트한다:
 * ① 같은 요청의 재시도는 같은 할당을 돌려준다, ② 해제 뒤 재할당은 새 예약을 실제로 잡는다,
 * ③ 동시에 같은 주문 줄로 두 번 할당해도 예약은 한 번만 잡힌다.
 */
class AllocationRoundTest extends AbstractIntegrationTest {

    @Autowired
    private AllocationGateway allocationGateway;

    /** ② 해제 뒤 재할당 — 실측 재현(PROBE3)이 같은 id를 돌려주고 allocated_qty가 0이던 자리다. */
    @Test
    void releaseThenReallocateSameOrderLineActuallyReserves() {
        putaway120();

        AllocationResult first = allocationGateway.allocate(
                new AllocateRequest("ORD-ROUND-0001", "ICN01", "SKU-100001", 30, false));
        assertThat(allocatedQty()).isEqualTo(30);

        allocationGateway.release("release:ORD-ROUND-0001", first.allocationIds());
        assertThat(allocatedQty()).isZero();

        AllocationResult second = allocationGateway.allocate(
                new AllocateRequest("ORD-ROUND-0001", "ICN01", "SKU-100001", 30, false));

        assertThat(second.allocationIds()).as("해제된 첫 할당의 재생이 아니라 새 예약이어야 한다")
                .doesNotContainAnyElementsOf(first.allocationIds());
        assertThat(allocatedQty()).isEqualTo(30);
        assertThat(statusOf(first.allocationIds().get(0))).isEqualTo("RELEASED");
        assertThat(statusOf(second.allocationIds().get(0))).isEqualTo("ACTIVE");
        assertReconciliationClean();
    }

    /** ① 같은 요청의 재시도 — 회차가 바뀌지 않으므로 같은 키가 되고, 예약은 한 번만 잡힌다. */
    @Test
    void retryOfTheSameAllocateReturnsTheSameReservation() {
        putaway120();

        AllocationResult first = allocationGateway.allocate(
                new AllocateRequest("ORD-ROUND-0002", "ICN01", "SKU-100001", 25, false));
        AllocationResult retry = allocationGateway.allocate(
                new AllocateRequest("ORD-ROUND-0002", "ICN01", "SKU-100001", 25, false));

        assertThat(retry.allocationIds()).isEqualTo(first.allocationIds());
        assertThat(allocatedQty()).isEqualTo(25);
        assertThat(allocationRowCount("ORD-ROUND-0002")).isEqualTo(first.allocationIds().size());
    }

    /**
     * ③ 동시 중복 — 회차는 트랜잭션 안에서 읽지만 직렬화는 {@code INSERT ... ON CONFLICT DO NOTHING}의
     * 키 선점이 그대로 맡는다. 둘 다 회차 0을 계산해 같은 키를 들이밀고, 진 쪽은 이긴 트랜잭션이 끝날
     * 때까지 대기했다가 저장된 결과를 재생한다.
     */
    @Test
    void concurrentAllocateForTheSameOrderLineReservesOnlyOnce() throws Exception {
        putaway120();

        int attempts = 8;
        List<List<AllocationId>> results = runConcurrently(attempts, index -> allocationGateway.allocate(
                new AllocateRequest("ORD-ROUND-0003", "ICN01", "SKU-100001", 10, false)).allocationIds());

        assertThat(results).as("여덟 스레드가 모두 같은 할당 id를 받는다")
                .allSatisfy(ids -> assertThat(ids).isEqualTo(results.get(0)));
        assertThat(allocatedQty()).as("예약은 한 번만 잡힌다").isEqualTo(10);
        assertThat(allocationRowCount("ORD-ROUND-0003")).isEqualTo(results.get(0).size());
        assertReconciliationClean();
    }

    /** 출고가 예약을 소진한 뒤에도 그 주문 줄은 다음 차수를 위해 다시 할당할 수 있다. */
    @Test
    void reallocateAfterShipmentConsumedTheReservation() {
        putaway120();

        AllocationResult first = allocationGateway.allocate(
                new AllocateRequest("ORD-ROUND-0004", "ICN01", "SKU-100001", 20, false));
        postAndExpectSuccess(new PostingRequest(
                "shipment:ORD-ROUND-0004:1", "SHIPMENT", "USER", "user:test",
                List.of(line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", -20),
                        line("ICN01", "V-CUSTOMER", "SKU-100001", "DEFAULT", 20)),
                "ORDER", "ORD-ROUND-0004", null, null, Instant.now(),
                first.allocationIds().stream().map(AllocationId::value).toList()));
        assertThat(statusOf(first.allocationIds().get(0))).isEqualTo("CONSUMED");

        AllocationResult second = allocationGateway.allocate(
                new AllocateRequest("ORD-ROUND-0004", "ICN01", "SKU-100001", 20, false));

        assertThat(second.allocationIds()).doesNotContainAnyElementsOf(first.allocationIds());
        assertThat(allocatedQty()).isEqualTo(20);
        assertReconciliationClean();
    }

    /**
     * ACTIVE 예약이 있는 동안 같은 주문 줄을 <b>다른 수량</b>으로 요청하면 회차가 그대로라 같은 키에
     * 다른 본문이 되어 409다. 이 부분의 의미는 고치기 전과 같다 — 달라진 것은 해제·소진 뒤에는 회차가
     * 올라가 그 주문 줄이 영구히 재할당 불가로 남지 않는다는 점이다.
     */
    @Test
    void differentQtyWhileReservationIsActiveIsStillConflict() {
        putaway120();

        allocationGateway.allocate(new AllocateRequest("ORD-ROUND-0005", "ICN01", "SKU-100001", 10, false));

        assertThatThrownBy(() -> allocationGateway.allocate(
                new AllocateRequest("ORD-ROUND-0005", "ICN01", "SKU-100001", 15, false)))
                .isInstanceOf(IdempotencyConflictException.class);

        // 해제하면 회차가 올라가므로 다른 수량으로도 다시 할당할 수 있다 (예전에는 영구히 409였다).
        List<AllocationId> active = activeAllocationIds("ORD-ROUND-0005");
        allocationGateway.release("release:ORD-ROUND-0005", active);
        allocationGateway.allocate(new AllocateRequest("ORD-ROUND-0005", "ICN01", "SKU-100001", 15, false));

        assertThat(allocatedQty()).isEqualTo(15);
    }

    // ── 픽스처·조회 헬퍼 ────────────────────────────────────────────────────────────

    /**
     * 수량 0(또는 음수) 할당이 회차 키를 태우지 않는다.
     *
     * <p>이 회차 방식이 없애려던 상태가 다른 문으로 되살아날 뻔했다. qty가 0 이하면 배분 루프가 한
     * 바퀴도 돌지 않아 allocation 행이 <b>0건</b>인 채로 결과가 기록되는데, 회차는 "ACTIVE가 하나도 없는
     * {@code idem_key}"로 세므로 <b>행이 아예 없는 회차는 영원히 닫히지 않는다</b> — 그 주문 줄은 회차 0에
     * 고정돼 이후 정상 할당이 매번 409가 된다. 되돌리려면 DB를 직접 고치는 수밖에 없다.
     *
     * <p>실측으로 확인한 모양이었다: {@code qty=0 → 성공, ids=[]} 뒤 {@code qty=30 →
     * IdempotencyConflictException(allocate:ORD-...:0)}. 코어가 막는다 — :web 말고 MCP·제안 경로도
     * 이 서비스를 부르므로 창구 검증만으로는 부족하다.
     */
    @Test
    void nonPositiveQtyIsRejectedAndDoesNotBurnTheRound() {
        putaway120();

        assertThatThrownBy(() -> allocationGateway.allocate(
                new AllocateRequest("ORD-NONPOS", "ICN01", "SKU-100001", 0, false)))
                .as("수량 0은 거절된다")
                .isInstanceOf(com.zerosum.inventory.domain.AllocationException.class);
        assertThatThrownBy(() -> allocationGateway.allocate(
                new AllocateRequest("ORD-NONPOS", "ICN01", "SKU-100001", -5, false)))
                .as("음수도 마찬가지다")
                .isInstanceOf(com.zerosum.inventory.domain.AllocationException.class);

        // 그리고 그 주문 줄은 여전히 정상으로 할당된다 — 키가 타지 않았다는 증거다.
        AllocationResult ok = allocationGateway.allocate(
                new AllocateRequest("ORD-NONPOS", "ICN01", "SKU-100001", 30, false));
        assertThat(ok.allocationIds()).as("거절이 회차를 태우지 않았다").isNotEmpty();
        assertThat(allocatedQty()).isEqualTo(30);
        assertReconciliationClean();
    }

    /**
     * 회차가 일부만 닫혔으면 재생하지 않고 거절한다.
     *
     * <p>회차는 "ACTIVE가 하나도 없는 {@code idem_key}"로 센다. FEFO가 여러 로트에 걸쳐 만든 행 중
     * 일부만 해제·소진되면 회차는 <b>열린 채로</b> 남고, 같은 요청의 재생은 RELEASED·CONSUMED가 섞인
     * id 목록을 200으로 돌려주게 된다 — 호출자는 30개가 예약된 줄 알지만 실제 ACTIVE는 그보다 적다.
     * 이 회차 방식이 없애려던 "조용한 초과 판매"의 부분 버전이고, {@code allocated_qty}와 ACTIVE 합계는
     * 서로 맞으므로 정합 검증 ②도 보지 못한다.
     *
     * <p>대가는 주석에 적었다 — 부분 소진 뒤 도착한 정직한 재시도도 이 거절을 받는다. 틀린 답을
     * 200으로 주는 것보다 낫다.
     */
    @Test
    void partiallyClosedRoundIsNotReplayed() {
        // 로트 둘에 나눠 적치해 FEFO가 두 행에 걸치게 한다 — 한 행만 닫히는 상태를 만들려면 필요하다.
        postAndExpectSuccess(request("receipt:PO-PARTIAL-A:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-200002", "L20260901-A", -20),
                line("ICN01", "A-01-01-1", "SKU-200002", "L20260901-A", 20)));
        postAndExpectSuccess(request("receipt:PO-PARTIAL-B:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-200002", "L20260910-B", -20),
                line("ICN01", "A-01-01-2", "SKU-200002", "L20260910-B", 20)));

        AllocationResult first = allocationGateway.allocate(
                new AllocateRequest("ORD-PARTIAL", "ICN01", "SKU-200002", 30, false));
        assertThat(first.allocationIds()).as("두 로트에 걸쳐야 부분 상태를 만들 수 있다").hasSizeGreaterThan(1);

        // 한 행만 해제한다 — 회차는 아직 열려 있다.
        allocationGateway.release("release:ORD-PARTIAL", List.of(first.allocationIds().get(0)));

        assertThatThrownBy(() -> allocationGateway.allocate(
                new AllocateRequest("ORD-PARTIAL", "ICN01", "SKU-200002", 30, false)))
                .as("닫힌 예약이 섞인 회차를 그대로 재생하면 예약량을 부풀려 답하게 된다")
                .isInstanceOf(com.zerosum.inventory.domain.AllocationException.class)
                .hasMessageContaining("해제·소진");

        assertReconciliationClean();
    }

    private void putaway120() {
        postAndExpectSuccess(request("receipt:PO-ROUND-SEED:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -120),
                line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", 120)));
    }

    private int allocatedQty() {
        return jdbcClient.sql("""
                SELECT b.allocated_qty FROM stock_balance b
                JOIN location l ON l.id = b.location_id JOIN warehouse w ON w.id = l.warehouse_id
                JOIN sku s ON s.id = b.sku_id
                WHERE w.code = 'ICN01' AND l.code = 'A-01-01-1' AND s.code = 'SKU-100001'
                """)
                .query(Integer.class)
                .single();
    }

    private String statusOf(AllocationId id) {
        return jdbcClient.sql("SELECT status FROM allocation WHERE id = :id")
                .param("id", id.value())
                .query(String.class)
                .single();
    }

    private int allocationRowCount(String orderLineRef) {
        return jdbcClient.sql("SELECT count(*) FROM allocation WHERE order_line_ref = :ref")
                .param("ref", orderLineRef)
                .query(Integer.class)
                .single();
    }

    private List<AllocationId> activeAllocationIds(String orderLineRef) {
        return jdbcClient.sql("SELECT id FROM allocation WHERE order_line_ref = :ref AND status = 'ACTIVE' ORDER BY id")
                .param("ref", orderLineRef)
                .query((rs, rowNum) -> new AllocationId(rs.getLong("id")))
                .list();
    }

    /** count개의 작업을 같은 순간에 동시 실행한다 (allocation.AllocationConcurrencyTest와 같다). */
    private <T> List<T> runConcurrently(int count, IndexedTask<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            CountDownLatch ready = new CountDownLatch(count);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = IntStream.range(0, count)
                    .<Callable<T>>mapToObj(index -> () -> {
                        ready.countDown();
                        start.await();
                        return task.run(index);
                    })
                    .map(pool::submit)
                    .toList();

            assertThat(ready.await(10, TimeUnit.SECONDS)).as("모든 스레드가 준비될 때까지 대기").isTrue();
            start.countDown();

            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdown();
        }
    }

    private interface IndexedTask<T> {
        T run(int index) throws Exception;
    }
}
