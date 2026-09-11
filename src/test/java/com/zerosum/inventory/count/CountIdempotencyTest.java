package com.zerosum.inventory.count;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zerosum.inventory.posting.IdempotencyConflictException;
import com.zerosum.inventory.support.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 실사 커맨드 4종(시작·제출·정정·중단)의 멱등성 — 같은 키는 재현하고, 같은 키에 다른 본문은 409로 거절한다
 * (docs/05-count-session.md: "시작, 제출, 정정, 중단 요청도 모두 멱등성의 멱등 키를 받는다").
 * db/04-harness.sql의 tst_count_*는 이 가운데 시작만 멱등 레코드를 직접 갖고 정정·중단은 내부에서 쓰는
 * tst_post의 멱등 키에 얹혀가는데, 그러면 정정을 이미 끝낸 세션에 같은 키로 재요청하면 COUNT_NOT_SUBMITTED로
 * 실패해버린다. docs의 요구(4종 모두 멱등)를 그대로 satisfy하기 위해 Java 쪽은 정정·중단에도 자체 멱등
 * 레코드를 둔다 — CountSessionService 클래스 주석 참고.
 */
class CountIdempotencyTest extends AbstractIntegrationTest {

    @Autowired
    private CountSessionGateway countSessionGateway;

    @Test
    void startReplaysOnSameKeyAndConflictsOnDifferentBody() {
        putawayTshirts("B-01-01-1", 10);

        long first = countSessionGateway.start(
                new StartCountRequest("count:CC-IDEM-01", "ICN01", "B-01-01-1", "user:lee.sh"));
        long replay = countSessionGateway.start(
                new StartCountRequest("count:CC-IDEM-01", "ICN01", "B-01-01-1", "user:lee.sh"));

        assertThat(replay).isEqualTo(first);
        assertThat(sessionCount()).as("재현은 세션을 다시 만들지 않는다").isEqualTo(1);

        assertThatThrownBy(() -> countSessionGateway.start(
                new StartCountRequest("count:CC-IDEM-01", "ICN01", "A-01-01-1", "user:lee.sh")))
                .as("같은 키에 다른 로케이션(다른 본문)은 409")
                .isInstanceOf(IdempotencyConflictException.class);

        countSessionGateway.abandon(new AbandonCountRequest("count:CC-IDEM-01:cleanup", first, "user:lee.sh"));
        assertReconciliationClean();
    }

    @Test
    void submitReplaysOnSameKeyAndConflictsOnDifferentBody() {
        putawayTshirts("B-01-01-1", 30);
        long sessionId = countSessionGateway.start(
                new StartCountRequest("count:CC-IDEM-02", "ICN01", "B-01-01-1", "user:lee.sh"));

        CountSubmitOutcome first = countSessionGateway.submit(new SubmitCountRequest("count:CC-IDEM-02:submit",
                sessionId, List.of(new CountLineInput("SKU-100001", "DEFAULT", 30)), "user:lee.sh"));
        CountSubmitOutcome replay = countSessionGateway.submit(new SubmitCountRequest("count:CC-IDEM-02:submit",
                sessionId, List.of(new CountLineInput("SKU-100001", "DEFAULT", 30)), "user:lee.sh"));

        assertThat(first).isInstanceOf(Confirmed.class);
        assertThat(replay).isEqualTo(first);
        assertThat(countResultRowCount(sessionId)).as("재현은 count_result를 다시 쓰지 않는다").isEqualTo(1);

        assertThatThrownBy(() -> countSessionGateway.submit(new SubmitCountRequest("count:CC-IDEM-02:submit",
                sessionId, List.of(new CountLineInput("SKU-100001", "DEFAULT", 29)), "user:lee.sh")))
                .as("같은 키에 다른 라인(다른 본문)은 409")
                .isInstanceOf(IdempotencyConflictException.class);

        assertReconciliationClean();
    }

    @Test
    void resolveReplaysOnSameKeyWithoutPostingTwice() {
        putawayTshirts("B-01-01-1", 30);
        long sessionId = countSessionGateway.start(
                new StartCountRequest("count:CC-IDEM-03", "ICN01", "B-01-01-1", "user:lee.sh"));
        countSessionGateway.submit(new SubmitCountRequest("count:CC-IDEM-03:submit", sessionId,
                List.of(new CountLineInput("SKU-100001", "DEFAULT", 24)), "user:lee.sh")); // 오차 초과 → REVIEW

        Long first = countSessionGateway.resolve(
                new ResolveCountRequest("count:CC-IDEM-03:resolve", sessionId, "user:choi.dw"));
        Long replay = countSessionGateway.resolve(
                new ResolveCountRequest("count:CC-IDEM-03:resolve", sessionId, "user:choi.dw"));

        assertThat(replay).isEqualTo(first);
        assertThat(adjustmentTxnCount(sessionId)).as("재현은 조정 거래를 다시 포스팅하지 않는다").isEqualTo(1);

        assertReconciliationClean();
    }

    @Test
    void abandonReplaysOnSameKeyAndConflictsOnDifferentBody() {
        putawayTshirts("B-01-01-1", 10);
        putawayTshirts("A-01-01-1", 10);
        long session1 = countSessionGateway.start(
                new StartCountRequest("count:CC-IDEM-04a", "ICN01", "B-01-01-1", "user:lee.sh"));
        long session2 = countSessionGateway.start(
                new StartCountRequest("count:CC-IDEM-04b", "ICN01", "A-01-01-1", "user:lee.sh"));

        countSessionGateway.abandon(new AbandonCountRequest("count:CC-IDEM-04:abandon", session1, "user:lee.sh"));
        // 재현: 같은 키로 다시 호출해도 예외 없이 조용히 끝난다
        countSessionGateway.abandon(new AbandonCountRequest("count:CC-IDEM-04:abandon", session1, "user:lee.sh"));
        assertThat(countSessionStatus(session1)).isEqualTo("ABANDONED");

        assertThatThrownBy(() -> countSessionGateway.abandon(
                new AbandonCountRequest("count:CC-IDEM-04:abandon", session2, "user:lee.sh")))
                .as("같은 키에 다른 세션 id(다른 본문)는 409")
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(countSessionStatus(session2)).as("두 번째 세션은 영향받지 않는다").isEqualTo("OPEN");

        countSessionGateway.abandon(new AbandonCountRequest("count:CC-IDEM-04b:cleanup", session2, "user:lee.sh"));
        assertReconciliationClean();
    }

    private void putawayTshirts(String locationCode, int qty) {
        postAndExpectSuccess(request("receipt:PO-CNT-IDEM-" + locationCode + ":1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -qty),
                line("ICN01", locationCode, "SKU-100001", "DEFAULT", qty)));
    }

    private int sessionCount() {
        return jdbcClient.sql("SELECT count(*) FROM count_session").query(Integer.class).single();
    }

    private int countResultRowCount(long sessionId) {
        return jdbcClient.sql("SELECT count(*) FROM count_result WHERE count_session_id = :id")
                .param("id", sessionId)
                .query(Integer.class)
                .single();
    }

    private int adjustmentTxnCount(long sessionId) {
        return jdbcClient.sql("""
                SELECT count(*) FROM inventory_txn
                WHERE source_type = 'COUNT' AND source_ref = :sessionId AND txn_type = 'ADJUSTMENT'
                """)
                .param("sessionId", String.valueOf(sessionId))
                .query(Integer.class)
                .single();
    }
}
