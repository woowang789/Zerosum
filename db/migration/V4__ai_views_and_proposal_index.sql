-- 4단계: AI 조회 뷰, 제안 목록 인덱스, AI 쓰기 경계
--
-- V1·V2·V3은 이미 적용된 마이그레이션이라 한 글자도 고치지 않는다.
-- 배경은 docs/07-ai-integration.md(권한 경계·조회 뷰), docs/08-testing-roadmap.md 4단계.

-- ── 제안 목록 인덱스 (docs/08-testing-roadmap.md 4단계) ───────────────────
-- 상태별 최신순 목록이 승인 화면의 유일한 조회 경로다. inventory_issue의 idx_issue_open_detected와 달리
-- 부분 인덱스로 좁히지 않는다 — 제안은 PENDING뿐 아니라 EXECUTED·STALE 이력 목록도 화면이 된다.
CREATE INDEX idx_proposal_status_created ON action_proposal (status, created_at);

-- ── 원장 조회 뷰 (get_ledger, get_issue_context) ──────────────────────────
-- occurred_at은 거래 헤더에만 있다. 비정규화는 하지 않기로 했고(측정 결과), 조인은 이 뷰 한 곳에만 둔다.
CREATE VIEW v_ledger AS
SELECT e.id AS ledger_entry_id, e.txn_id, t.txn_type, t.source_type, t.source_ref, t.reason_code,
       t.actor_type, t.actor_id, t.proposal_id, t.occurred_at, t.recorded_at,
       e.warehouse_id, w.code AS warehouse_code, e.location_id, loc.code AS location_code, loc.is_virtual,
       e.sku_id, s.code AS sku_code, e.lot_id, l.lot_no, e.qty_delta, e.on_hand_after
FROM inventory_ledger_entry e
JOIN inventory_txn t ON t.id = e.txn_id      JOIN warehouse w ON w.id  = e.warehouse_id
JOIN location    loc ON loc.id = e.location_id  JOIN sku     s ON s.id  = e.sku_id
JOIN lot           l ON l.id = e.lot_id;

-- ── 봐야 할 이슈 (list_open_issues) ───────────────────────────────────────
-- "봐야 할 이슈" = status IN ('OPEN','ACKED'). idx_issue_open_detected(V3)가 그대로 쓰인다.
-- acked_by·resolved_*·resolution_note는 일부러 내보내지 않는다 — AI가 "누가 처리했는지"를 읽을 필요가 없고,
-- 읽을 수 없으면 그것을 근거로 제안할 수도 없다. 컬럼 GRANT와 함께 이중 방어선이 된다.
CREATE VIEW v_open_issue AS
SELECT i.id AS issue_id, i.issue_type, i.severity, i.status,
       i.location_id, loc.code AS location_code, w.code AS warehouse_code,
       i.sku_id, s.code AS sku_code, i.lot_id, l.lot_no,
       i.count_session_id, i.detail, i.ai_analysis, i.detected_at, i.acked_at
FROM inventory_issue i
LEFT JOIN location loc ON loc.id = i.location_id   LEFT JOIN warehouse w ON w.id = loc.warehouse_id
LEFT JOIN sku        s ON s.id   = i.sku_id        LEFT JOIN lot       l ON l.id = i.lot_id
WHERE i.status IN ('OPEN', 'ACKED');

-- ── 실사 이력 (get_issue_context의 원인 분석 재료) ────────────────────────
CREATE VIEW v_count_history AS
SELECT cr.count_session_id, cs.status AS session_status,
       cs.started_at, cs.submitted_at, cs.closed_at, cs.resolution_txn_id,
       cr.location_id, loc.code AS location_code, cr.sku_id, s.code AS sku_code, cr.lot_id, l.lot_no,
       cr.system_qty, cr.counted_qty, cr.counted_qty - cr.system_qty AS diff_qty, cr.counted_by, cr.counted_at
FROM count_result cr
JOIN count_session cs ON cs.id = cr.count_session_id  JOIN location loc ON loc.id = cr.location_id
JOIN sku            s ON s.id  = cr.sku_id            JOIN lot        l ON l.id   = cr.lot_id;

-- ── basis 캡처용 뷰 ───────────────────────────────────────────────────────
-- v_available_stock(V2)은 is_sellable 로케이션만 보여주므로 근거 원천이 될 수 없다(RECEIVING·RETURN_HOLD·
-- DAMAGED·TRANSIT 재고를 숨긴다). 그리고 basis_snapshot이 관측값을 식별하는 balance_id를 노출하지 않는다.
CREATE VIEW v_balance_basis AS
SELECT b.id AS balance_id, w.code AS warehouse_code, loc.code AS location_code, s.code AS sku_code, l.lot_no,
       b.on_hand_qty, b.allocated_qty, b.on_hand_qty - b.allocated_qty AS available_qty
FROM stock_balance b
JOIN warehouse w ON w.id = b.warehouse_id   JOIN location loc ON loc.id = b.location_id
JOIN sku       s ON s.id = b.sku_id         JOIN lot        l ON l.id   = b.lot_id;

-- v_sellable_stock(V2)은 warehouse_id·sku_id로만 집계돼 코드 컬럼이 없다. AI 표면은 코드로 다뤄야 하고
-- (docs/07-ai-integration.md), ai_proposer에 warehouse·sku 마스터 테이블 권한을 주지 않으려면 코드→id
-- 조인을 뷰 안에 둬야 한다(아래 v_balance_basis와 같은 원리).
CREATE VIEW v_warehouse_sku_basis AS
SELECT w.code AS warehouse_code, s.code AS sku_code,
       v.warehouse_id, v.sku_id, v.sellable_qty
FROM v_sellable_stock v
JOIN warehouse w ON w.id = v.warehouse_id
JOIN sku       s ON s.id = v.sku_id;

-- ── 권한 ──────────────────────────────────────────────────────────────────
-- 뷰는 소유자(migrator) 권한으로 평가되므로(security_invoker 기본 false) 기반 테이블 GRANT 없이 동작한다.
-- ai_ro가 stock_balance를 못 읽으면서 v_available_stock을 읽는 현 상태가 그 증거다.
GRANT SELECT ON v_ledger, v_open_issue, v_count_history TO ai_ro;
GRANT SELECT ON v_ledger, v_open_issue, v_count_history, v_balance_basis TO app_rw;

-- ai_proposer: docs/07의 표는 "action_proposal INSERT만"이었지만 그대로는 성립하지 않는다.
-- INSERT ... RETURNING id가 SELECT 권한을 요구해 제안 id를 돌려줄 수 없고(권한 오류 42501),
-- 잔액을 못 읽어 basis_snapshot을 서버가 직접 채울 수도 없다(docs/07-ai-integration.md의 basis 규칙과 모순).
-- 그래서 규칙을 지키는 쪽으로 표를 넓히되, 컬럼 단위로만 연다.
GRANT SELECT ON v_balance_basis, v_warehouse_sku_basis TO ai_proposer;
GRANT SELECT (id, proposal_type, command_payload, status, created_at, expires_at)
  ON action_proposal TO ai_proposer;

-- ai_analysis 한 컬럼만 쓸 수 있고, status·acked_*·resolved_*는 문법적으로 못 건드린다.
-- WHERE 절에 쓸 수 있는 컬럼도 (id, status)뿐이다 — 컬럼 SELECT 권한이 없으면 WHERE에서도 42501이 난다.
-- "ACKED는 사람이 인지했다는 뜻"이라는 규칙을 앱 규율이 아니라 DB가 지킨다.
GRANT SELECT (id, status) ON inventory_issue TO ai_proposer;
GRANT UPDATE (ai_analysis) ON inventory_issue TO ai_proposer;
