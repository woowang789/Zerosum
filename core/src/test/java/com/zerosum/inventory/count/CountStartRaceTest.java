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
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 검증용: 실사 시작의 실제 동시 경합. 순차 2회 호출은 "커밋된 표시를 읽는" 경로이고,
 * 동시 경합은 "커밋되지 않은 표시를 쥔 행 잠금에서 대기하는" 다른 경로다 (db/run-concurrency.sh UC-G04).
 *
 * <p>단언이 "성공한 시작은 정확히 1건"에서 "전부 같은 세션 id를 받는다"로 바뀌었다. 시작이 멱등 기록을
 * 버리고 로케이션 행 FOR UPDATE + 열린 세션 재사용으로 바뀌면서, 경합에서 밀린 쪽은 이제 실패하지 않고
 * 먼저 만들어진 세션 id를 받기 때문이다. 지키려던 불변식("로케이션당 진행 중인 실사는 하나")은 그대로이고,
 * 오히려 더 강하게 확인한다 — 세션 행이 정확히 1개만 만들어졌는지까지 본다.
 */
class CountStartRaceTest extends AbstractIntegrationTest {

    @Autowired
    CountSessionGateway countSessionGateway;

    @Test
    @DisplayName("같은 로케이션에 10커넥션이 동시에 실사를 시작해도 세션은 하나뿐이고 전부 그 id를 받는다")
    void 같은_로케이션_동시_실사_시작() throws Exception {
        int workers = 10;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Long> returnedIds = new ArrayList<>();
        try {
            CountDownLatch ready = new CountDownLatch(workers);
            CountDownLatch go = new CountDownLatch(1);
            List<Callable<Long>> tasks = IntStream.range(0, workers).mapToObj(i -> (Callable<Long>) () -> {
                ready.countDown();
                go.await();
                // 예외를 삼키지 않는다 — 밀린 쪽도 실패하지 않고 열린 세션 id를 받는 것이 이제 정상이다
                return countSessionGateway.start(new StartCountRequest("ICN01", "A-01-01-1", "user:lee.sh"));
            }).toList();

            List<Future<Long>> futures = new ArrayList<>();
            for (Callable<Long> task : tasks) {
                futures.add(pool.submit(task));
            }
            ready.await();
            go.countDown();
            for (Future<Long> f : futures) {
                returnedIds.add(f.get());
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
        int createdSessions = jdbcClient.sql("SELECT count(*) FROM count_session").query(Integer.class).single();

        assertThat(returnedIds).as("10커넥션이 전부 같은 세션 id를 받았다").containsOnly(returnedIds.get(0));
        assertThat(createdSessions).as("만들어진 세션은 정확히 1개").isEqualTo(1);
        assertThat(activeSessions).as("진행 중 세션도 1개").isEqualTo(1);
        assertThat(countSessionIdOf("ICN01", "A-01-01-1")).as("로케이션 표시가 그 세션을 가리킨다")
                .isEqualTo(returnedIds.get(0));
        assertReconciliationClean();
    }
}
