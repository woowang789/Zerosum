package com.zerosum.inventory.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zerosum.inventory.domain.PostingException;
import com.zerosum.inventory.support.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * DB가 최후 방어선으로 동작하는지 확인한다. 애플리케이션 검사를 우회한 상황을 흉내 내
 * db/usecases/D-invariants.sql의 UC-D01·D04b·D05를 재현한다.
 */
class DatabaseInvariantTest extends AbstractIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void insufficientAvailableStockIsRejectedByApplication() {
        putawayTshirts120();

        assertThatThrownBy(() -> postingGateway.post(
                request("move:OVER-0001", "MOVE", null, null,
                        line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", -999),
                        line("ICN01", "A-01-01-2", "SKU-100001", "DEFAULT", 999)),
                Preconditions.none()))
                .isInstanceOf(PostingException.class)
                .extracting(ex -> ((PostingException) ex).code())
                .isEqualTo("INSUFFICIENT_STOCK");
    }

    @Test
    void negativeOnHandQtyIsRejectedByCheckConstraintAsLastResort() {
        putawayTshirts120();
        Ids ids = resolveIds("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT");

        // 애플리케이션 검증을 건너뛰고 잔액을 직접 음수로 UPDATE — I1 CHECK가 최후 방어선이다
        assertThatThrownBy(() -> jdbcClient.sql("""
                UPDATE stock_balance SET on_hand_qty = -1
                WHERE location_id = :loc AND sku_id = :sku AND lot_id = :lot
                """)
                .param("loc", ids.locationId())
                .param("sku", ids.skuId())
                .param("lot", ids.lotId())
                .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unbalancedLedgerInsertIsRejectedByDeferredConstraintTriggerAtCommit() throws SQLException {
        long txnId = postAndExpectSuccess(request("receipt:PO-TRIGGER-0001:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -10),
                line("ICN01", "RCV-01", "SKU-100001", "DEFAULT", 10)));
        Ids ids = resolveIds("ICN01", "RCV-01", "SKU-100001", "DEFAULT");

        // Spring 테스트 트랜잭션 롤백에 기대지 않고 직접 커밋해야 지연 제약(DEFERRABLE INITIALLY DEFERRED)이
        // 실제로 커밋 시점에 걸리는 것을 볼 수 있다 (db/usecases/D-invariants.sql UC-D04b).
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO inventory_ledger_entry
                        (txn_id, warehouse_id, location_id, sku_id, lot_id, qty_delta, on_hand_after)
                    VALUES (?, ?, ?, ?, ?, ?, NULL)
                    """)) {
                ps.setLong(1, txnId);
                ps.setLong(2, ids.warehouseId());
                ps.setLong(3, ids.locationId());
                ps.setLong(4, ids.skuId());
                ps.setLong(5, ids.lotId());
                ps.setInt(6, 7); // 기존 거래에 한쪽만 추가해 (SKU, 로트) 합계를 깬다
                ps.executeUpdate();
            }

            assertThatThrownBy(connection::commit)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("is not zero-sum");
        }
    }

    @Test
    void appRwCannotUpdateOrDeleteLedgerEntries() {
        long txnId = postAndExpectSuccess(request("receipt:PO-PERM-0001:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -10),
                line("ICN01", "RCV-01", "SKU-100001", "DEFAULT", 10)));

        assertThatThrownBy(() -> jdbcClient.sql("UPDATE inventory_ledger_entry SET qty_delta = 999 WHERE txn_id = :id")
                .param("id", txnId)
                .update())
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbcClient.sql("DELETE FROM inventory_ledger_entry WHERE txn_id = :id")
                .param("id", txnId)
                .update())
                .isInstanceOf(DataAccessException.class);

        // 거부됐을 뿐 실제로는 아무것도 바뀌지 않았어야 한다
        Integer qtyDelta = jdbcClient.sql("SELECT qty_delta FROM inventory_ledger_entry WHERE txn_id = :id ORDER BY id LIMIT 1")
                .param("id", txnId)
                .query(Integer.class)
                .single();
        assertThat(qtyDelta).isEqualTo(-10);
    }

    private void putawayTshirts120() {
        postAndExpectSuccess(request("receipt:PO-SEED-0002:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -120),
                line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", 120)));
    }
}
