package com.zerosum.inventory.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.zerosum.inventory.domain.IssueException;
import com.zerosum.inventory.reconciliation.ReconciliationService;
import com.zerosum.inventory.repository.AiQueryRepository.LedgerRow;
import com.zerosum.inventory.repository.AiQueryRepository.OpenIssueRow;
import com.zerosum.inventory.support.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * AI 전용 커넥션(ai_ro·ai_proposer)의 DB 권한 경계. db/usecases/E2-ai-permissions.sql·
 * E3-proposer-permissions.sql과 같은 시나리오를 Java 통합 테스트로 재현한다 — 특히 ai_proposer가
 * status를 직접 바꾸는 우회로가 봉쇄돼 있는지가 핵심이다. 그 컬럼에 UPDATE 권한을 주지 않은 것 자체가
 * "ACKED는 사람이 인지했다"는 규칙을 앱 코드가 아니라 DB가 지키게 하는 지점이다(V4 마이그레이션).
 */
class AiPermissionBoundaryTest extends AbstractIntegrationTest {

    @Autowired
    private AiAnalysisService aiAnalysisService;

    @Autowired
    private AiQueryService aiQueryService;

    @Autowired
    private ReconciliationService reconciliationService;

    @Test
    void aiRoCannotReadStockBalanceDirectly() throws SQLException {
        assertPermissionDenied("ai_ro", "SELECT count(*) FROM stock_balance");
        assertReconciliationClean();
    }

    @Test
    void aiRoCannotReadInventoryIssueDirectly() throws SQLException {
        assertPermissionDenied("ai_ro", "SELECT count(*) FROM inventory_issue");
        assertReconciliationClean();
    }

    @Test
    void aiRoCanReadAllFourViews() throws SQLException {
        try (Connection con = aiConnection("ai_ro"); Statement st = con.createStatement()) {
            st.execute("SELECT count(*) FROM v_available_stock");
            st.execute("SELECT count(*) FROM v_ledger");
            st.execute("SELECT count(*) FROM v_open_issue");
            st.execute("SELECT count(*) FROM v_count_history");
        }
        assertReconciliationClean();
    }

    @Test
    void aiProposerCannotChangeStatusToAcked() throws SQLException {
        assertPermissionDenied("ai_proposer",
                "UPDATE inventory_issue SET status = 'ACKED' WHERE id = (SELECT max(id) FROM inventory_issue)");
        assertReconciliationClean();
    }

    @Test
    void aiProposerCannotReadAckedBy() throws SQLException {
        assertPermissionDenied("ai_proposer", "SELECT acked_by FROM inventory_issue");
        assertReconciliationClean();
    }

    @Test
    void aiProposerCanUpdateOnlyAiAnalysis() throws SQLException {
        try (Connection con = aiConnection("ai_proposer"); Statement st = con.createStatement()) {
            st.execute("UPDATE inventory_issue SET ai_analysis = '{}'::JSONB "
                    + "WHERE id = (SELECT max(id) FROM inventory_issue)");
        }
        assertReconciliationClean();
    }

    @Test
    void writingAnalysisToResolvedIssueThrows() {
        postAndExpectSuccess(request("receipt:AI-BOUNDARY-0001:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -10),
                line("ICN01", "B-01-01-1", "SKU-100001", "DEFAULT", 10)));
        corruptOnHandQty("B-01-01-1", "SKU-100001", 3);
        assertThat(reconciliationService.runOnce()).isEqualTo(1);

        long issueId = jdbcClient.sql("SELECT id FROM inventory_issue WHERE issue_type = 'PROJECTION_MISMATCH'")
                .query(Long.class)
                .single();
        reconciliationRepository.resolve(issueId, "user:test", null, "테스트로 종결");

        assertThatThrownBy(() -> aiAnalysisService.writeIssueAnalysis(issueId, "{}"))
                .isInstanceOf(IssueException.class);

        corruptOnHandQty("B-01-01-1", "SKU-100001", -3); // 원복
        assertReconciliationClean();
    }

    // ── 창고 권한 누수 방지 (AiQueryService의 조회 도구 4개) ──────────────────────────
    // location은 UNIQUE(warehouse_id, code)라 같은 로케이션 코드가 창고마다 존재한다(db/03-seed.sql).
    // 아래 세 테스트는 같은 로케이션 코드를 ICN01·YIT01 양쪽에 채워 넣어 코드만으로는 창고가
    // 갈리지 않음을 증명한다.

    @Test
    void ledgerIsScopedToCallerWarehouse() {
        postAndExpectSuccess(request("receipt:AI-BOUNDARY-LEDGER-ICN:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -10),
                line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", 10)));
        postAndExpectSuccess(request("receipt:AI-BOUNDARY-LEDGER-YIT:1", "RECEIPT", null, null,
                line("YIT01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -10),
                line("YIT01", "A-01-01-1", "SKU-100001", "DEFAULT", 10)));

        List<LedgerRow> ledger = aiQueryService.getLedger("ICN01", "SKU-100001", "A-01-01-1", null, null);

        assertThat(ledger).isNotEmpty();
        assertThat(ledger).extracting(LedgerRow::warehouseCode)
                .as("같은 로케이션 코드(A-01-01-1)를 쓰는 YIT01 원장이 하나도 섞이지 않는다")
                .containsOnly("ICN01");
    }

    @Test
    void openIssueListIsScopedToCallerWarehouse() {
        postAndExpectSuccess(request("receipt:AI-BOUNDARY-ISSUE-ICN:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -10),
                line("ICN01", "B-01-01-1", "SKU-100001", "DEFAULT", 10)));
        postAndExpectSuccess(request("receipt:AI-BOUNDARY-ISSUE-YIT:1", "RECEIPT", null, null,
                line("YIT01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -10),
                line("YIT01", "B-01-01-1", "SKU-100001", "DEFAULT", 10)));
        corruptOnHandQty("ICN01", "B-01-01-1", "SKU-100001", 3);
        corruptOnHandQty("YIT01", "B-01-01-1", "SKU-100001", 3);
        assertThat(reconciliationService.runOnce()).isEqualTo(2);

        Ids icnIds = resolveIds("ICN01", "B-01-01-1", "SKU-100001", "DEFAULT");
        Ids yitIds = resolveIds("YIT01", "B-01-01-1", "SKU-100001", "DEFAULT");

        List<OpenIssueRow> icnIssues = aiQueryService.listOpenIssues("ICN01");
        assertThat(icnIssues).extracting(OpenIssueRow::locationId)
                .as("ICN01로 부르면 ICN01 이슈만 나오고 YIT01 이슈는 섞이지 않는다")
                .contains(icnIds.locationId())
                .doesNotContain(yitIds.locationId());

        corruptOnHandQty("ICN01", "B-01-01-1", "SKU-100001", -3); // 원복
        corruptOnHandQty("YIT01", "B-01-01-1", "SKU-100001", -3);
        assertReconciliationClean();
    }

    @Test
    void issueContextRejectsIssueFromAnotherWarehouse() {
        postAndExpectSuccess(request("receipt:AI-BOUNDARY-CTX-YIT:1", "RECEIPT", null, null,
                line("YIT01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -10),
                line("YIT01", "B-01-01-1", "SKU-100001", "DEFAULT", 10)));
        corruptOnHandQty("YIT01", "B-01-01-1", "SKU-100001", 3);
        assertThat(reconciliationService.runOnce()).isEqualTo(1);

        long yitIssueId = jdbcClient.sql("SELECT id FROM inventory_issue WHERE issue_type = 'PROJECTION_MISMATCH'")
                .query(Long.class)
                .single();

        assertThatThrownBy(() -> aiQueryService.getIssueContext("ICN01", yitIssueId))
                .as("YIT01 이슈 id를 ICN01 권한으로 넘기면 거부한다")
                .isInstanceOf(IssueException.class);

        corruptOnHandQty("YIT01", "B-01-01-1", "SKU-100001", -3); // 원복
        assertReconciliationClean();
    }

    private void assertPermissionDenied(String role, String sql) throws SQLException {
        try (Connection con = aiConnection(role); Statement st = con.createStatement()) {
            SQLException ex = assertThrows(SQLException.class, () -> st.execute(sql));
            assertThat(ex.getSQLState()).isEqualTo("42501");
        }
    }

    private static Connection aiConnection(String role) throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), role, role);
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

    /** 위 3인자 버전과 같지만 창고를 고정하지 않는다 — 두 창고 모두에 이슈를 만들어야 하는 테스트용. */
    private void corruptOnHandQty(String warehouseCode, String locationCode, String skuCode, int delta) {
        jdbcClient.sql("""
                UPDATE stock_balance SET on_hand_qty = on_hand_qty + :delta
                WHERE location_id = (SELECT id FROM location WHERE code = :loc
                                      AND warehouse_id = (SELECT id FROM warehouse WHERE code = :wh))
                  AND sku_id = (SELECT id FROM sku WHERE code = :sku)
                """)
                .param("delta", delta)
                .param("loc", locationCode)
                .param("wh", warehouseCode)
                .param("sku", skuCode)
                .update();
    }
}
