package com.zerosum.inventory.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * AI 조회 전용 저장소. {@code aiReadJdbcClient}(ai_ro)만 주입받는다 — V4 마이그레이션이 ai_ro에 준 뷰 4개
 * (v_available_stock·v_ledger·v_open_issue·v_count_history) 말고는 아무것도 읽을 수 없다. 행 타입은 각
 * 뷰의 컬럼과 1:1인 중첩 record다.
 *
 * <p>v_available_stock(V2)은 warehouse_code를 노출하지 않고 warehouse_id만 준다 — ai_proposer 쪽
 * v_warehouse_sku_basis(V4)와 달리 이 뷰는 만들 때 창고 코드 조인을 넣지 않았다. ai_ro는 warehouse
 * 테이블에 권한이 없어 이 안에서 코드→id를 직접 바꿀 수 없으므로, 같은 ai_ro 권한으로 읽을 수 있는
 * v_ledger의 (warehouse_id, warehouse_code) 조합을 다리로 써서 창고 코드 필터를 건다. 잔액이 있는
 * 창고는 반드시 그 창고로 기록된 원장이 최소 한 줄 있다(포스팅이 잔액과 원장을 같은 트랜잭션에
 * 쓴다) — 그렇지 않다면 v_available_stock 쪽에도 애초에 행이 없으므로 이 다리는 항상 정확하다.
 */
@Repository
public class AiQueryRepository {

    public record AvailableStockRow(long warehouseId, String skuCode, String lotNo, LocalDate expiryDate,
            String locationCode, int onHandQty, int allocatedQty, int availableQty, boolean inCount) {
    }

    public record LedgerRow(long ledgerEntryId, long txnId, String txnType, String sourceType, String sourceRef,
            String reasonCode, String actorType, String actorId, Long proposalId, Instant occurredAt,
            String warehouseCode, String locationCode, String skuCode, String lotNo, int qtyDelta,
            Integer onHandAfter) {
    }

    public record OpenIssueRow(long issueId, String issueType, String severity, String status, Long locationId,
            String locationCode, Long skuId, String skuCode, Long lotId, String lotNo, Long countSessionId,
            String detailJson, String aiAnalysisJson, Instant detectedAt) {
    }

    public record CountHistoryRow(long countSessionId, String sessionStatus, String locationCode, String skuCode,
            String lotNo, int systemQty, int countedQty, int diffQty, String countedBy, Instant countedAt) {
    }

    // v_ledger를 읽는 ledger()·ledgerAround()가 함께 쓰는 SELECT 목록 (LedgerRow 필드와 1:1).
    private static final String LEDGER_COLUMNS = """
            ledger_entry_id, txn_id, txn_type, source_type, source_ref, reason_code, actor_type, actor_id,
            proposal_id, occurred_at, warehouse_code, location_code, sku_code, lot_no, qty_delta, on_hand_after
            """;

    // v_open_issue를 읽는 openIssues()·issue()가 함께 쓰는 SELECT 목록 (OpenIssueRow 필드와 1:1).
    // detail·ai_analysis는 JSONB라 ::TEXT로 캐스트해 문자열로 받는다 (ProposalRepository의 basis_snapshot과 같은 관례).
    private static final String OPEN_ISSUE_COLUMNS = """
            issue_id, issue_type, severity, status, location_id, location_code, sku_id, sku_code, lot_id, lot_no,
            count_session_id, detail::TEXT AS detail, ai_analysis::TEXT AS ai_analysis, detected_at
            """;

    private final JdbcClient jdbc;

    AiQueryRepository(@Qualifier("aiReadJdbcClient") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 가용 재고. skuCode가 null이면 그 창고 전체를 본다. */
    public List<AvailableStockRow> availableStock(String warehouseCode, String skuCode, int limit) {
        return jdbc.sql("""
                SELECT warehouse_id, sku_code, lot_no, expiry_date, location_code, on_hand_qty, allocated_qty,
                       available_qty, in_count
                FROM v_available_stock
                WHERE warehouse_id IN (SELECT warehouse_id FROM v_ledger WHERE warehouse_code = :warehouseCode)
                  AND (CAST(:skuCode AS TEXT) IS NULL OR sku_code = CAST(:skuCode AS TEXT))
                ORDER BY location_code, lot_no
                LIMIT :limit
                """)
                .param("warehouseCode", warehouseCode)
                .param("skuCode", skuCode)
                .param("limit", limit)
                .query(AiQueryRepository::mapAvailableStockRow)
                .list();
    }

    /**
     * 원장. warehouseCode는 필수 — location UNIQUE(warehouse_id, code)라 로케이션 코드만으로는 창고를
     * 가릴 수 없다. skuCode·locationCode·from·to 중 null인 조건은 걸지 않는다.
     */
    public List<LedgerRow> ledger(String warehouseCode, String skuCode, String locationCode, Instant from,
            Instant to, int limit) {
        return jdbc.sql("SELECT " + LEDGER_COLUMNS + """
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
                .query(AiQueryRepository::mapLedgerRow)
                .list();
    }

    /**
     * 봐야 할 이슈 (v_open_issue의 WHERE가 이미 OPEN·ACKED로 좁혀 둔다). warehouseCode로 반드시 좁힌다.
     * 오래된 감지 순.
     */
    public List<OpenIssueRow> openIssues(String warehouseCode, int limit) {
        return jdbc.sql("SELECT " + OPEN_ISSUE_COLUMNS + """
                FROM v_open_issue
                WHERE warehouse_code = :warehouseCode
                ORDER BY detected_at
                LIMIT :limit
                """)
                .param("warehouseCode", warehouseCode)
                .param("limit", limit)
                .query(AiQueryRepository::mapOpenIssueRow)
                .list();
    }

    /**
     * 이슈 하나. warehouseCode가 다르면(다른 창고 이슈) 빈 Optional이다 — 서비스가 이를 "없다"와 똑같이
     * 예외로 거부한다. RESOLVED면 v_open_issue 자체가 걸러내므로 마찬가지로 빈 Optional이다.
     */
    public Optional<OpenIssueRow> issue(String warehouseCode, long issueId) {
        return jdbc.sql("SELECT " + OPEN_ISSUE_COLUMNS + """
                FROM v_open_issue WHERE issue_id = :issueId AND warehouse_code = :warehouseCode
                """)
                .param("issueId", issueId)
                .param("warehouseCode", warehouseCode)
                .query(AiQueryRepository::mapOpenIssueRow)
                .optional();
    }

    /**
     * 같은 파티션(location_id·sku_id·lot_id)에서 앵커 id 기준 앞뒤 최대 window건씩. 앵커 자신은 "이전"
     * 쪽(id ≤ anchor)에 포함된다.
     */
    public List<LedgerRow> ledgerAround(Long locationId, Long skuId, Long lotId, long anchorLedgerEntryId,
            int window) {
        return jdbc.sql(("""
                SELECT * FROM (
                  (SELECT %s
                   FROM v_ledger
                   WHERE location_id IS NOT DISTINCT FROM CAST(:locationId AS BIGINT)
                     AND sku_id IS NOT DISTINCT FROM CAST(:skuId AS BIGINT)
                     AND lot_id IS NOT DISTINCT FROM CAST(:lotId AS BIGINT)
                     AND ledger_entry_id <= :anchor
                   ORDER BY ledger_entry_id DESC LIMIT :window)
                  UNION ALL
                  (SELECT %s
                   FROM v_ledger
                   WHERE location_id IS NOT DISTINCT FROM CAST(:locationId AS BIGINT)
                     AND sku_id IS NOT DISTINCT FROM CAST(:skuId AS BIGINT)
                     AND lot_id IS NOT DISTINCT FROM CAST(:lotId AS BIGINT)
                     AND ledger_entry_id > :anchor
                   ORDER BY ledger_entry_id ASC LIMIT :window)
                ) combined
                ORDER BY ledger_entry_id
                """).formatted(LEDGER_COLUMNS, LEDGER_COLUMNS))
                .param("locationId", locationId)
                .param("skuId", skuId)
                .param("lotId", lotId)
                .param("anchor", anchorLedgerEntryId)
                .param("window", window)
                .query(AiQueryRepository::mapLedgerRow)
                .list();
    }

    /**
     * 이슈 컨텍스트의 앵커 원장 id: {@code COALESCE(그 이슈의 detail.ledgerEntryId, 파티션 최신 원장 id, 0)}.
     * CHAIN_BREAK처럼 detail에 ledgerEntryId가 있는 타입은 그 값(최초 깨짐 지점)이 그대로 앵커가 되고,
     * 나머지 타입은 파티션의 마지막 원장 줄이 앵커가 된다. JSON 추출(->>)을 SQL에서 끝내 AiQueryService가
     * detail 텍스트에 정규식을 걸 필요가 없게 한다 — OpenIssueRow.detailJson에는 기대지 않고 issueId로
     * 다시 조회한다.
     */
    public long anchorLedgerEntryId(long issueId, Long locationId, Long skuId, Long lotId) {
        return jdbc.sql("""
                SELECT COALESCE(
                         (SELECT (detail ->> 'ledgerEntryId')::BIGINT FROM v_open_issue WHERE issue_id = :issueId),
                         (SELECT max(ledger_entry_id) FROM v_ledger
                          WHERE location_id IS NOT DISTINCT FROM CAST(:locationId AS BIGINT)
                            AND sku_id IS NOT DISTINCT FROM CAST(:skuId AS BIGINT)
                            AND lot_id IS NOT DISTINCT FROM CAST(:lotId AS BIGINT)),
                         0)
                """)
                .param("issueId", issueId)
                .param("locationId", locationId)
                .param("skuId", skuId)
                .param("lotId", lotId)
                .query(Long.class)
                .single();
    }

    /** 같은 파티션의 실사 이력. 오래된 순. */
    public List<CountHistoryRow> countHistory(Long locationId, Long skuId, Long lotId) {
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
                .query(AiQueryRepository::mapCountHistoryRow)
                .list();
    }

    private static AvailableStockRow mapAvailableStockRow(ResultSet rs, int rowNum) throws SQLException {
        return new AvailableStockRow(rs.getLong("warehouse_id"), rs.getString("sku_code"), rs.getString("lot_no"),
                rs.getObject("expiry_date", LocalDate.class), rs.getString("location_code"), rs.getInt("on_hand_qty"),
                rs.getInt("allocated_qty"), rs.getInt("available_qty"), rs.getBoolean("in_count"));
    }

    private static LedgerRow mapLedgerRow(ResultSet rs, int rowNum) throws SQLException {
        return new LedgerRow(rs.getLong("ledger_entry_id"), rs.getLong("txn_id"), rs.getString("txn_type"),
                rs.getString("source_type"), rs.getString("source_ref"), rs.getString("reason_code"),
                rs.getString("actor_type"), rs.getString("actor_id"), (Long) rs.getObject("proposal_id"),
                rs.getTimestamp("occurred_at").toInstant(), rs.getString("warehouse_code"),
                rs.getString("location_code"), rs.getString("sku_code"), rs.getString("lot_no"),
                rs.getInt("qty_delta"), (Integer) rs.getObject("on_hand_after"));
    }

    private static OpenIssueRow mapOpenIssueRow(ResultSet rs, int rowNum) throws SQLException {
        return new OpenIssueRow(rs.getLong("issue_id"), rs.getString("issue_type"), rs.getString("severity"),
                rs.getString("status"), (Long) rs.getObject("location_id"), rs.getString("location_code"),
                (Long) rs.getObject("sku_id"), rs.getString("sku_code"), (Long) rs.getObject("lot_id"),
                rs.getString("lot_no"), (Long) rs.getObject("count_session_id"), rs.getString("detail"),
                rs.getString("ai_analysis"), rs.getTimestamp("detected_at").toInstant());
    }

    private static CountHistoryRow mapCountHistoryRow(ResultSet rs, int rowNum) throws SQLException {
        return new CountHistoryRow(rs.getLong("count_session_id"), rs.getString("session_status"),
                rs.getString("location_code"), rs.getString("sku_code"), rs.getString("lot_no"),
                rs.getInt("system_qty"), rs.getInt("counted_qty"), rs.getInt("diff_qty"), rs.getString("counted_by"),
                rs.getTimestamp("counted_at").toInstant());
    }
}
