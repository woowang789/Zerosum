package com.zerosum.inventory.posting;

import static org.assertj.core.api.Assertions.assertThat;

import com.zerosum.inventory.domain.Posted;
import com.zerosum.inventory.domain.PostingException;
import com.zerosum.inventory.domain.PostingOutcome;
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

/** 동시성. db/run-concurrency.sh의 UC-G02(같은 멱등 키)·UC-G01(같은 잔액 행 병렬 차감) 취지를 재현한다. */
class ConcurrencyTest extends AbstractIntegrationTest {

    @Test
    void concurrentRequestsWithSameIdemKeyProduceExactlyOneTransaction() throws Exception {
        postAndExpectSuccess(request("receipt:PO-CONC-0001:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -50),
                line("ICN01", "RCV-01", "SKU-100001", "DEFAULT", 50)));

        PostingRequest putaway = request("move:CONC-SAME-KEY", "MOVE", null, null,
                line("ICN01", "RCV-01", "SKU-100001", "DEFAULT", -50),
                line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", 50));

        int threadCount = 10;
        List<Long> txnIds = runConcurrently(threadCount, index -> {
            PostingOutcome outcome = postingGateway.post(putaway, Preconditions.none());
            assertThat(outcome).isInstanceOf(Posted.class);
            return ((Posted) outcome).txnId();
        });

        assertThat(txnIds).hasSize(threadCount);
        assertThat(txnIds).containsOnly(txnIds.get(0));

        Integer txnCount = jdbcClient.sql("SELECT count(*) FROM inventory_txn WHERE idem_key = :key")
                .param("key", "move:CONC-SAME-KEY")
                .query(Integer.class)
                .single();
        assertThat(txnCount).isEqualTo(1);
        assertThat(onHandQty("ICN01", "RCV-01", "SKU-100001", "DEFAULT")).isZero();
        assertThat(onHandQty("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT")).isEqualTo(50);
    }

    @Test
    void concurrentDeductionsOnSameBalanceRowSerializeWithoutGoingNegative() throws Exception {
        postAndExpectSuccess(request("receipt:PO-CONC-0002:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-300001", "DEFAULT", -20),
                line("ICN01", "A-02-01-1", "SKU-300001", "DEFAULT", 20)));

        int attempts = 10; // 20개 재고를 5개씩 깎으므로 정확히 4건만 성공해야 한다
        List<Boolean> results = runConcurrently(attempts, index -> {
            try {
                postingGateway.post(request("adjust:CONC-DEDUCT-" + index, "ADJUSTMENT", "LOST", null,
                                line("ICN01", "A-02-01-1", "SKU-300001", "DEFAULT", -5),
                                line("ICN01", "V-ADJUST", "SKU-300001", "DEFAULT", 5)),
                        Preconditions.none());
                return true;
            } catch (PostingException e) {
                assertThat(e.code()).isEqualTo("INSUFFICIENT_STOCK");
                return false;
            }
        });

        long successCount = results.stream().filter(Boolean::booleanValue).count();
        assertThat(successCount).isEqualTo(4);
        assertThat(onHandQty("ICN01", "A-02-01-1", "SKU-300001", "DEFAULT")).isZero();
    }

    /** count개의 작업을 같은 순간에 동시 실행하고 각 결과를 인덱스 순서로 모은다. */
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
