package com.zerosum.inventory.repository;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code count_result}와, 실사에서 비롯된 {@code inventory_issue}(COUNT_VARIANCE) 접근.
 * db/04-harness.sql tst_count_submit·tst_count_resolve의 count_result/inventory_issue 부분과 대응한다.
 */
@Repository
public class CountResultRepository {

    private final JdbcClient jdbc;

    CountResultRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record SystemBalanceLine(long skuId, long lotId, int onHandQty) {
    }

    /** 실사 대상 로케이션의 현재 잔액. 표시가 커밋된 뒤라 이 값은 실사 시작 시점과 같다 (05-count-session.md). */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<SystemBalanceLine> systemBalances(long locationId) {
        return jdbc.sql("SELECT sku_id, lot_id, on_hand_qty FROM stock_balance WHERE location_id = :locationId")
                .param("locationId", locationId)
                .query((rs, rowNum) -> new SystemBalanceLine(
                        rs.getLong("sku_id"), rs.getLong("lot_id"), rs.getInt("on_hand_qty")))
                .list();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insertResult(long sessionId, long locationId, long skuId, long lotId, int systemQty, int countedQty,
            String countedBy) {
        jdbc.sql("""
                INSERT INTO count_result
                    (count_session_id, location_id, sku_id, lot_id, system_qty, counted_qty, counted_by, counted_at)
                VALUES (:sessionId, :locationId, :skuId, :lotId, :systemQty, :countedQty, :countedBy, now())
                """)
                .param("sessionId", sessionId)
                .param("locationId", locationId)
                .param("skuId", skuId)
                .param("lotId", lotId)
                .param("systemQty", systemQty)
                .param("countedQty", countedQty)
                .param("countedBy", countedBy)
                .update();
    }

    /**
     * 세션의 모든 차이 라인({@code counted_qty <> system_qty})에 COUNT_VARIANCE 이슈를 연다 — 오차를 넘은
     * 라인뿐 아니라 오차 이내인 라인까지 세션 전체의 차이 라인을 모두 포함한다 (db/04-harness.sql
     * tst_count_submit과 동일: 세션 하나라도 REVIEW로 가면 그 세션의 모든 차이가 검토 대상이 된다).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void insertVarianceIssues(long sessionId) {
        jdbc.sql("""
                INSERT INTO inventory_issue (issue_type, severity, location_id, sku_id, lot_id, detail)
                SELECT 'COUNT_VARIANCE', 'MEDIUM', cr.location_id, cr.sku_id, cr.lot_id,
                       jsonb_build_object('countSessionId', cr.count_session_id, 'systemQty', cr.system_qty,
                                          'countedQty', cr.counted_qty, 'diff', cr.counted_qty - cr.system_qty)
                FROM count_result cr WHERE cr.count_session_id = :sessionId AND cr.counted_qty <> cr.system_qty
                """)
                .param("sessionId", sessionId)
                .update();
    }

    public record DiffLine(String skuCode, String lotNo, int diff) {
    }

    /** 정정 대상 라인({@code counted_qty <> system_qty}). diff = counted − system, 조정 거래의 수량 부호와 같다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<DiffLine> diffLines(long sessionId) {
        return jdbc.sql("""
                SELECT s.code AS sku_code, lo.lot_no, cr.counted_qty - cr.system_qty AS diff
                FROM count_result cr JOIN sku s ON s.id = cr.sku_id JOIN lot lo ON lo.id = cr.lot_id
                WHERE cr.count_session_id = :sessionId AND cr.counted_qty <> cr.system_qty
                ORDER BY cr.id
                """)
                .param("sessionId", sessionId)
                .query((rs, rowNum) -> new DiffLine(rs.getString("sku_code"), rs.getString("lot_no"), rs.getInt("diff")))
                .list();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void resolveVarianceIssues(long sessionId, long resolutionTxnId) {
        jdbc.sql("""
                UPDATE inventory_issue SET status = 'RESOLVED', resolved_txn_id = :txnId
                WHERE issue_type = 'COUNT_VARIANCE' AND (detail ->> 'countSessionId')::BIGINT = :sessionId
                  AND status = 'OPEN'
                """)
                .param("txnId", resolutionTxnId)
                .param("sessionId", sessionId)
                .update();
    }
}
