package com.zerosum.inventory.allocation;

import static org.assertj.core.api.Assertions.assertThat;

import com.zerosum.inventory.domain.AllocationException;
import com.zerosum.inventory.support.AbstractIntegrationTest;
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

/** 동시 할당. 같은 잔액 행을 여러 주문이 동시에 다퉈도 가용 수량만큼만 성공해야 한다 (posting.ConcurrencyTest와 같은 취지). */
class AllocationConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private AllocationGateway allocationGateway;

    @Test
    void concurrentAllocationsBeyondAvailabilitySucceedExactlyUpToAvailable() throws Exception {
        postAndExpectSuccess(request("receipt:PO-ALLOC-CONC:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-300001", "DEFAULT", -20),
                line("ICN01", "A-02-01-1", "SKU-300001", "DEFAULT", 20)));

        int attempts = 10; // 가용 20개를 5개씩 요청하므로 정확히 4건만 성공해야 한다
        List<Boolean> results = runConcurrently(attempts, index -> {
            try {
                allocationGateway.allocate(new AllocateRequest("alloc:ORD-ALLOC-CONC-" + index,
                        "ORD-ALLOC-CONC-" + index, "ICN01", "SKU-300001", 5, false));
                return true;
            } catch (AllocationException e) {
                assertThat(e.code()).isEqualTo("INSUFFICIENT_STOCK");
                return false;
            }
        });

        long successCount = results.stream().filter(Boolean::booleanValue).count();
        assertThat(successCount).isEqualTo(4);
        assertThat(availableQty("ICN01", "A-02-01-1", "SKU-300001")).isZero();
    }

    private int availableQty(String warehouseCode, String locationCode, String skuCode) {
        return jdbcClient.sql("""
                SELECT b.on_hand_qty - b.allocated_qty FROM stock_balance b
                JOIN location l ON l.id = b.location_id JOIN warehouse w ON w.id = l.warehouse_id
                JOIN sku s ON s.id = b.sku_id
                WHERE w.code = :wh AND l.code = :loc AND s.code = :sku
                """)
                .param("wh", warehouseCode)
                .param("loc", locationCode)
                .param("sku", skuCode)
                .query(Integer.class)
                .single();
    }

    /** count개의 작업을 같은 순간에 동시 실행하고 각 결과를 인덱스 순서로 모은다 (posting.ConcurrencyTest와 같다). */
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
