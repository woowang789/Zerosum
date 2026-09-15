package com.zerosum.inventory.web.stock;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 원장 조회. {@link com.zerosum.inventory.web.issue.IssueRepository}와 같은 뷰(v_ledger, V4)를 app_rw로
 * 읽는다 — ai_ro가 아니라 app_rw로 읽는 이유도 같다(:web 규약, 사람 화면이 AI 전용 커넥션을 빌려 쓰지
 * 않는다). {@code AiQueryRepository}는 고치지 않는다.
 */
@Repository
class LedgerQueryRepository {

    public record LedgerRow(long ledgerEntryId, long txnId, String txnType, String sourceType, String sourceRef,
            String reasonCode, String actorType, String actorId, Long proposalId, Instant occurredAt,
            String locationCode, boolean isVirtual, String skuCode, String lotNo, int qtyDelta,
            Integer onHandAfter) {
    }

    private final JdbcClient jdbc;

    LedgerQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** sku·location·from·to는 널이면 그 조건을 걸지 않는다. 오래된 순. */
    public List<LedgerRow> find(String warehouseCode, String skuCode, String locationCode, Instant from, Instant to,
            int limit) {
        return jdbc.sql("""
                SELECT ledger_entry_id, txn_id, txn_type, source_type, source_ref, reason_code, actor_type,
                       actor_id, proposal_id, occurred_at, location_code, is_virtual, sku_code, lot_no, qty_delta,
                       on_hand_after
                FROM v_ledger
                WHERE warehouse_code = :warehouseCode
                  AND (CAST(:skuCode AS TEXT) IS NULL OR sku_code = CAST(:skuCode AS TEXT))
                  AND (CAST(:locationCode AS TEXT) IS NULL OR location_code = CAST(:locationCode AS TEXT))
                  AND (CAST(:from AS TIMESTAMPTZ) IS NULL OR occurred_at >= CAST(:from AS TIMESTAMPTZ))
                  AND (CAST(:to AS TIMESTAMPTZ) IS NULL OR occurred_at <= CAST(:to AS TIMESTAMPTZ))
                ORDER BY ledger_entry_id
                LIMIT :limit
                """)
                .param("warehouseCode", warehouseCode)
                .param("skuCode", skuCode)
                .param("locationCode", locationCode)
                .param("from", from == null ? null : Timestamp.from(from))
                .param("to", to == null ? null : Timestamp.from(to))
                .param("limit", limit)
                .query(LedgerQueryRepository::mapRow)
                .list();
    }

    private static LedgerRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new LedgerRow(rs.getLong("ledger_entry_id"), rs.getLong("txn_id"), rs.getString("txn_type"),
                rs.getString("source_type"), rs.getString("source_ref"), rs.getString("reason_code"),
                rs.getString("actor_type"), rs.getString("actor_id"), (Long) rs.getObject("proposal_id"),
                rs.getTimestamp("occurred_at").toInstant(), rs.getString("location_code"),
                rs.getBoolean("is_virtual"), rs.getString("sku_code"), rs.getString("lot_no"),
                rs.getInt("qty_delta"), (Integer) rs.getObject("on_hand_after"));
    }
}
