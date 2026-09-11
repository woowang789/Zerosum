-- ── app_admin: 마스터 데이터만 ───────────────────────────────
-- 로케이션을 하나 잘못 만들면 재고가 유령 로케이션으로 들어가고, SKU 코드를 잘못 고치면
-- 이력이 통째로 어긋난다. 그래서 마스터 쓰기를 일상 쓰기 경로(app_rw)와 분리한다.
GRANT SELECT, INSERT, UPDATE ON warehouse, location, sku, lot TO app_admin;
GRANT SELECT ON stock_balance, inventory_txn, inventory_ledger_entry TO app_admin;

-- ── app_rw: 코어 테이블 읽기·쓰기 ────────────────────────────
-- inventory_txn / inventory_ledger_entry의 SELECT·INSERT는 V1__init.sql의 GRANT가 이미 부여했다.
-- 여기서 UPDATE·DELETE를 주지 않는 것이 I4(원장 불변)의 DB 강제다.
-- 마스터는 읽기만 가능하다. 예외는 실사 표시 한 컬럼뿐이다.
GRANT SELECT ON warehouse, location, sku, lot TO app_rw;
GRANT SELECT, INSERT, UPDATE, DELETE ON stock_balance, allocation, count_session, count_result TO app_rw;
GRANT SELECT, INSERT, UPDATE ON idempotency_record, outbox_event, action_proposal, inventory_issue TO app_rw;
GRANT UPDATE (count_session_id) ON location TO app_rw;   -- 실사 표시만 바꿀 수 있다

-- ── 판매 가능 수량 뷰 (05-count-session.md) ──────────────────
CREATE VIEW v_sellable_stock AS
SELECT b.warehouse_id,
       b.sku_id,
       COALESCE(SUM(b.on_hand_qty - b.allocated_qty)
                FILTER (WHERE loc.count_session_id IS NULL), 0)     AS sellable_qty,
       COALESCE(SUM(b.on_hand_qty - b.allocated_qty)
                FILTER (WHERE loc.count_session_id IS NOT NULL), 0) AS in_count_qty
FROM stock_balance b
JOIN location loc ON loc.id = b.location_id AND loc.is_sellable
GROUP BY b.warehouse_id, b.sku_id;

-- ── AI 조회 뷰 (07-ai-integration.md) ────────────────────────
CREATE VIEW v_available_stock AS
SELECT b.warehouse_id,
       b.sku_id,  s.code AS sku_code,  s.name AS sku_name,
       b.lot_id,  l.lot_no,            l.expiry_date,
       b.location_id, loc.code AS location_code,
       b.on_hand_qty, b.allocated_qty,
       b.on_hand_qty - b.allocated_qty AS available_qty,
       loc.count_session_id IS NOT NULL AS in_count
FROM stock_balance b
JOIN location loc ON loc.id = b.location_id AND loc.is_sellable
JOIN sku s        ON s.id   = b.sku_id
JOIN lot l        ON l.id   = b.lot_id;

GRANT SELECT ON v_available_stock TO ai_ro;
GRANT SELECT ON v_sellable_stock  TO app_rw;
GRANT SELECT ON v_available_stock TO app_rw;

-- ── ai_proposer: 제안 생성만 ────────────────────────────────
GRANT INSERT ON action_proposal TO ai_proposer;
