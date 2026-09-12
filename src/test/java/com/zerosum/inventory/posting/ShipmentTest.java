package com.zerosum.inventory.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zerosum.inventory.allocation.AllocateRequest;
import com.zerosum.inventory.allocation.AllocationGateway;
import com.zerosum.inventory.allocation.AllocationResult;
import com.zerosum.inventory.domain.AllocationId;
import com.zerosum.inventory.domain.Posted;
import com.zerosum.inventory.domain.PostingException;
import com.zerosum.inventory.domain.PostingOutcome;
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
 * 출고(SHIPMENT). db/usecases/A-inbound-outbound.sql UC-A04~A05와 db/usecases/D-invariants.sql
 * UC-D14를 (이번 범위에서 실제로 쓸 수 있게 된) 진짜 SHIPMENT로 재현한다.
 */
class ShipmentTest extends AbstractIntegrationTest {

    @Autowired
    private AllocationGateway allocationGateway;

    @Test
    void shipmentConsumesAllocationAcrossTwoLotsAndReducesOnHandAndAllocatedTogether() {
        postAndExpectSuccess(request("receipt:PO-SHIP-0001:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-200002", "L20260910-B", -80),
                line("ICN01", "A-01-01-2", "SKU-200002", "L20260910-B", 80)));
        postAndExpectSuccess(request("receipt:PO-SHIP-0002:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-200002", "L20260901-A", -50),
                line("ICN01", "A-01-02-1", "SKU-200002", "L20260901-A", 50)));

        AllocationResult allocated = allocationGateway.allocate(
                new AllocateRequest("alloc:ORD-SHIP-0001-1", "ORD-SHIP-0001-1", "ICN01", "SKU-200002", 60, false));

        postAndExpectSuccess(shipmentRequest("ship:ORD-SHIP-0001-1:1", allocationIdValues(allocated),
                line("ICN01", "A-01-02-1", "SKU-200002", "L20260901-A", -50),
                line("ICN01", "V-CUSTOMER", "SKU-200002", "L20260901-A", 50),
                line("ICN01", "A-01-01-2", "SKU-200002", "L20260910-B", -10),
                line("ICN01", "V-CUSTOMER", "SKU-200002", "L20260910-B", 10)));

        assertThat(onHandQty("ICN01", "A-01-02-1", "SKU-200002", "L20260901-A")).isZero();
        assertThat(onHandQty("ICN01", "A-01-01-2", "SKU-200002", "L20260910-B")).isEqualTo(70);
        assertThat(allocatedQty("ICN01", "A-01-01-2", "SKU-200002", "L20260910-B"))
                .as("소진된 할당만큼 allocated_qty도 함께 줄어야 한다").isZero();

        Integer consumedCount = jdbcClient.sql(
                        "SELECT count(*) FROM allocation WHERE order_line_ref = :ref AND status = 'CONSUMED'")
                .param("ref", "ORD-SHIP-0001-1")
                .query(Integer.class)
                .single();
        assertThat(consumedCount).as("두 로트에 걸친 할당 2건 모두 CONSUMED").isEqualTo(2);
    }

    @Test
    void reversingReceiptAlreadyShippedFailsViaNegativePrevention() {
        long receiptTxnId = postAndExpectSuccess(request("receipt:PO-SHIP-D14:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-300001", "DEFAULT", -20),
                line("ICN01", "A-02-01-1", "SKU-300001", "DEFAULT", 20)));

        AllocationResult allocated = allocationGateway.allocate(
                new AllocateRequest("alloc:ORD-SHIP-D14-1", "ORD-SHIP-D14-1", "ICN01", "SKU-300001", 15, false));
        postAndExpectSuccess(shipmentRequest("ship:ORD-SHIP-D14-1:1", allocationIdValues(allocated),
                line("ICN01", "A-02-01-1", "SKU-300001", "DEFAULT", -15),
                line("ICN01", "V-CUSTOMER", "SKU-300001", "DEFAULT", 15)));
        assertThat(onHandQty("ICN01", "A-02-01-1", "SKU-300001", "DEFAULT")).isEqualTo(5);

        // 20개 입고를 통째로 되돌리려 하면 이미 15개가 나가 5개뿐이라 음수 방지에 걸린다 (정정 순서를 알려준다)
        assertThatThrownBy(() -> postingGateway.post(
                request("reverse:PO-SHIP-D14", "REVERSAL", null, receiptTxnId,
                        line("ICN01", "V-SUPPLIER", "SKU-300001", "DEFAULT", 20),
                        line("ICN01", "A-02-01-1", "SKU-300001", "DEFAULT", -20)),
                Preconditions.none()))
                .isInstanceOf(PostingException.class)
                .extracting(ex -> ((PostingException) ex).code())
                .isEqualTo("INSUFFICIENT_STOCK");
    }

    @Test
    void concurrentShipmentsWithSameIdemKeyProduceExactlyOneTransaction() throws Exception {
        postAndExpectSuccess(request("receipt:PO-SHIP-CONC:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -50),
                line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", 50)));
        AllocationResult allocated = allocationGateway.allocate(
                new AllocateRequest("alloc:ORD-SHIP-CONC-1", "ORD-SHIP-CONC-1", "ICN01", "SKU-100001", 50, false));

        PostingRequest shipment = shipmentRequest("ship:ORD-SHIP-CONC-1:1", allocationIdValues(allocated),
                line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", -50),
                line("ICN01", "V-CUSTOMER", "SKU-100001", "DEFAULT", 50));

        int threadCount = 10;
        List<Long> txnIds = runConcurrently(threadCount, index -> {
            PostingOutcome outcome = postingGateway.post(shipment, Preconditions.none());
            assertThat(outcome).isInstanceOf(Posted.class);
            return ((Posted) outcome).txnId();
        });

        assertThat(txnIds).hasSize(threadCount);
        assertThat(txnIds).containsOnly(txnIds.get(0));

        Integer txnCount = jdbcClient.sql("SELECT count(*) FROM inventory_txn WHERE idem_key = :key")
                .param("key", "ship:ORD-SHIP-CONC-1:1")
                .query(Integer.class)
                .single();
        assertThat(txnCount).isEqualTo(1);
        assertThat(onHandQty("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT")).isZero();

        Integer consumedCount = jdbcClient.sql(
                        "SELECT count(*) FROM allocation WHERE order_line_ref = :ref AND status = 'CONSUMED'")
                .param("ref", "ORD-SHIP-CONC-1")
                .query(Integer.class)
                .single();
        assertThat(consumedCount).as("동시 요청 중 실제 소진은 한 번뿐").isEqualTo(1);
    }

    private static List<Long> allocationIdValues(AllocationResult result) {
        return result.allocationIds().stream().map(AllocationId::value).toList();
    }

    private static PostingRequest shipmentRequest(String idemKey, List<Long> consumeAllocationIds,
            PostingLineInput... lines) {
        return new PostingRequest(idemKey, "SHIPMENT", "USER", "user:test", List.of(lines),
                "ORDER", idemKey, null, null, Instant.now(), consumeAllocationIds);
    }

    private int allocatedQty(String warehouseCode, String locationCode, String skuCode, String lotNo) {
        return jdbcClient.sql("""
                SELECT b.allocated_qty FROM stock_balance b
                JOIN location l ON l.id = b.location_id JOIN warehouse w ON w.id = l.warehouse_id
                JOIN sku s ON s.id = b.sku_id JOIN lot lo ON lo.id = b.lot_id
                WHERE w.code = :wh AND l.code = :loc AND s.code = :sku AND lo.lot_no = :lot
                """)
                .param("wh", warehouseCode)
                .param("loc", locationCode)
                .param("sku", skuCode)
                .param("lot", lotNo)
                .query(Integer.class)
                .single();
    }

    /** count개의 작업을 같은 순간에 동시 실행하고 각 결과를 인덱스 순서로 모은다 (ConcurrencyTest와 같다). */
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
