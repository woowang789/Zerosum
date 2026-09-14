package com.zerosum.inventory.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zerosum.inventory.domain.IssueException;
import com.zerosum.inventory.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * inventory_issue 처리(인지·종결) 경로. 배치(ReconciliationService#runOnce())가 만든 이슈는 스스로
 * 닫히지 않으므로, 사람이 acknowledge()·resolve()를 불러야 닫힌다.
 */
class ReconciliationIssueLifecycleTest extends AbstractIntegrationTest {

    @Autowired
    private ReconciliationService reconciliationService;

    @Test
    void acknowledgeMarksIssueAckedWithActorAndTimestamp() {
        long issueId = insertOpenIssue();

        reconciliationService.acknowledge(issueId, "user:ops");

        record Row(String status, String ackedBy, boolean ackedAtSet) {
        }
        Row row = jdbcClient.sql("""
                SELECT status, acked_by, (acked_at IS NOT NULL) AS acked_at_set FROM inventory_issue WHERE id = :id
                """)
                .param("id", issueId)
                .query((rs, rowNum) -> new Row(rs.getString("status"), rs.getString("acked_by"),
                        rs.getBoolean("acked_at_set")))
                .single();
        assertThat(row.status()).isEqualTo("ACKED");
        assertThat(row.ackedBy()).isEqualTo("user:ops");
        assertThat(row.ackedAtSet()).isTrue();
    }

    @Test
    void resolveWithTransactionLinksResolvedTxnId() {
        long issueId = insertOpenIssue();
        long txnId = postAndExpectSuccess(request("receipt:ISSUE-RESOLVE-0001:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -1),
                line("ICN01", "RCV-01", "SKU-100001", "DEFAULT", 1)));

        // OPEN → RESOLVED 직행 (ACKED를 건너뛴다)
        reconciliationService.resolve(issueId, "user:ops", txnId, null);

        record Row(String status, Long resolvedTxnId, String resolvedBy, boolean resolvedAtSet) {
        }
        Row row = jdbcClient.sql("""
                SELECT status, resolved_txn_id, resolved_by, (resolved_at IS NOT NULL) AS resolved_at_set
                FROM inventory_issue WHERE id = :id
                """)
                .param("id", issueId)
                .query((rs, rowNum) -> new Row(rs.getString("status"), (Long) rs.getObject("resolved_txn_id"),
                        rs.getString("resolved_by"), rs.getBoolean("resolved_at_set")))
                .single();
        assertThat(row.status()).isEqualTo("RESOLVED");
        assertThat(row.resolvedTxnId()).isEqualTo(txnId);
        assertThat(row.resolvedBy()).isEqualTo("user:ops");
        assertThat(row.resolvedAtSet()).isTrue();
    }

    @Test
    void resolveWithoutTransactionKeepsResolvedTxnIdNullAndRecordsNote() {
        long issueId = insertOpenIssue();

        reconciliationService.resolve(issueId, "user:ops", null, "코드를 고쳤다. 과거 원장은 그대로 둔다.");

        record Row(String status, Long resolvedTxnId, String note) {
        }
        Row row = jdbcClient.sql("SELECT status, resolved_txn_id, resolution_note FROM inventory_issue WHERE id = :id")
                .param("id", issueId)
                .query((rs, rowNum) -> new Row(rs.getString("status"), (Long) rs.getObject("resolved_txn_id"),
                        rs.getString("resolution_note")))
                .single();
        assertThat(row.status()).isEqualTo("RESOLVED");
        assertThat(row.resolvedTxnId()).as("거래 없이 종결하면 resolved_txn_id는 널이다").isNull();
        assertThat(row.note()).isEqualTo("코드를 고쳤다. 과거 원장은 그대로 둔다.");
    }

    @Test
    void resolvingAlreadyResolvedIssueIsRejected() {
        long issueId = insertOpenIssue();
        reconciliationService.resolve(issueId, "user:ops", null, "1차 종결");

        assertThatThrownBy(() -> reconciliationService.resolve(issueId, "user:ops2", null, "2차 종결 시도"))
                .isInstanceOf(IssueException.class)
                .extracting(ex -> ((IssueException) ex).code())
                .isEqualTo("ISSUE_ALREADY_RESOLVED");

        // 거절된 시도가 기존 값을 건드리지 않았다
        String note = jdbcClient.sql("SELECT resolution_note FROM inventory_issue WHERE id = :id")
                .param("id", issueId)
                .query(String.class)
                .single();
        assertThat(note).isEqualTo("1차 종결");
    }

    @Test
    void acknowledgedIssueIsNeitherDuplicatedNorRevertedByRerun() {
        postAndExpectSuccess(request("receipt:ISSUE-DEDUP-0001:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -20),
                line("ICN01", "B-01-01-1", "SKU-100001", "DEFAULT", 20)));
        corruptOnHandQty("B-01-01-1", "SKU-100001", 4);

        assertThat(reconciliationService.runOnce()).isEqualTo(1);
        long issueId = singleIssueId("PROJECTION_MISMATCH");
        reconciliationService.acknowledge(issueId, "user:ops");

        // 불일치가 그대로인 채 다시 돌려도 — OPEN·ACKED가 dedup 대상이므로 ACKED된 이슈는 새로 만들지 않는다
        assertThat(reconciliationService.runOnce()).as("ACKED된 이슈는 새로 만들지 않는다").isZero();
        assertThat(countIssues("PROJECTION_MISMATCH")).isEqualTo(1);
        String status = jdbcClient.sql("SELECT status FROM inventory_issue WHERE id = :id")
                .param("id", issueId)
                .query(String.class)
                .single();
        assertThat(status).as("되살아나지(=OPEN으로 되돌아가지) 않는다").isEqualTo("ACKED");

        corruptOnHandQty("B-01-01-1", "SKU-100001", -4); // 원복
    }

    @Test
    void resolvedIssueDoesNotBlockANewIssueIfTheSameMismatchRecursButTheOldRowStaysUntouched() {
        postAndExpectSuccess(request("receipt:ISSUE-DEDUP-0002:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -15),
                line("ICN01", "B-01-01-1", "SKU-100001", "DEFAULT", 15)));
        corruptOnHandQty("B-01-01-1", "SKU-100001", 6);

        assertThat(reconciliationService.runOnce()).isEqualTo(1);
        long firstIssueId = singleIssueId("PROJECTION_MISMATCH");
        reconciliationService.resolve(firstIssueId, "user:ops", null, "확인해보니 무해했다고 판단");

        // dedup은 OPEN·ACKED만 대상이다(3단계 작업 지시의 "이미 열려 있는(OPEN/ACKED)"이라는 표현을 그대로
        // 따른 판단) — RESOLVED는 재발 감지를 막지 않는다. 재발을 영영 숨기면 실제로 반복되는 버그를
        // 놓치게 된다.
        assertThat(reconciliationService.runOnce()).as("불일치가 그대로면 재발로 보고 새 이슈를 만든다").isEqualTo(1);
        assertThat(countIssues("PROJECTION_MISMATCH")).isEqualTo(2);

        String oldStatus = jdbcClient.sql("SELECT status FROM inventory_issue WHERE id = :id")
                .param("id", firstIssueId)
                .query(String.class)
                .single();
        assertThat(oldStatus).as("기존 RESOLVED 이슈는 되살아나지 않는다").isEqualTo("RESOLVED");

        corruptOnHandQty("B-01-01-1", "SKU-100001", -6); // 원복
    }

    @Test
    void checkConstraintRejectsResolvedStatusWithoutResolvedAt() {
        long issueId = insertOpenIssue();

        // V3__issue_columns_and_indexes.sql의 CHECK((status = 'RESOLVED') = (resolved_at IS NOT NULL))
        // 직접 확인 — resolved_at을 빠뜨린 UPDATE는 23514로 거절되어야 한다(조용히 채워주지 않는다).
        assertThatThrownBy(() -> jdbcClient.sql("UPDATE inventory_issue SET status = 'RESOLVED' WHERE id = :id")
                .param("id", issueId)
                .update())
                .as("status가 RESOLVED면 resolved_at이 있어야 한다")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void checkConstraintRejectsAckedAtWhileStatusStaysOpen() {
        long issueId = insertOpenIssue();

        // V3__issue_columns_and_indexes.sql의 CHECK(status <> 'OPEN' OR acked_at IS NULL) 직접 확인.
        assertThatThrownBy(() -> jdbcClient.sql("UPDATE inventory_issue SET acked_at = now() WHERE id = :id")
                .param("id", issueId)
                .update())
                .as("status가 OPEN이면 acked_at은 NULL이어야 한다")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private long insertOpenIssue() {
        return jdbcClient.sql("""
                INSERT INTO inventory_issue (issue_type, severity, detail) VALUES ('TEST_ISSUE', 'LOW', '{}'::JSONB)
                RETURNING id
                """)
                .query(Long.class)
                .single();
    }

    private void corruptOnHandQty(String locationCode, String skuCode, int delta) {
        jdbcClient.sql("""
                UPDATE stock_balance SET on_hand_qty = on_hand_qty + :delta
                WHERE location_id = (SELECT id FROM location WHERE code = :loc
                                      AND warehouse_id = (SELECT id FROM warehouse WHERE code = 'ICN01'))
                  AND sku_id = (SELECT id FROM sku WHERE code = :sku)
                """)
                .param("delta", delta)
                .param("loc", locationCode)
                .param("sku", skuCode)
                .update();
    }

    private long singleIssueId(String issueType) {
        return jdbcClient.sql("SELECT id FROM inventory_issue WHERE issue_type = :type")
                .param("type", issueType)
                .query(Long.class)
                .single();
    }

    private int countIssues(String issueType) {
        return jdbcClient.sql("SELECT count(*) FROM inventory_issue WHERE issue_type = :type")
                .param("type", issueType)
                .query(Integer.class)
                .single();
    }
}
