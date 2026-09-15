package com.zerosum.inventory.count;

import static org.assertj.core.api.Assertions.assertThat;

import com.zerosum.inventory.support.AbstractIntegrationTest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 검증용: 실사 시작과 포스팅의 경합. 포스팅이 location을 FOR SHARE로 잠그고, 실사 시작의
 * count_session_id UPDATE가 그 잠금과 충돌해 진행 중인 포스팅이 끝나기를 기다린다는 것이
 * docs/05-count-session.md가 FOR KEY SHARE가 아니라 FOR SHARE를 쓰는 이유다.
 * 허용되는 결과는 둘뿐이다 — 이동이 실사 표시 전에 커밋되거나, COUNT_IN_PROGRESS로 거절되거나.
 */
class CountStartVersusPostingRaceTest extends AbstractIntegrationTest {

    @Autowired
    CountSessionGateway countSessionGateway;

    @Test
    @DisplayName("실사 시작과 이동이 20회 경합해도 중간 상태가 남지 않는다")
    void 실사_시작과_포스팅_경합() throws Exception {
        postAndExpectSuccess(request("receipt:PO-RACE:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -200),
                line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", 200)));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        int posted = 0;
        int rejected = 0;
        try {
            for (int i = 0; i < 20; i++) {
                CountDownLatch go = new CountDownLatch(1);
                int round = i;
                Future<Boolean> move = pool.submit(() -> {
                    go.await();
                    try {
                        postAndExpectSuccess(request("move:RACE-" + round, "MOVE", null, null,
                                line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", -1),
                                line("ICN01", "B-01-01-1", "SKU-100001", "DEFAULT", 1)));
                        return true;
                    } catch (RuntimeException rejectedByCount) {
                        return false;   // COUNT_IN_PROGRESS — 정상적인 결과다
                    }
                });
                Future<?> count = pool.submit(() -> {
                    go.await();
                    countSessionGateway.start(new StartCountRequest("ICN01", "A-01-01-1", "user:lee.sh"));
                    return null;
                });
                go.countDown();
                if (move.get()) {
                    posted++;
                } else {
                    rejected++;
                }
                count.get();
                // 다음 라운드를 위해 표시를 해제한다
                countSessionGateway.abandon(new AbandonCountRequest(
                        "count:RACE-" + round + ":abandon", countSessionIdOf("ICN01", "A-01-01-1"), "user:lee.sh"));
            }
        } finally {
            pool.shutdownNow();
        }

        int onHand = onHandQty("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT");
        assertThat(posted + rejected).as("20회 모두 두 결과 중 하나로 끝났다").isEqualTo(20);
        assertThat(onHand).as("커밋된 이동 수만큼만 줄었다").isEqualTo(200 - posted);
        assertReconciliationClean();
        System.out.println("  경합 결과: 커밋 " + posted + "회 / 거절 " + rejected + "회");
    }
}
