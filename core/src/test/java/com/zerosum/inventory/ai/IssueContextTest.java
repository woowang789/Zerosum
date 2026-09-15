package com.zerosum.inventory.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.zerosum.inventory.count.CountLineInput;
import com.zerosum.inventory.count.CountSessionGateway;
import com.zerosum.inventory.count.CountSubmitOutcome;
import com.zerosum.inventory.count.ResolveCountRequest;
import com.zerosum.inventory.count.ReviewRequired;
import com.zerosum.inventory.count.StartCountRequest;
import com.zerosum.inventory.count.SubmitCountRequest;
import com.zerosum.inventory.reconciliation.ReconciliationService;
import com.zerosum.inventory.repository.AiQueryRepository.LedgerRow;
import com.zerosum.inventory.support.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link AiQueryService#getIssueContext}의 앵커 규칙(COALESCE(detail의 ledgerEntryId, 파티션 최신
 * 원장 id))과 파티션 경계를 확인한다. 이슈를 만들어내는 기법은
 * {@link com.zerosum.inventory.reconciliation.ReconciliationChainAndVirtualLocationTest}·
 * {@link com.zerosum.inventory.reconciliation.ReconciliationCountFlagTest}와 같다.
 */
class IssueContextTest extends AbstractIntegrationTest {

    @Autowired
    private AiQueryService aiQueryService;

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private CountSessionGateway countSessionGateway;

    @Test
    void chainBreakContextAnchorsTheFirstBreakAndGivesSurroundingLinesFromSamePartitionOnly() throws SQLException {
        long entryA = postReceiptAndCaptureLatestEntryId("receipt:AICTX-CB-0001:1", "A-01-01-1", "SKU-100001", 40);
        long entryB = postReceiptAndCaptureLatestEntryId("receipt:AICTX-CB-0002:1", "A-01-01-1", "SKU-100001", 10);
        long entryC = postReceiptAndCaptureLatestEntryId("receipt:AICTX-CB-0003:1", "A-01-01-1", "SKU-100001", 5);
        // 다른 파티션(다른 SKU) 잡음 — 컨텍스트에 섞이면 안 된다
        long otherPartitionEntry = postReceiptAndCaptureLatestEntryId("receipt:AICTX-CB-0004:1", "A-01-01-1",
                "SKU-100002", 7);

        corruptLedgerOnHandAfter(entryB, 5); // 두 번째 줄을 조작 — 최초 깨짐이 entryA가 아니라 entryB가 되게 한다
        assertThat(reconciliationService.runOnce()).isEqualTo(1);
        long issueId = jdbcClient.sql("SELECT id FROM inventory_issue WHERE issue_type = 'CHAIN_BREAK'")
                .query(Long.class)
                .single();

        IssueContext ctx = aiQueryService.getIssueContext("ICN01", issueId);
        assertThat(ctx.anchorLedgerEntryId()).as("detail의 ledgerEntryId(최초 깨짐)를 앵커로 쓴다").isEqualTo(entryB);
        assertThat(ctx.surroundingLedger()).extracting(LedgerRow::ledgerEntryId)
                .as("앵커 전후 줄을 포함하고 다른 파티션(다른 SKU)은 섞이지 않는다")
                .contains(entryA, entryB, entryC)
                .doesNotContain(otherPartitionEntry);
        assertThat(ctx.surroundingLedger()).allSatisfy(row -> assertThat(row.skuCode()).isEqualTo("SKU-100001"));

        corruptLedgerOnHandAfter(entryB, -5); // 원복
        assertReconciliationClean();
    }

    @Test
    void projectionMismatchAnchorsToThePartitionsLatestLedgerRow() {
        postReceiptAndCaptureLatestEntryId("receipt:AICTX-PM-0001:1", "A-01-02-1", "SKU-100001", 20);
        long latestEntryId = postReceiptAndCaptureLatestEntryId("receipt:AICTX-PM-0002:1", "A-01-02-1", "SKU-100001",
                5);

        corruptOnHandQty("A-01-02-1", "SKU-100001", 3);
        assertThat(reconciliationService.runOnce()).isEqualTo(1);
        long issueId = jdbcClient.sql("SELECT id FROM inventory_issue WHERE issue_type = 'PROJECTION_MISMATCH'")
                .query(Long.class)
                .single();

        IssueContext ctx = aiQueryService.getIssueContext("ICN01", issueId);
        assertThat(ctx.anchorLedgerEntryId()).as("detail에 ledgerEntryId가 없으니 파티션 마지막 줄이 앵커가 된다")
                .isEqualTo(latestEntryId);

        corruptOnHandQty("A-01-02-1", "SKU-100001", -3); // 원복
        assertReconciliationClean();
    }

    @Test
    void countVarianceContextIncludesTheCountHistory() {
        postAndExpectSuccess(request("receipt:AICTX-CV-0001:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-300001", "DEFAULT", -100),
                line("ICN01", "A-02-01-1", "SKU-300001", "DEFAULT", 100)));

        long sessionId = countSessionGateway.start(
                new StartCountRequest("ICN01", "A-02-01-1", "user:test"));
        CountSubmitOutcome outcome = countSessionGateway.submit(new SubmitCountRequest("count:AICTX-CV-0001:submit",
                sessionId, List.of(new CountLineInput("SKU-300001", "DEFAULT", 80)), "user:test"));
        assertThat(outcome).isInstanceOf(ReviewRequired.class);

        long issueId = jdbcClient.sql("""
                SELECT id FROM inventory_issue WHERE issue_type = 'COUNT_VARIANCE' AND count_session_id = :sid
                """)
                .param("sid", sessionId)
                .query(Long.class)
                .single();

        IssueContext ctx = aiQueryService.getIssueContext("ICN01", issueId);
        assertThat(ctx.countHistory())
                .as("실사 이력에 이번 실사 결과가 들어간다")
                .anySatisfy(h -> {
                    assertThat(h.systemQty()).isEqualTo(100);
                    assertThat(h.countedQty()).isEqualTo(80);
                    assertThat(h.diffQty()).isEqualTo(-20);
                });

        countSessionGateway.resolve(new ResolveCountRequest("count:AICTX-CV-0001:resolve", sessionId, "user:test"));
        assertReconciliationClean();
    }

    @Test
    void countFlagMismatchContextDoesNotBlowUpWithNullSkuAndLot() {
        long sessionId = danglingCountFlagOn("A-01-01-2");
        assertThat(reconciliationService.runOnce()).isEqualTo(1);
        long issueId = jdbcClient.sql("SELECT id FROM inventory_issue WHERE issue_type = 'COUNT_FLAG_MISMATCH'")
                .query(Long.class)
                .single();

        IssueContext ctx = aiQueryService.getIssueContext("ICN01", issueId);
        assertThat(ctx.issue().skuId()).isNull();
        assertThat(ctx.issue().lotId()).isNull();
        assertThat(ctx.anchorLedgerEntryId()).as("파티션이 없으니 앵커는 0").isZero();
        assertThat(ctx.surroundingLedger()).isEmpty();
        assertThat(ctx.countHistory()).isEmpty();

        clearFlag("A-01-01-2");
        assertThat(sessionId).isPositive(); // 실사 세션은 원복 대상이 아니다 (ABANDONED로 남아도 무방)
        assertReconciliationClean();
    }

    /** RECEIPT를 하나 posting하고, 그 라인이 만든 물리 로케이션 쪽 원장 id를 돌려준다. */
    private long postReceiptAndCaptureLatestEntryId(String idemKey, String locationCode, String skuCode, int qty) {
        postAndExpectSuccess(request(idemKey, "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", skuCode, "DEFAULT", -qty),
                line("ICN01", locationCode, skuCode, "DEFAULT", qty)));
        Ids ids = resolveIds("ICN01", locationCode, skuCode, "DEFAULT");
        return jdbcClient.sql("""
                SELECT max(id) FROM inventory_ledger_entry
                WHERE location_id = :loc AND sku_id = :sku AND on_hand_after IS NOT NULL
                """)
                .param("loc", ids.locationId())
                .param("sku", ids.skuId())
                .query(Long.class)
                .single();
    }

    // app_rw(jdbcClient)에는 원장 UPDATE 권한이 없다(I4) — migrator 연결로 직접 조작한다
    // (ReconciliationChainAndVirtualLocationTest와 같은 이유).
    private void corruptLedgerOnHandAfter(long ledgerEntryId, int delta) throws SQLException {
        try (Connection migrator = migratorConnection();
                PreparedStatement ps = migrator.prepareStatement(
                        "UPDATE inventory_ledger_entry SET on_hand_after = on_hand_after + ? WHERE id = ?")) {
            ps.setInt(1, delta);
            ps.setLong(2, ledgerEntryId);
            ps.executeUpdate();
        }
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

    /** UC-F06a와 동일: 세션 없이(ABANDONED) 로케이션 표시만 남은 상태를 만든다. */
    private long danglingCountFlagOn(String locationCode) {
        long sessionId = jdbcClient.sql("""
                INSERT INTO count_session (location_id, started_by)
                SELECT id, 'user:test' FROM location
                WHERE code = :loc AND warehouse_id = (SELECT id FROM warehouse WHERE code = 'ICN01')
                RETURNING id
                """)
                .param("loc", locationCode)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                UPDATE location SET count_session_id = :sid
                WHERE code = :loc AND warehouse_id = (SELECT id FROM warehouse WHERE code = 'ICN01')
                """)
                .param("sid", sessionId)
                .param("loc", locationCode)
                .update();
        jdbcClient.sql("""
                UPDATE count_session SET status = 'ABANDONED', closed_by = 'user:test', closed_at = now()
                WHERE id = :sid
                """)
                .param("sid", sessionId)
                .update();
        return sessionId;
    }

    private void clearFlag(String locationCode) {
        jdbcClient.sql("""
                UPDATE location SET count_session_id = NULL
                WHERE code = :loc AND warehouse_id = (SELECT id FROM warehouse WHERE code = 'ICN01')
                """)
                .param("loc", locationCode)
                .update();
    }
}
