package com.zerosum.inventory.web.issue;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * app_rw(primary) 전용 이슈 조회 저장소. {@code AiQueryRepository}와 같은 뷰(v_open_issue·v_ledger·
 * v_count_history, V4)를 읽지만 ai_ro가 아니라 app_rw로 읽는다 — 사람 화면이 AI 전용 커넥션을 빌려 쓰면
 * 감사와 권한이 뒤섞이므로(:web 규약), 같은 목적의 쿼리를 이 리포지토리가 따로 갖는다.
 * {@code AiQueryRepository}는 고치지 않는다.
 */
@Repository
public class IssueRepository {

    public record OpenIssueRow(long issueId, String issueType, String severity, String status, Long locationId,
            String locationCode, String warehouseCode, Long skuId, String skuCode, Long lotId, String lotNo,
            Long countSessionId, String detail, String aiAnalysis, Instant detectedAt, Instant ackedAt) {
    }

    public record LedgerRow(long ledgerEntryId, long txnId, String txnType, String reasonCode, String actorType,
            String actorId, Long proposalId, Instant occurredAt, String locationCode, String skuCode, String lotNo,
            int qtyDelta, Integer onHandAfter) {
    }

    public record CountHistoryRow(long countSessionId, String sessionStatus, String locationCode, String skuCode,
            String lotNo, int systemQty, int countedQty, int diffQty, String countedBy, Instant countedAt) {
    }

    // v_open_issue를 읽는 listOpen()·findOpen()이 함께 쓰는 SELECT 목록 (OpenIssueRow 필드와 1:1).
    private static final String OPEN_ISSUE_COLUMNS = """
            issue_id, issue_type, severity, status, location_id, location_code, warehouse_code,
            sku_id, sku_code, lot_id, lot_no, count_session_id,
            detail::TEXT AS detail, ai_analysis::TEXT AS ai_analysis, detected_at, acked_at
            """;

    private final JdbcClient jdbc;

    IssueRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 봐야 할 이슈(v_open_issue의 WHERE가 이미 OPEN·ACKED로 좁혀 둔다). 오래된 감지 순. */
    public List<OpenIssueRow> listOpen(String warehouseCode, int limit) {
        return jdbc.sql("SELECT " + OPEN_ISSUE_COLUMNS + """
                FROM v_open_issue
                WHERE warehouse_code = :warehouseCode
                ORDER BY detected_at
                LIMIT :limit
                """)
                .param("warehouseCode", warehouseCode)
                .param("limit", limit)
                .query(IssueRepository::mapOpenIssueRow)
                .list();
    }

    /**
     * 이슈 하나 — 창고로 미리 거르지 않는다. {@code AiQueryRepository#issue}와 달리 이 화면(사람)은
     * 존재 자체를 숨길 이유가 없다: 다른 창고 이슈면 호출자가 창고 검증(AccessGuard)에서 403으로 안다.
     */
    public Optional<OpenIssueRow> findOpen(long issueId) {
        return jdbc.sql("SELECT " + OPEN_ISSUE_COLUMNS + " FROM v_open_issue WHERE issue_id = :id")
                .param("id", issueId)
                .query(IssueRepository::mapOpenIssueRow)
                .optional();
    }

    /**
     * 원인 분석 컨텍스트용 원장 — 같은 파티션(로케이션·SKU·로트)의 이력 전부. {@code AiQueryService
     * #getIssueContext}와 달리 앵커·윈도우로 건수를 제한하지 않는다: LLM 컨텍스트 창과 달리 사람 화면은
     * 파티션 이력을 전부 보여줘도 된다(최소 구현 — 화면이 필요해지면 그때 상한을 둔다).
     */
    public List<LedgerRow> ledgerFor(Long locationId, Long skuId, Long lotId) {
        return jdbc.sql("""
                SELECT ledger_entry_id, txn_id, txn_type, reason_code, actor_type, actor_id, proposal_id,
                       occurred_at, location_code, sku_code, lot_no, qty_delta, on_hand_after
                FROM v_ledger
                WHERE location_id IS NOT DISTINCT FROM CAST(:locationId AS BIGINT)
                  AND sku_id IS NOT DISTINCT FROM CAST(:skuId AS BIGINT)
                  AND lot_id IS NOT DISTINCT FROM CAST(:lotId AS BIGINT)
                ORDER BY ledger_entry_id
                """)
                .param("locationId", locationId)
                .param("skuId", skuId)
                .param("lotId", lotId)
                .query(IssueRepository::mapLedgerRow)
                .list();
    }

    /** 같은 파티션의 실사 이력. 오래된 순. */
    public List<CountHistoryRow> countHistoryFor(Long locationId, Long skuId, Long lotId) {
        return jdbc.sql("""
                SELECT count_session_id, session_status, location_code, sku_code, lot_no, system_qty, counted_qty,
                       diff_qty, counted_by, counted_at
                FROM v_count_history
                WHERE location_id IS NOT DISTINCT FROM CAST(:locationId AS BIGINT)
                  AND sku_id IS NOT DISTINCT FROM CAST(:skuId AS BIGINT)
                  AND lot_id IS NOT DISTINCT FROM CAST(:lotId AS BIGINT)
                ORDER BY counted_at
                """)
                .param("locationId", locationId)
                .param("skuId", skuId)
                .param("lotId", lotId)
                .query(IssueRepository::mapCountHistoryRow)
                .list();
    }

    private static OpenIssueRow mapOpenIssueRow(ResultSet rs, int rowNum) throws SQLException {
        return new OpenIssueRow(rs.getLong("issue_id"), rs.getString("issue_type"), rs.getString("severity"),
                rs.getString("status"), (Long) rs.getObject("location_id"), rs.getString("location_code"),
                rs.getString("warehouse_code"), (Long) rs.getObject("sku_id"), rs.getString("sku_code"),
                (Long) rs.getObject("lot_id"), rs.getString("lot_no"), (Long) rs.getObject("count_session_id"),
                rs.getString("detail"), rs.getString("ai_analysis"), rs.getTimestamp("detected_at").toInstant(),
                rs.getTimestamp("acked_at") == null ? null : rs.getTimestamp("acked_at").toInstant());
    }

    private static LedgerRow mapLedgerRow(ResultSet rs, int rowNum) throws SQLException {
        return new LedgerRow(rs.getLong("ledger_entry_id"), rs.getLong("txn_id"), rs.getString("txn_type"),
                rs.getString("reason_code"), rs.getString("actor_type"), rs.getString("actor_id"),
                (Long) rs.getObject("proposal_id"), rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("location_code"), rs.getString("sku_code"), rs.getString("lot_no"),
                rs.getInt("qty_delta"), (Integer) rs.getObject("on_hand_after"));
    }

    private static CountHistoryRow mapCountHistoryRow(ResultSet rs, int rowNum) throws SQLException {
        return new CountHistoryRow(rs.getLong("count_session_id"), rs.getString("session_status"),
                rs.getString("location_code"), rs.getString("sku_code"), rs.getString("lot_no"),
                rs.getInt("system_qty"), rs.getInt("counted_qty"), rs.getInt("diff_qty"), rs.getString("counted_by"),
                rs.getTimestamp("counted_at").toInstant());
    }
}
