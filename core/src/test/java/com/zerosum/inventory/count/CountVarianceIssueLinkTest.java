package com.zerosum.inventory.count;

import static org.assertj.core.api.Assertions.assertThat;

import com.zerosum.inventory.reconciliation.ReconciliationService;
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

    @Autowired
    private ReconciliationService reconciliationService;

    @Test
    void countVarianceIssueCarriesValidCountSessionIdAndRecordsResolutionHistory() {
        postAndExpectSuccess(request("receipt:CNT-LINK-0001:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-300001", "DEFAULT", -100),
                line("ICN01", "A-02-01-1", "SKU-300001", "DEFAULT", 100)));

        long sessionId = countSessionGateway.start(
                new StartCountRequest("ICN01", "A-02-01-1", "user:lee.sh"));
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

    /**
     * 담당자가 "확인함"(ack)을 누른 COUNT_VARIANCE도 실사 종결에서 함께 닫혀야 한다. 종결 UPDATE가
     * 'OPEN'만 보던 동안에는 ACKED 이슈가 빠져, 차이를 실제로 정정하고 세션을 닫았는데도 이슈만 ACKED로
     * 영영 남았다 — COUNT_VARIANCE는 실사 제출 때 한 번만 만들어지므로 다시 열릴 경로도 없다.
     *
     * <p>한 세션에 차이 라인 둘을 두고 하나만 ack한다. OPEN·ACKED를 한 번에 보므로 종결 조건이 'OPEN'으로
     * 좁아져도, 반대로 'ACKED'로 갈아끼워져도 걸린다.
     */
    @Test
    void acknowledgedCountVarianceIssueIsResolvedAlongsideTheOpenOne() {
        postAndExpectSuccess(request("receipt:CNT-LINK-0002:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-300001", "DEFAULT", -100),
                line("ICN01", "A-02-01-1", "SKU-300001", "DEFAULT", 100),
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -40),
                line("ICN01", "A-02-01-1", "SKU-100001", "DEFAULT", 40)));

        long sessionId = countSessionGateway.start(
                new StartCountRequest("ICN01", "A-02-01-1", "user:lee.sh"));
        // 두 라인(-20, -4) 모두 오차(1개 이하이면서 5% 이하)를 넘는다 — REVIEW로 남고 차이 라인마다 이슈가 하나씩 열린다
        CountSubmitOutcome outcome = countSessionGateway.submit(new SubmitCountRequest("count:CNT-LINK-0002:submit",
                sessionId,
                List.of(new CountLineInput("SKU-300001", "DEFAULT", 80), new CountLineInput("SKU-100001", "DEFAULT", 36)),
                "user:lee.sh"));
        assertThat(outcome).isInstanceOf(ReviewRequired.class);

        long ackedIssueId = varianceIssueId(sessionId, "SKU-300001");
        long openIssueId = varianceIssueId(sessionId, "SKU-100001");
        reconciliationService.acknowledge(ackedIssueId, "user:ops");
        assertThat(issueRow(ackedIssueId).status()).isEqualTo("ACKED");
        assertThat(issueRow(openIssueId).status()).as("다른 하나는 OPEN 그대로 둔다").isEqualTo("OPEN");

        Long txnId = countSessionGateway.resolve(
                new ResolveCountRequest("count:CNT-LINK-0002:resolve", sessionId, "user:choi.dw"));
        assertThat(txnId).isNotNull();

        IssueRow acked = issueRow(ackedIssueId);
        assertThat(acked.status()).as("ACKED 이슈도 실사 종결에서 함께 닫힌다").isEqualTo("RESOLVED");
        assertThat(acked.resolvedTxnId()).as("정정 거래가 종결 근거로 남는다").isEqualTo(txnId);
        assertThat(acked.resolvedBy()).isEqualTo("user:choi.dw");
        assertThat(acked.ackedBy()).as("인지 이력은 종결이 덮어쓰지 않는다").isEqualTo("user:ops");

        IssueRow open = issueRow(openIssueId);
        assertThat(open.status()).as("OPEN 이슈는 여전히 닫힌다").isEqualTo("RESOLVED");
        assertThat(open.resolvedTxnId()).isEqualTo(txnId);

        assertReconciliationClean();
    }

    private record IssueRow(String status, Long resolvedTxnId, String resolvedBy, String ackedBy) {
    }

    private IssueRow issueRow(long issueId) {
        return jdbcClient.sql("SELECT status, resolved_txn_id, resolved_by, acked_by FROM inventory_issue WHERE id = :id")
                .param("id", issueId)
                .query((rs, rowNum) -> new IssueRow(rs.getString("status"), (Long) rs.getObject("resolved_txn_id"),
                        rs.getString("resolved_by"), rs.getString("acked_by")))
                .single();
    }

    private long varianceIssueId(long sessionId, String skuCode) {
        return jdbcClient.sql("""
                SELECT i.id FROM inventory_issue i JOIN sku s ON s.id = i.sku_id
                WHERE i.issue_type = 'COUNT_VARIANCE' AND i.count_session_id = :sid AND s.code = :sku
                """)
                .param("sid", sessionId)
                .param("sku", skuCode)
                .query(Long.class)
                .single();
    }
}
