package com.zerosum.inventory.count;

import static org.assertj.core.api.Assertions.assertThat;

import com.zerosum.inventory.support.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 회귀: 순환 실사는 정기적으로 도는 업무이므로 한 로케이션을 몇 번이고 다시 실사할 수 있어야 한다.
 * 실사가 끝나는 경로는 둘(CONFIRMED·ABANDONED)이므로 둘 다 확인한다 — 하나만 보면 반쪽이다.
 */
class RecountSameLocationTest extends AbstractIntegrationTest {

    @Autowired
    private CountSessionGateway countSessionGateway;

    @Test
    @DisplayName("포기한 뒤 같은 로케이션을 다시 실사할 수 있다")
    void 포기_뒤_재실사() {
        putawayTshirts("B-01-01-1", 30);

        long first = start("ICN01", "B-01-01-1");
        countSessionGateway.abandon(new AbandonCountRequest("count:RECOUNT-AB:abandon", first, "user:lee.sh"));
        assertThat(countSessionStatus(first)).isEqualTo("ABANDONED");

        long second = start("ICN01", "B-01-01-1");

        assertThat(second).as("닫힌 세션의 재생이 아니라 새 세션이다").isNotEqualTo(first);
        assertThat(countSessionStatus(second)).as("두 번째 세션은 실제로 OPEN이다").isEqualTo("OPEN");
        assertThat(countSessionIdOf("ICN01", "B-01-01-1")).as("로케이션 표시가 새 세션을 가리킨다").isEqualTo(second);

        countSessionGateway.abandon(new AbandonCountRequest("count:RECOUNT-AB:abandon2", second, "user:lee.sh"));
        assertReconciliationClean();
    }

    @Test
    @DisplayName("정상 종료한 뒤 같은 로케이션을 다시 실사할 수 있다")
    void 확정_뒤_재실사() {
        putawayTshirts("B-01-01-1", 30);

        long first = start("ICN01", "B-01-01-1");
        countSessionGateway.submit(new SubmitCountRequest("count:RECOUNT-CF:submit", first,
                List.of(new CountLineInput("SKU-100001", "DEFAULT", 30)), "user:lee.sh"));
        assertThat(countSessionStatus(first)).isEqualTo("CONFIRMED");

        long second = start("ICN01", "B-01-01-1");

        assertThat(second).as("닫힌 세션의 재생이 아니라 새 세션이다").isNotEqualTo(first);
        assertThat(countSessionStatus(second)).as("두 번째 세션은 실제로 OPEN이다").isEqualTo("OPEN");
        assertThat(countSessionIdOf("ICN01", "B-01-01-1")).as("로케이션 표시가 새 세션을 가리킨다").isEqualTo(second);

        countSessionGateway.abandon(new AbandonCountRequest("count:RECOUNT-CF:abandon", second, "user:lee.sh"));
        assertReconciliationClean();
    }

    // 웹 경로(CountController.start)와 같은 방식으로 호출한다 — 두 번째 실사도 같은 (창고, 로케이션)이다.
    private long start(String warehouseCode, String locationCode) {
        return countSessionGateway.start(new StartCountRequest(warehouseCode, locationCode, "user:lee.sh"));
    }

    private void putawayTshirts(String locationCode, int qty) {
        postAndExpectSuccess(request("receipt:PO-RECOUNT-" + locationCode + ":1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -qty),
                line("ICN01", locationCode, "SKU-100001", "DEFAULT", qty)));
    }
}
