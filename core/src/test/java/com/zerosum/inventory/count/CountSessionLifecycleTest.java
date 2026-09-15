package com.zerosum.inventory.count;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zerosum.inventory.domain.PostingException;
import com.zerosum.inventory.posting.Preconditions;
import com.zerosum.inventory.support.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 실사 세션의 기본 생명주기: 시작 → 제출(차이 없음 / 오차 이내 / 오차 초과) → 정정 · 중단.
 * db/usecases/C-count-session.sql UC-C01~C09를 재현한다.
 */
class CountSessionLifecycleTest extends AbstractIntegrationTest {

    @Autowired
    private CountSessionGateway countSessionGateway;

    @Test
    void startMarksLocationAndBlocksShipmentUntilResolved() {
        putawayTshirts("B-01-01-1", 30);

        long sessionId = countSessionGateway.start(
                new StartCountRequest("ICN01", "B-01-01-1", "user:lee.sh"));

        assertThat(countSessionIdOf("ICN01", "B-01-01-1")).isEqualTo(sessionId);

        assertThatThrownBy(() -> postingGateway.post(
                request("ship:ORD-0001-1:1", "SHIPMENT", null, null,
                        line("ICN01", "B-01-01-1", "SKU-100001", "DEFAULT", -5),
                        line("ICN01", "V-CUSTOMER", "SKU-100001", "DEFAULT", 5)),
                Preconditions.none()))
                .isInstanceOf(PostingException.class)
                .extracting(ex -> ((PostingException) ex).code())
                .isEqualTo("COUNT_IN_PROGRESS");

        assertReconciliationClean();
    }

    /**
     * 전에는 두 번째 시작이 {@code uq_count_session_active} 위반으로 거절되는 것을 단언했다. 시작이 로케이션을
     * FOR UPDATE로 잠그고 열린 세션을 그대로 돌려주게 되면서 거절이 아니라 재생이 되지만, 이 테스트가
     * 지키려던 불변식("로케이션당 진행 중인 실사는 하나")은 그대로다 — 세션이 새로 생기지 않는 것을 본다.
     */
    @Test
    void secondStartOnOpenLocationReturnsTheOpenSession() {
        putawayTshirts("B-01-01-1", 30);
        long first = countSessionGateway.start(new StartCountRequest("ICN01", "B-01-01-1", "user:lee.sh"));

        long second = countSessionGateway.start(new StartCountRequest("ICN01", "B-01-01-1", "user:jo.mk"));

        assertThat(second).as("열린 세션이 그대로 돌아온다").isEqualTo(first);
        assertThat(activeSessionCount("ICN01", "B-01-01-1")).as("진행 중 세션은 여전히 하나").isEqualTo(1);
        assertThat(countSessionIdOf("ICN01", "B-01-01-1")).isEqualTo(first);

        assertReconciliationClean();
    }

    /**
     * 위 테스트는 <b>앱이</b> 두 번째 세션을 만들지 않는다는 것만 본다 — 인덱스를 스키마에서 통째로
     * 지워도 통과한다. 이 저장소의 전제는 불변식을 DB가 강제한다는 것이므로, 앱을 건너뛰고 원장에
     * 직접 손을 넣었을 때 무엇이 막는지도 증거로 남아야 한다.
     *
     * <p>고치기 전에는 시작을 두 번 불러 {@code DataIntegrityViolationException}을 받는 것으로 이
     * 인덱스를 증명했다. 이제 시작은 열린 세션을 돌려주므로 그 경로로는 인덱스에 닿지 않는다 —
     * 앱 방어가 좋아진 만큼 DB 방어의 증거가 사라졌고, 그 자리를 이 테스트가 메운다.
     */
    @Test
    void directInsertOfASecondOpenSessionIsRejectedByTheIndex() {
        putawayTshirts("B-01-01-1", 30);
        countSessionGateway.start(new StartCountRequest("ICN01", "B-01-01-1", "user:lee.sh"));

        assertThatThrownBy(() -> jdbcClient.sql("""
                INSERT INTO count_session (location_id, status, started_by)
                SELECT l.id, 'OPEN', 'user:bypass'
                FROM location l JOIN warehouse w ON w.id = l.warehouse_id
                WHERE w.code = 'ICN01' AND l.code = 'B-01-01-1'
                """).update())
                .as("부분 유니크 인덱스 uq_count_session_active가 두 번째 진행 중 세션을 막는다")
                .isInstanceOf(DataIntegrityViolationException.class);

        assertReconciliationClean();
    }

    /**
     * 그 인덱스는 <b>부분</b>이다 — OPEN·REVIEW만 막고 닫힌 세션은 몇 개든 쌓이게 둔다. 그래야
     * 같은 로케이션을 여러 번 실사할 수 있다. 부분 조건이 사라지면(전체 유니크가 되면) 재실사가
     * 다시 불가능해지므로, 막는 것만큼 막지 않는 것도 증거로 남긴다.
     */
    @Test
    void closedSessionsDoNotOccupyTheIndex() {
        putawayTshirts("B-01-01-1", 30);
        long first = countSessionGateway.start(new StartCountRequest("ICN01", "B-01-01-1", "user:lee.sh"));
        countSessionGateway.abandon(new AbandonCountRequest("count:abandon:" + first, first, "user:lee.sh"));

        long second = countSessionGateway.start(new StartCountRequest("ICN01", "B-01-01-1", "user:jo.mk"));

        assertThat(second).as("닫힌 세션은 인덱스를 점유하지 않는다").isNotEqualTo(first);
        assertReconciliationClean();
    }

    @Test
    void submitWithNoVarianceConfirmsImmediatelyWithoutAdjustment() {
        putawayTshirts("B-01-01-1", 30);
        long sessionId = countSessionGateway.start(
                new StartCountRequest("ICN01", "B-01-01-1", "user:lee.sh"));

        CountSubmitOutcome outcome = countSessionGateway.submit(new SubmitCountRequest("count:CC-0004:submit",
                sessionId, List.of(new CountLineInput("SKU-100001", "DEFAULT", 30)), "user:lee.sh"));

        assertThat(outcome).isInstanceOf(Confirmed.class);
        assertThat(((Confirmed) outcome).resolutionTxnId()).as("차이가 없으므로 정정 거래도 없다").isNull();
        assertThat(countSessionStatus(sessionId)).isEqualTo("CONFIRMED");
        assertThat(countSessionIdOf("ICN01", "B-01-01-1")).isNull();
        assertThat(onHandQty("ICN01", "B-01-01-1", "SKU-100001", "DEFAULT")).isEqualTo(30);

        assertReconciliationClean();
    }

    @Test
    void withinToleranceVarianceAutoResolvesInSameTransaction() {
        putawayTshirts("B-01-01-1", 30);
        long sessionId = countSessionGateway.start(
                new StartCountRequest("ICN01", "B-01-01-1", "user:lee.sh"));

        // 차이 -1: 오차(1개 이하이면서 5% 이하) 이내라 같은 트랜잭션에서 자동 정정된다
        CountSubmitOutcome outcome = countSessionGateway.submit(new SubmitCountRequest("count:CC-0005:submit",
                sessionId, List.of(new CountLineInput("SKU-100001", "DEFAULT", 29)), "user:lee.sh"));

        assertThat(outcome).isInstanceOf(Confirmed.class);
        Long txnId = ((Confirmed) outcome).resolutionTxnId();
        assertThat(txnId).as("정정 거래가 생겼다").isNotNull();
        assertThat(txnType(txnId)).isEqualTo("ADJUSTMENT");
        assertThat(countSessionStatus(sessionId)).isEqualTo("CONFIRMED");
        assertThat(countSessionIdOf("ICN01", "B-01-01-1")).isNull();
        assertThat(onHandQty("ICN01", "B-01-01-1", "SKU-100001", "DEFAULT")).isEqualTo(29);

        assertReconciliationClean();
    }

    @Test
    void overToleranceVarianceGoesToReviewThenResolvesOnApproval() {
        putawayTshirts("B-01-01-1", 30);
        long sessionId = countSessionGateway.start(
                new StartCountRequest("ICN01", "B-01-01-1", "user:lee.sh"));

        // 차이 -6: 오차를 넘어 REVIEW로 남는다 (1개 이하 AND 5% 이하를 둘 다 벗어남)
        CountSubmitOutcome outcome = countSessionGateway.submit(new SubmitCountRequest("count:CC-0006:submit",
                sessionId, List.of(new CountLineInput("SKU-100001", "DEFAULT", 24)), "user:lee.sh"));

        assertThat(outcome).isInstanceOf(ReviewRequired.class);
        assertThat(countSessionStatus(sessionId)).isEqualTo("REVIEW");
        assertThat(countSessionIdOf("ICN01", "B-01-01-1")).as("표시 유지").isEqualTo(sessionId);
        assertThat(onHandQty("ICN01", "B-01-01-1", "SKU-100001", "DEFAULT")).as("정정 전이라 실재고 그대로").isEqualTo(30);
        assertThat(openCountVarianceIssueCount()).isEqualTo(1);

        assertThatThrownBy(() -> postingGateway.post(
                request("ship:ORD-0002-1:1", "SHIPMENT", null, null,
                        line("ICN01", "B-01-01-1", "SKU-100001", "DEFAULT", -5),
                        line("ICN01", "V-CUSTOMER", "SKU-100001", "DEFAULT", 5)),
                Preconditions.none()))
                .isInstanceOf(PostingException.class)
                .extracting(ex -> ((PostingException) ex).code())
                .isEqualTo("COUNT_IN_PROGRESS");

        Long txnId = countSessionGateway.resolve(
                new ResolveCountRequest("count:CC-0006:resolve", sessionId, "user:choi.dw"));

        assertThat(txnId).isNotNull();
        assertThat(onHandQty("ICN01", "B-01-01-1", "SKU-100001", "DEFAULT")).isEqualTo(24);
        assertThat(countSessionIdOf("ICN01", "B-01-01-1")).isNull();
        assertThat(countSessionStatus(sessionId)).isEqualTo("CONFIRMED");
        assertThat(openCountVarianceIssueCount()).as("정정 승인으로 이슈가 RESOLVED된다").isZero();

        assertReconciliationClean();
    }

    @Test
    void abandonWithoutResolutionLeavesStockUnchanged() {
        putawayTshirts("B-01-01-1", 30);
        long sessionId = countSessionGateway.start(
                new StartCountRequest("ICN01", "B-01-01-1", "user:lee.sh"));

        countSessionGateway.abandon(new AbandonCountRequest("count:CC-0007:abandon", sessionId, "user:lee.sh"));

        assertThat(countSessionStatus(sessionId)).isEqualTo("ABANDONED");
        assertThat(countSessionIdOf("ICN01", "B-01-01-1")).isNull();
        assertThat(onHandQty("ICN01", "B-01-01-1", "SKU-100001", "DEFAULT")).isEqualTo(30);

        assertReconciliationClean();
    }

    /** 그 로케이션의 진행 중(OPEN·REVIEW) 세션 수 — uq_count_session_active가 강제하는 I10 그대로다. */
    private int activeSessionCount(String warehouseCode, String locationCode) {
        return jdbcClient.sql("""
                SELECT count(*) FROM count_session s
                JOIN location l ON l.id = s.location_id JOIN warehouse w ON w.id = l.warehouse_id
                WHERE w.code = :wh AND l.code = :loc AND s.status IN ('OPEN', 'REVIEW')
                """)
                .param("wh", warehouseCode)
                .param("loc", locationCode)
                .query(Integer.class)
                .single();
    }

    private void putawayTshirts(String locationCode, int qty) {
        postAndExpectSuccess(request("receipt:PO-CNT-" + locationCode + ":1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -qty),
                line("ICN01", locationCode, "SKU-100001", "DEFAULT", qty)));
    }
}
