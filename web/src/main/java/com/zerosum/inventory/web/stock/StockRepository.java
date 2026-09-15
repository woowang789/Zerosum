package com.zerosum.inventory.web.stock;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 재고 현황 조회. {@code stock_balance}를 app_rw로 직접 읽는다 — {@code v_available_stock}(V2)은
 * {@code is_sellable}(= location_type = 'STORAGE')만 통과시키는 {@code ai_ro}용 뷰라 RECEIVING·
 * RETURN_HOLD·DAMAGED 같은, 작업자가 가장 먼저 봐야 할 재고가 사라진다. 그 뷰의 목적은 AI가 보는
 * 범위를 좁히는 것이지 사람이 보는 범위를 정하는 것이 아니므로 사람 화면은 뷰를 빌려 쓰지 않는다.
 * 가상 로케이션({@code is_virtual})은 잔액 행을 두지 않는 것이 불변식이라 정상적으로는 나올 수 없지만,
 * 혹시 나오더라도 정합 이슈로 잡힐 일이지 재고 현황에 섞일 것이 아니므로 조인에서 제외한다.
 */
@Repository
class StockRepository {

    public record AvailableStockRow(String warehouseCode, String skuCode, String skuName, String lotNo,
            LocalDate expiryDate, String locationCode, String locationType, int onHandQty, int allocatedQty,
            int availableQty, boolean inCount) {
    }

    private final JdbcClient jdbc;

    StockRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** skuCode가 널이면 창고 전체, 아니면 그 SKU만. SKU·로트·로케이션 순. */
    public List<AvailableStockRow> find(String warehouseCode, String skuCode, int limit) {
        return jdbc.sql("""
                SELECT w.code AS warehouse_code, s.code AS sku_code, s.name AS sku_name,
                       l.lot_no, l.expiry_date, loc.code AS location_code, loc.location_type,
                       b.on_hand_qty, b.allocated_qty, b.on_hand_qty - b.allocated_qty AS available_qty,
                       loc.count_session_id IS NOT NULL AS in_count
                FROM stock_balance b
                JOIN warehouse w ON w.id = b.warehouse_id
                JOIN location loc ON loc.id = b.location_id AND NOT loc.is_virtual
                JOIN sku s ON s.id = b.sku_id
                JOIN lot l ON l.id = b.lot_id
                WHERE w.code = :warehouseCode
                  AND (CAST(:skuCode AS TEXT) IS NULL OR s.code = CAST(:skuCode AS TEXT))
                ORDER BY s.code, l.lot_no, loc.code
                LIMIT :limit
                """)
                .param("warehouseCode", warehouseCode)
                .param("skuCode", skuCode)
                .param("limit", limit)
                .query(StockRepository::mapRow)
                .list();
    }

    private static AvailableStockRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new AvailableStockRow(rs.getString("warehouse_code"), rs.getString("sku_code"),
                rs.getString("sku_name"), rs.getString("lot_no"),
                rs.getObject("expiry_date", LocalDate.class), rs.getString("location_code"),
                rs.getString("location_type"), rs.getInt("on_hand_qty"), rs.getInt("allocated_qty"),
                rs.getInt("available_qty"), rs.getBoolean("in_count"));
    }
}
