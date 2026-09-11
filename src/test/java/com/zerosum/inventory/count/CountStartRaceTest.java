package com.zerosum.inventory.count;

import static org.assertj.core.api.Assertions.assertThat;

import com.zerosum.inventory.support.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 검증용: 실사 시작의 실제 동시 경합. 순차 2회 호출은 "커밋된 충돌 행"을 만나는 경로이고,
 * 동시 경합은 "커밋되지 않은 충돌 행에서 대기하는" 다른 경로다 (db/run-concurrency.sh UC-G04).
 */
class CountStartRaceTest extends AbstractIntegrationTest {

    @Autowired
    CountSessionGateway countSessionGateway;

    @Test
    @DisplayName("같은 로케이션에 10커넥션이 동시에 실사를 시작해도 진행 중 세션은 하나뿐이다")
    void 같은_로케이션_동시_실사_시작() throws Exception {
        int workers = 10;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        AtomicInteger succeeded = new AtomicInteger();
        try {
            CountDownLatch ready = new CountDownLatch(workers);
            CountDownLatch go = new CountDownLatch(1);
            List<Callable<Void>> tasks = IntStream.range(0, workers).mapToObj(i -> (Callable<Void>) () -> {
                ready.countDown();
                go.await();
                try {
                    countSessionGateway.start(new StartCountRequest(
                            "count:RACE-" + i, "ICN01", "A-01-01-1", "user:lee.sh"));
                    succeeded.incrementAndGet();
                } catch (RuntimeException expected) {
                    // 경합에서 밀린 쪽은 실패하는 것이 정상이다
                }
                return null;
            }).toList();

            List<Future<Void>> futures = new ArrayList<>();
            for (Callable<Void> task : tasks) {
                futures.add(pool.submit(task));
            }
            ready.await();
            go.countDown();
            for (Future<Void> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        int activeSessions = jdbcClient.sql("""
                SELECT count(*) FROM count_session
                WHERE location_id = (SELECT l.id FROM location l JOIN warehouse w ON w.id = l.warehouse_id
                                      WHERE w.code = 'ICN01' AND l.code = 'A-01-01-1')
                  AND status IN ('OPEN', 'REVIEW')
                """).query(Integer.class).single();

        assertThat(succeeded.get()).as("성공한 시작은 정확히 1건").isEqualTo(1);
        assertThat(activeSessions).as("진행 중 세션도 1개").isEqualTo(1);
        assertReconciliationClean();
    }
}
