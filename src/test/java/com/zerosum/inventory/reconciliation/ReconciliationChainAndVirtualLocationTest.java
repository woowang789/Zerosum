package com.zerosum.inventory.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.zerosum.inventory.support.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 정합 검증 배치 ③(원장 체인)·④(가상 로케이션 잔액) — db/usecases/F-reconciliation.sql UC-F04·UC-F05와
 * 같은 시나리오를 Java 배치(ReconciliationService)로 재현한다.
 */
class ReconciliationChainAndVirtualLocationTest extends AbstractIntegrationTest {

    @Autowired
    private ReconciliationService reconciliationService;

    @Test
    void chainBreakPinpointsTheCorruptedLedgerRowAndItsTransaction() throws SQLException {
        long txnId = postAndExpectSuccess(request("receipt:RECON-F04-0001:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -40),
                line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", 40)));

        Ids ids = resolveIds("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT");
        long firstLedgerEntryId = jdbcClient.sql("""
                SELECT min(id) FROM inventory_ledger_entry WHERE location_id = :loc AND on_hand_after IS NOT NULL
                """)
                .param("loc", ids.locationId())
                .query(Long.class)
                .single();

        // UC-F04a와 동일: 원장의 잔량 기록을 하나 조작한다. app_rw(jdbcClient)에는 원장 UPDATE 권한이
        // 없으므로(I4의 DB 강제, V1__init.sql) migrator 연결로 직접 조작해야 한다 — F-reconciliation.sql이
        // migrator로 실행되는 것과 같은 이유다.
        corruptLedgerOnHandAfter(firstLedgerEntryId, 5);

        assertThat(reconciliationService.runOnce()).isEqualTo(1);
        assertThat(countIssues("CHAIN_BREAK")).isEqualTo(1);

        record ChainDetail(long ledgerEntryId, long txnId) {
        }
        ChainDetail detail = jdbcClient.sql("""
                SELECT (detail ->> 'ledgerEntryId')::BIGINT AS ledger_entry_id, (detail ->> 'txnId')::BIGINT AS txn_id
                FROM inventory_issue WHERE issue_type = 'CHAIN_BREAK'
                """)
                .query((rs, rowNum) -> new ChainDetail(rs.getLong("ledger_entry_id"), rs.getLong("txn_id")))
                .single();
        assertThat(detail.ledgerEntryId()).as("조작한 그 원장 줄을 정확히 가리킨다 (UC-F04c)").isEqualTo(firstLedgerEntryId);
        assertThat(detail.txnId()).isEqualTo(txnId);

        // 자동 보정하지 않는다 — 원장은 조작한 값 그대로다
        int onHandAfter = jdbcClient.sql("SELECT on_hand_after FROM inventory_ledger_entry WHERE id = :id")
                .param("id", firstLedgerEntryId)
                .query(Integer.class)
                .single();
        assertThat(onHandAfter).as("배치는 보정하지 않는다 (원래 40 + 5)").isEqualTo(45);

        // 다른 체크는 오염되지 않는다 — qty_delta는 그대로라 잔액 투영(①)은 여전히 맞는다
        assertThat(countIssues("PROJECTION_MISMATCH")).isZero();

        corruptLedgerOnHandAfter(firstLedgerEntryId, -5); // 원복
    }

    private void corruptLedgerOnHandAfter(long ledgerEntryId, int delta) throws SQLException {
        try (Connection migrator = migratorConnection();
                PreparedStatement ps = migrator.prepareStatement(
                        "UPDATE inventory_ledger_entry SET on_hand_after = on_hand_after + ? WHERE id = ?")) {
            ps.setInt(1, delta);
            ps.setLong(2, ledgerEntryId);
            ps.executeUpdate();
        }
    }

    @Test
    void virtualLocationBalanceRowIsDetectedWithoutAutoCorrection() {
        // UC-F05a와 동일: 공급사 가상 로케이션에 잔액 행을 만든다 (정상 코드라면 생길 수 없는 버그 시뮬레이션)
        jdbcClient.sql("""
                INSERT INTO stock_balance (warehouse_id, location_id, sku_id, lot_id)
                SELECT w.id, l.id, s.id, lo.id
                FROM warehouse w, location l, sku s, lot lo
                WHERE w.code = 'ICN01' AND l.warehouse_id = w.id AND l.code = 'V-SUPPLIER'
                  AND s.code = 'SKU-100001' AND lo.sku_id = s.id AND lo.lot_no = 'DEFAULT'
                """)
                .update();

        assertThat(reconciliationService.runOnce()).isEqualTo(1);
        assertThat(countIssues("VIRTUAL_LOCATION_BALANCE")).isEqualTo(1);

        // 자동 보정하지 않는다 — 문제의 잔액 행이 그대로 남아 있다
        int stillThere = jdbcClient.sql("""
                SELECT count(*) FROM stock_balance b JOIN location l ON l.id = b.location_id WHERE l.is_virtual
                """)
                .query(Integer.class)
                .single();
        assertThat(stillThere).isEqualTo(1);

        jdbcClient.sql("""
                DELETE FROM stock_balance WHERE location_id = (SELECT id FROM location WHERE code = 'V-SUPPLIER'
                    AND warehouse_id = (SELECT id FROM warehouse WHERE code = 'ICN01'))
                """)
                .update(); // 원복
    }

    private int countIssues(String issueType) {
        return jdbcClient.sql("SELECT count(*) FROM inventory_issue WHERE issue_type = :type")
                .param("type", issueType)
                .query(Integer.class)
                .single();
    }
}
