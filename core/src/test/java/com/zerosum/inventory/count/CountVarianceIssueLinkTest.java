package com.zerosum.inventory.count;

import static org.assertj.core.api.Assertions.assertThat;

import com.zerosum.inventory.support.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * COUNT_VARIANCE 이슈의 count_session_id 외래키와 처리 이력 컬럼(V3__issue_columns_and_indexes.sql)
 * 연결을 확인한다. 예전에는 detail->>'countSessionId' JSONB 문자열 비교로만 다시 찾을 수 있었다.
 */
class CountVarianceIssueLinkTest extends AbstractIntegrationTest {

    @Autowired
    private CountSessionGateway countSessionGateway;

    @Test
    void countVarianceIssueCarriesValidCountSessionIdAndRecordsResolutionHistory() {
        postAndExpectSuccess(request("receipt:CNT-LINK-0001:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-300001", "DEFAULT", -100),
                line("ICN01", "A-02-01-1", "SKU-300001", "DEFAULT", 100)));

        long sessionId = countSessionGateway.start(
                new StartCountRequest("count:CNT-LINK-0001", "ICN01", "A-02-01-1", "user:lee.sh"));
        // 차이 -20: 오차(1개 이하이면서 5% 이하)를 넘어 REVIEW로 남는다
        CountSubmitOutcome outcome = countSessionGateway.submit(new SubmitCountRequest("count:CNT-LINK-0001:submit",
                sessionId, List.of(new CountLineInput("SKU-300001", "DEFAULT", 80)), "user:lee.sh"));
        assertThat(outcome).isInstanceOf(ReviewRequired.class);

        record OpenRow(Long countSessionId, String status) {
        }
        OpenRow opened = jdbcClient.sql("""
                SELECT i.count_session_id, i.status FROM inventory_issue i
                JOIN count_session cs ON cs.id = i.count_session_id
                WHERE i.issue_type = 'COUNT_VARIANCE' AND i.count_session_id = :sid
                """)
                .param("sid", sessionId)
                .query((rs, rowNum) -> new OpenRow((Long) rs.getObject("count_session_id"), rs.getString("status")))
                .single();
        assertThat(opened.countSessionId()).as("외래키가 유효해 count_session과 조인된다").isEqualTo(sessionId);
        assertThat(opened.status()).isEqualTo("OPEN");

        Long txnId = countSessionGateway.resolve(
                new ResolveCountRequest("count:CNT-LINK-0001:resolve", sessionId, "user:choi.dw"));
        assertThat(txnId).isNotNull();

        Boolean historyRecorded = jdbcClient.sql("""
                SELECT (status = 'RESOLVED' AND resolved_by = :resolvedBy AND resolved_at IS NOT NULL
                        AND resolved_txn_id = :txnId) AS ok
                FROM inventory_issue WHERE issue_type = 'COUNT_VARIANCE' AND count_session_id = :sid
                """)
                .param("resolvedBy", "user:choi.dw")
                .param("txnId", txnId)
                .param("sid", sessionId)
                .query(Boolean.class)
                .single();
        assertThat(historyRecorded).as("resolved_by·resolved_at·resolved_txn_id이 함께 채워진다").isTrue();

        assertReconciliationClean();
    }
}
