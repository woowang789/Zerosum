package com.zerosum.inventory.repository;

import com.zerosum.inventory.domain.IssueException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 정합 검증 배치 ①~⑤(docs/06-events-reconciliation.md)의 정본 쿼리와, {@code inventory_issue}의
 * 인지(acknowledge)·종결(resolve) 처리. {@code AbstractIntegrationTest}의
 * {@code assertReconciliationClean()}(테스트 오라클)과 {@code ReconciliationService}(운영 배치)가
 * 이 클래스 하나만 호출한다 — 같은 쿼리를 두 벌 두지 않기 위해서다.
 *
 * <p>각 조회 메서드는 SQL 한 문장이라 하나의 스냅샷에서 실행된다. 그래서 운영 중에 돌려도 쓰기 도중의
 * 중간 상태 때문에 거짓 불일치가 나오지 않는다.
 *
 * <p>다른 리포지토리와 달리 메서드에 {@code @Transactional(propagation = MANDATORY)}를 쓰지 않는다.
 * 그 리포지토리들은 포스팅·실사처럼 반드시 상위 서비스 트랜잭션 안에서만 불리지만, 이 클래스는
 * 트랜잭션 없이 직접 호출하는 테스트 오라클과, 마찬가지로 트랜잭션을 두르지 않는 배치
 * (ReconciliationService) 양쪽에서 그대로 쓸 수 있어야 한다. 각 메서드가 SQL 한 문장으로 끝나
 * 원자성에는 문제가 없다.
 */
@Repository
public class ReconciliationRepository {

    private final JdbcClient jdbc;

    ReconciliationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ── ① 잔액 투영 검증 (I4) ───────────────────────────────────────────────────────

    public record ProjectionMismatch(long locationId, long skuId, long lotId, int onHandQty, int ledgerQty) {
    }

    public List<ProjectionMismatch> findProjectionMismatches() {
        return jdbc.sql("""
                WITH ledger_sum AS (
                  SELECT e.location_id, e.sku_id, e.lot_id, SUM(e.qty_delta) AS ledger_qty
                  FROM inventory_ledger_entry e JOIN location l ON l.id = e.location_id
                  WHERE NOT l.is_virtual GROUP BY e.location_id, e.sku_id, e.lot_id)
                SELECT location_id, sku_id, lot_id,
                       COALESCE(b.on_hand_qty, 0) AS on_hand_qty, COALESCE(s.ledger_qty, 0) AS ledger_qty
                FROM stock_balance b
                FULL JOIN ledger_sum s USING (location_id, sku_id, lot_id)
                WHERE COALESCE(b.on_hand_qty, 0) <> COALESCE(s.ledger_qty, 0)
                """)
                .query((rs, rowNum) -> new ProjectionMismatch(
                        rs.getLong("location_id"), rs.getLong("sku_id"), rs.getLong("lot_id"),
                        rs.getInt("on_hand_qty"), rs.getInt("ledger_qty")))
                .list();
    }

    // ── ② 할당 검증 (I5) ────────────────────────────────────────────────────────────

    public record AllocationMismatch(long balanceId, long locationId, long skuId, long lotId,
            int allocatedQty, int activeQty) {
    }

    public List<AllocationMismatch> findAllocationMismatches() {
        return jdbc.sql("""
                SELECT b.id AS balance_id, b.location_id, b.sku_id, b.lot_id, b.allocated_qty,
                       COALESCE(SUM(a.qty), 0) AS active_qty
                FROM stock_balance b
                LEFT JOIN allocation a ON a.balance_id = b.id AND a.status = 'ACTIVE'
                GROUP BY b.id, b.location_id, b.sku_id, b.lot_id, b.allocated_qty
                HAVING b.allocated_qty <> COALESCE(SUM(a.qty), 0)
                """)
                .query((rs, rowNum) -> new AllocationMismatch(
                        rs.getLong("balance_id"), rs.getLong("location_id"), rs.getLong("sku_id"),
                        rs.getLong("lot_id"), rs.getInt("allocated_qty"), rs.getInt("active_qty")))
                .list();
    }

    // ── ③ 원장 체인 검증 (I7) ───────────────────────────────────────────────────────

    public record ChainBreak(long ledgerEntryId, long txnId, long locationId, long skuId, long lotId,
            int onHandAfter, int expected) {
    }

    /**
     * 체인이 한 번 끊기면 LAG가 틀어진 값을 이어받아 그 뒤로 같은 파티션(로케이션·SKU·로트)의 모든 행이
     * 함께 어긋난 것처럼 보인다. {@code DISTINCT ON}으로 파티션별 최솟값(id)만 남겨 실제 원인 거래의
     * 원장 줄만 짚는다 (3단계 작업 지시: "③은 최초 불일치 지점을 짚어야 한다").
     */
    public List<ChainBreak> findChainBreaks() {
        return jdbc.sql("""
                SELECT DISTINCT ON (location_id, sku_id, lot_id)
                       id AS ledger_entry_id, txn_id, location_id, sku_id, lot_id, on_hand_after, expected
                FROM (
                  SELECT e.*, COALESCE(LAG(e.on_hand_after) OVER w, 0) + e.qty_delta AS expected
                  FROM inventory_ledger_entry e WHERE e.on_hand_after IS NOT NULL
                  WINDOW w AS (PARTITION BY e.location_id, e.sku_id, e.lot_id ORDER BY e.id)
                ) t
                WHERE on_hand_after <> expected
                ORDER BY location_id, sku_id, lot_id, id
                """)
                .query((rs, rowNum) -> new ChainBreak(
                        rs.getLong("ledger_entry_id"), rs.getLong("txn_id"), rs.getLong("location_id"),
                        rs.getLong("sku_id"), rs.getLong("lot_id"), rs.getInt("on_hand_after"),
                        rs.getInt("expected")))
                .list();
    }

    // ── ④ 가상 로케이션에 잔액 행이 생겼는지 ────────────────────────────────────────────

    public record VirtualLocationBalance(long balanceId, long locationId, long skuId, long lotId) {
    }

    public List<VirtualLocationBalance> findVirtualLocationBalances() {
        return jdbc.sql("""
                SELECT b.id AS balance_id, b.location_id, b.sku_id, b.lot_id
                FROM stock_balance b JOIN location l ON l.id = b.location_id WHERE l.is_virtual
                """)
                .query((rs, rowNum) -> new VirtualLocationBalance(
                        rs.getLong("balance_id"), rs.getLong("location_id"), rs.getLong("sku_id"),
                        rs.getLong("lot_id")))
                .list();
    }

    // ── ⑤ 실사 표시 검증 (I10) ──────────────────────────────────────────────────────

    public record CountFlagMismatch(long locationId, Long flaggedSessionId, Long activeSessionId) {
    }

    public List<CountFlagMismatch> findCountFlagMismatches() {
        return jdbc.sql("""
                SELECT l.id AS location_id, l.count_session_id AS flagged_session_id, s.id AS active_session_id
                FROM location l
                LEFT JOIN count_session s ON s.location_id = l.id AND s.status IN ('OPEN', 'REVIEW')
                WHERE l.count_session_id IS DISTINCT FROM s.id
                """)
                .query((rs, rowNum) -> new CountFlagMismatch(
                        rs.getLong("location_id"), (Long) rs.getObject("flagged_session_id"),
                        (Long) rs.getObject("active_session_id")))
                .list();
    }

    // ── 요약 (테스트 오라클이 라벨과 함께 쓴다) ──────────────────────────────────────────

    public record CheckSummary(String label, int count) {
    }

    /** ①~⑤를 모두 돌려 개수만 라벨과 함께 묶는다. assertReconciliationClean()이 이 메서드 하나만 부른다. */
    public List<CheckSummary> summarize() {
        return List.of(
                new CheckSummary("① 잔액 투영 (I4)", findProjectionMismatches().size()),
                new CheckSummary("② 할당 (I5)", findAllocationMismatches().size()),
                new CheckSummary("③ 원장 체인 (I7)", findChainBreaks().size()),
                new CheckSummary("④ 가상 로케이션 잔액 행", findVirtualLocationBalances().size()),
                new CheckSummary("⑤ 실사 표시 (I10)", findCountFlagMismatches().size()));
    }

    // ── 이슈 기록 (자동 보정 없이 기록만 — 3단계 작업 지시) ──────────────────────────────

    /**
     * 같은 불일치(issue_type + location_id + sku_id + lot_id, NULL-safe 비교)로 이미 열려 있는
     * (OPEN·ACKED) 이슈가 있으면 새로 만들지 않는다 — 배치를 반복 실행해도 이슈가 중복되지 않게 하기
     * 위해서다. 조회와 삽입을 {@code WHERE NOT EXISTS}로 한 문장에 묶어 원자적으로 처리한다.
     * 이 지점이 CHAIN_BREAK을 포함한 모든 이슈 타입의 dedup 키다 — 파티션(로케이션·SKU·로트)별로
     * 이미 열린 이슈가 있으면 재발이 아닌 한 새로 만들지 않는다.
     *
     * @return 실제로 새 행을 만들었으면 true, 이미 열린 이슈가 있어 건너뛰었으면 false
     */
    public boolean recordIssueIfAbsent(String issueType, String severity, Long locationId, Long skuId, Long lotId,
            Map<String, Long> detail) {
        List<String> keys = List.copyOf(detail.keySet());
        List<Long> values = keys.stream().map(detail::get).toList();
        int n = keys.size();
        // detail JSON은 Java에서 문자열로 조립하지 않는다 — 키·값을 각각 실제 바인드 파라미터
        // (:k0/:v0, :k1/:v1, ...)로 넘기고 jsonb_object_agg가 SQL에서 조립한다(4단계 작업 지시).
        // pairs는 플레이스홀더 "이름"만 만들 뿐이고, Map의 키 문자열·값은 문자열 연결로 SQL에 들어가지
        // 않는다. to_jsonb(BIGINT)로 값을 숫자로 남긴다 — null이면 to_jsonb(NULL::bigint)가 JSON
        // null이 된다(flaggedSessionId 등).
        // VALUES(...)는 행이 0개면 SQL 자체가 깨진다. ReconciliationService의 호출부 5곳은 전부
        // detail에 1개 이상의 키를 채워 부른다(최소가 recordVirtualLocationBalances의 balanceId 1개) —
        // 그래서 빈 Map을 방어하는 코드는 두지 않는다.
        String pairs = IntStream.range(0, n)
                .mapToObj(i -> "(:k%d, CAST(:v%d AS BIGINT))".formatted(i, i))
                .collect(Collectors.joining(", "));
        JdbcClient.StatementSpec query = jdbc.sql("""
                INSERT INTO inventory_issue (issue_type, severity, location_id, sku_id, lot_id, detail)
                SELECT :issueType, :severity, CAST(:locationId AS BIGINT), CAST(:skuId AS BIGINT),
                       CAST(:lotId AS BIGINT),
                       (SELECT COALESCE(jsonb_object_agg(k, to_jsonb(v)), '{}'::JSONB)
                        FROM (VALUES %s) AS t(k, v))
                WHERE NOT EXISTS (
                  SELECT 1 FROM inventory_issue
                  WHERE issue_type = :issueType AND status IN ('OPEN', 'ACKED')
                    AND location_id IS NOT DISTINCT FROM CAST(:locationId AS BIGINT)
                    AND sku_id IS NOT DISTINCT FROM CAST(:skuId AS BIGINT)
                    AND lot_id IS NOT DISTINCT FROM CAST(:lotId AS BIGINT))
                """.formatted(pairs))
                .param("issueType", issueType)
                .param("severity", severity)
                .param("locationId", locationId)
                .param("skuId", skuId)
                .param("lotId", lotId);
        for (int i = 0; i < n; i++) {
            query = query.param("k" + i, keys.get(i)).param("v" + i, values.get(i));
        }
        return query.update() > 0;
    }

    // ── 이슈 처리 (사람이 인지·종결한다 — 배치는 기록만 하고 닫지 않는다) ────────────────

    /**
     * OPEN → ACKED. "봐야 할 이슈" 목록(status IN ('OPEN','ACKED'), idx_issue_open_detected)에는
     * 계속 남고 알림만 멈추는 용도다. {@link AllocationRepository#closeAsReleased}와 같은 패턴 —
     * 조건부 UPDATE의 영향 행 수로 판정해 0건이면(이미 없거나 OPEN이 아니면) 예외를 던진다.
     */
    public void acknowledge(long issueId, String ackedBy) {
        int updated = jdbc.sql("""
                UPDATE inventory_issue SET status = 'ACKED', acked_by = :ackedBy, acked_at = now()
                WHERE id = :id AND status = 'OPEN'
                """)
                .param("ackedBy", ackedBy)
                .param("id", issueId)
                .update();
        if (updated != 1) {
            throw new IssueException("ISSUE_NOT_OPEN", "이슈 %d는 OPEN 상태가 아니다".formatted(issueId));
        }
    }

    /**
     * OPEN·ACKED → RESOLVED (ACKED를 건너뛴 직행도 허용한다). resolvedTxnId는 널일 수 있다 — ③
     * CHAIN_BREAK처럼 app_rw에 원장 UPDATE 권한이 없어 거래로 고칠 수 없는 이슈는 코드만 고치고 과거
     * 원장은 그대로 두는 것도 종결이기 때문이다. 그 경우 resolutionNote에 이유를 남긴다.
     */
    public void resolve(long issueId, String resolvedBy, Long resolvedTxnId, String resolutionNote) {
        int updated = jdbc.sql("""
                UPDATE inventory_issue
                SET status = 'RESOLVED', resolved_by = :resolvedBy, resolved_at = now(),
                    resolved_txn_id = CAST(:resolvedTxnId AS BIGINT), resolution_note = :resolutionNote
                WHERE id = :id AND status IN ('OPEN', 'ACKED')
                """)
                .param("resolvedBy", resolvedBy)
                .param("resolvedTxnId", resolvedTxnId)
                .param("resolutionNote", resolutionNote)
                .param("id", issueId)
                .update();
        if (updated != 1) {
            throw new IssueException("ISSUE_ALREADY_RESOLVED", "이슈 %d는 이미 RESOLVED 상태다".formatted(issueId));
        }
    }
}
