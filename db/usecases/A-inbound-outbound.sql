-- ══ A. 입고부터 출고까지 정상 흐름 ═══════════════════════════
-- 2026-09-11 인천 1센터의 하루를 따라간다.

-- A01. 발주 PO-20260908-0042 입고: 공급사 가상 로케이션 → 입고장
SELECT tst_run('UC-A01', '티셔츠 120개 입고 (공급사 → RCV-01)', $$
  SELECT tst_post('receipt:PO-20260908-0042-1:1', 'RECEIPT', 'user:kim.ys',
    '[{"wh":"ICN01","loc":"V-SUPPLIER","sku":"SKU-100001","lot":"DEFAULT","qty":-120},
      {"wh":"ICN01","loc":"RCV-01",    "sku":"SKU-100001","lot":"DEFAULT","qty":120}]'::jsonb,
    'PO', 'PO-20260908-0042') $$);
SELECT tst_assert('UC-A01', 'RCV-01 실재고', tst_qty('ICN01','RCV-01','SKU-100001'), 120);
SELECT tst_assert('UC-A01', '공급사 가상 로케이션에는 잔액 행이 없다',
  (SELECT count(*)::INT FROM stock_balance WHERE location_id = tst_loc('ICN01','V-SUPPLIER')), 0);

-- A02. 적치: 입고장 → 보관
SELECT tst_run('UC-A02', '티셔츠 120개 적치 (RCV-01 → A-01-01-1)', $$
  SELECT tst_post('move:WO-20260911-0007', 'MOVE', 'user:park.jh',
    '[{"wh":"ICN01","loc":"RCV-01",   "sku":"SKU-100001","lot":"DEFAULT","qty":-120},
      {"wh":"ICN01","loc":"A-01-01-1","sku":"SKU-100001","lot":"DEFAULT","qty":120}]'::jsonb,
    'WORK_ORDER', 'WO-20260911-0007') $$);
SELECT tst_assert('UC-A02', 'RCV-01 실재고', tst_qty('ICN01','RCV-01','SKU-100001'), 0);
SELECT tst_assert('UC-A02', 'A-01-01-1 실재고', tst_qty('ICN01','A-01-01-1','SKU-100001'), 120);

-- A03. 로트 관리 SKU 입고. 유통기한이 늦은 로트를 먼저 넣어 FEFO가 id 순서가 아님을 만든다
SELECT tst_run('UC-A03a', '콜드브루 L20260910-B(만료 10-10) 80개 입고·적치', $$
  SELECT tst_post('receipt:PO-20260910-0088-1:1', 'RECEIPT', 'user:kim.ys',
    '[{"wh":"ICN01","loc":"V-SUPPLIER","sku":"SKU-200002","lot":"L20260910-B","qty":-80},
      {"wh":"ICN01","loc":"A-01-01-2","sku":"SKU-200002","lot":"L20260910-B","qty":80}]'::jsonb,
    'PO', 'PO-20260910-0088') $$);
SELECT tst_run('UC-A03b', '콜드브루 L20260901-A(만료 10-01) 50개 입고·적치', $$
  SELECT tst_post('receipt:PO-20260901-0031-1:1', 'RECEIPT', 'user:kim.ys',
    '[{"wh":"ICN01","loc":"V-SUPPLIER","sku":"SKU-200002","lot":"L20260901-A","qty":-50},
      {"wh":"ICN01","loc":"A-01-02-1","sku":"SKU-200002","lot":"L20260901-A","qty":50}]'::jsonb,
    'PO', 'PO-20260901-0031') $$);

-- A04. 할당: FEFO는 잔액 행 id가 아니라 유통기한 순서를 따라야 한다
SELECT tst_run('UC-A04', '주문 ORD-20260911-0001 콜드브루 60개 할당 (FEFO)', $$
  SELECT tst_allocate('alloc:ORD-20260911-0001-1', 'ORD-20260911-0001-1', 'ICN01', 'SKU-200002', 60, FALSE) $$);
SELECT tst_assert('UC-A04', '만료 임박 L20260901-A를 먼저 50개 전량 할당',
  (SELECT allocated_qty FROM stock_balance WHERE location_id = tst_loc('ICN01','A-01-02-1')
     AND lot_id = (SELECT id FROM lot WHERE lot_no='L20260901-A')), 50);
SELECT tst_assert('UC-A04', '나머지 10개는 L20260910-B에서',
  (SELECT allocated_qty FROM stock_balance WHERE location_id = tst_loc('ICN01','A-01-01-2')
     AND lot_id = (SELECT id FROM lot WHERE lot_no='L20260910-B')), 10);
SELECT tst_assert('UC-A04', 'A-01-02-1 가용 수량은 0 (실재고 50, 할당 50)',
  tst_avail('ICN01','A-01-02-1','SKU-200002','L20260901-A'), 0);

-- A05. 출고: 할당을 소진하면서 실재고와 할당량을 함께 줄인다
SELECT tst_run('UC-A05', '주문 ORD-20260911-0001 출고 60개 (할당 소진)', $$
  SELECT tst_post('ship:ORD-20260911-0001-1:1', 'SHIPMENT', 'user:park.jh',
    '[{"wh":"ICN01","loc":"A-01-02-1", "sku":"SKU-200002","lot":"L20260901-A","qty":-50},
      {"wh":"ICN01","loc":"V-CUSTOMER","sku":"SKU-200002","lot":"L20260901-A","qty":50},
      {"wh":"ICN01","loc":"A-01-01-2", "sku":"SKU-200002","lot":"L20260910-B","qty":-10},
      {"wh":"ICN01","loc":"V-CUSTOMER","sku":"SKU-200002","lot":"L20260910-B","qty":10}]'::jsonb,
    'ORDER', 'ORD-20260911-0001',
    p_consume_alloc => tst_alloc_ids('ORD-20260911-0001-1')) $$);
SELECT tst_assert('UC-A05', 'A-01-02-1 실재고 0', tst_qty('ICN01','A-01-02-1','SKU-200002','L20260901-A'), 0);
SELECT tst_assert('UC-A05', 'A-01-01-2 실재고 70', tst_qty('ICN01','A-01-01-2','SKU-200002','L20260910-B'), 70);
SELECT tst_assert('UC-A05', 'A-01-01-2 할당량 0 (소진됨)',
  (SELECT allocated_qty FROM stock_balance WHERE location_id = tst_loc('ICN01','A-01-01-2')
     AND lot_id = (SELECT id FROM lot WHERE lot_no='L20260910-B')), 0);
SELECT tst_assert('UC-A05', '할당 2건 모두 CONSUMED',
  (SELECT count(*)::INT FROM allocation WHERE order_line_ref='ORD-20260911-0001-1' AND status='CONSUMED'), 2);

-- A06. 주문 취소로 할당 해제
SELECT tst_run('UC-A06a', '주문 ORD-20260911-0002 티셔츠 30개 할당', $$
  SELECT tst_allocate('alloc:ORD-20260911-0002-1', 'ORD-20260911-0002-1', 'ICN01', 'SKU-100001', 30, FALSE) $$);
SELECT tst_assert('UC-A06a', 'A-01-01-1 가용 90 (실재고 120 − 할당 30)',
  tst_avail('ICN01','A-01-01-1','SKU-100001'), 90);
SELECT tst_run('UC-A06b', '주문 취소 → 할당 해제', $$
  SELECT tst_release_alloc('release:ORD-20260911-0002-1', tst_alloc_ids('ORD-20260911-0002-1')) $$);
SELECT tst_assert('UC-A06b', 'A-01-01-1 가용 120으로 복구', tst_avail('ICN01','A-01-01-1','SKU-100001'), 120);
SELECT tst_assert('UC-A06b', '할당 상태 RELEASED',
  (SELECT status FROM allocation WHERE order_line_ref='ORD-20260911-0002-1'), 'RELEASED');

-- A07~A09. 반품: 고객 → 반품검수 → (양품 보관 / 불량 격리)
SELECT tst_run('UC-A07', '반품 RMA-20260911-0003 티셔츠 3개 입고 (고객 → RTN-01)', $$
  SELECT tst_post('return:RMA-20260911-0003:1', 'RETURN', 'user:kim.ys',
    '[{"wh":"ICN01","loc":"V-CUSTOMER","sku":"SKU-100001","lot":"DEFAULT","qty":-3},
      {"wh":"ICN01","loc":"RTN-01",    "sku":"SKU-100001","lot":"DEFAULT","qty":3}]'::jsonb,
    'RMA', 'RMA-20260911-0003') $$);
SELECT tst_run('UC-A08', '검수 양품 2개 → 보관 (RTN-01 → A-01-01-1)', $$
  SELECT tst_post('move:RMA-20260911-0003:pass', 'MOVE', 'user:park.jh',
    '[{"wh":"ICN01","loc":"RTN-01",   "sku":"SKU-100001","lot":"DEFAULT","qty":-2},
      {"wh":"ICN01","loc":"A-01-01-1","sku":"SKU-100001","lot":"DEFAULT","qty":2}]'::jsonb,
    'RMA', 'RMA-20260911-0003') $$);
SELECT tst_run('UC-A09', '검수 불량 1개 → 불량 격리 (RTN-01 → DMG-01)', $$
  SELECT tst_post('move:RMA-20260911-0003:fail', 'MOVE', 'user:park.jh',
    '[{"wh":"ICN01","loc":"RTN-01","sku":"SKU-100001","lot":"DEFAULT","qty":-1},
      {"wh":"ICN01","loc":"DMG-01","sku":"SKU-100001","lot":"DEFAULT","qty":1}]'::jsonb,
    'RMA', 'RMA-20260911-0003') $$);
SELECT tst_assert('UC-A09', 'RTN-01 비었음', tst_qty('ICN01','RTN-01','SKU-100001'), 0);
SELECT tst_assert('UC-A09', 'DMG-01 1개', tst_qty('ICN01','DMG-01','SKU-100001'), 1);
SELECT tst_assert('UC-A09', 'A-01-01-1 122개 (120 + 양품 2)', tst_qty('ICN01','A-01-01-1','SKU-100001'), 122);

-- A10. 보관 중 파손 조정 (사유 코드 필수)
SELECT tst_run('UC-A10', '지게차 파손 2개 조정 (A-01-01-1 → 조정 가상)', $$
  SELECT tst_post('adjust:INC-20260911-0012', 'ADJUSTMENT', 'user:choi.dw',
    '[{"wh":"ICN01","loc":"A-01-01-1","sku":"SKU-100001","lot":"DEFAULT","qty":-2},
      {"wh":"ICN01","loc":"V-ADJUST", "sku":"SKU-100001","lot":"DEFAULT","qty":2}]'::jsonb,
    'INCIDENT', 'INC-20260911-0012', 'DAMAGED_IN_STORAGE') $$);
SELECT tst_assert('UC-A10', 'A-01-01-1 120개', tst_qty('ICN01','A-01-01-1','SKU-100001'), 120);

-- A11. 잘못 올린 조정을 역분개로 정정 (원장은 지우지 않는다)
SELECT tst_run('UC-A11', '조정 오입력 역분개', $$
  SELECT tst_post('reverse:INC-20260911-0012', 'REVERSAL', 'user:choi.dw',
    '[{"wh":"ICN01","loc":"A-01-01-1","sku":"SKU-100001","lot":"DEFAULT","qty":2},
      {"wh":"ICN01","loc":"V-ADJUST", "sku":"SKU-100001","lot":"DEFAULT","qty":-2}]'::jsonb,
    'INCIDENT', 'INC-20260911-0012', 'REVERSAL',
    p_reverses_txn_id => (SELECT id FROM inventory_txn WHERE idem_key='adjust:INC-20260911-0012')) $$);
SELECT tst_assert('UC-A11', 'A-01-01-1 122개로 복구', tst_qty('ICN01','A-01-01-1','SKU-100001'), 122);
SELECT tst_assert('UC-A11', '원장은 지워지지 않고 4줄이 남는다 (조정 2 + 역분개 2)',
  (SELECT count(*)::INT FROM inventory_ledger_entry e JOIN inventory_txn t ON t.id=e.txn_id
    WHERE t.source_ref='INC-20260911-0012'), 4);

-- A12~A13. 센터 간 이동: 운송 중에도 재고가 사라지지 않는다
SELECT tst_run('UC-A12', '용인센터로 40개 출발 (A-01-01-1 → TRS-01)', $$
  SELECT tst_post('transfer_out:TR-20260911-0002', 'TRANSFER_OUT', 'user:park.jh',
    '[{"wh":"ICN01","loc":"A-01-01-1","sku":"SKU-100001","lot":"DEFAULT","qty":-40},
      {"wh":"ICN01","loc":"TRS-01",   "sku":"SKU-100001","lot":"DEFAULT","qty":40}]'::jsonb,
    'TRANSFER', 'TR-20260911-0002') $$);
SELECT tst_assert('UC-A12', '운송 중 재고 40개가 TRS-01에 보인다', tst_qty('ICN01','TRS-01','SKU-100001'), 40);
SELECT tst_run('UC-A13', '용인센터 도착 스캔 (ICN01 TRS-01 → YIT01 RCV-01)', $$
  SELECT tst_post('transfer_in:TR-20260911-0002', 'TRANSFER_IN', 'user:jung.hm',
    '[{"wh":"ICN01","loc":"TRS-01","sku":"SKU-100001","lot":"DEFAULT","qty":-40},
      {"wh":"YIT01","loc":"RCV-01","sku":"SKU-100001","lot":"DEFAULT","qty":40}]'::jsonb,
    'TRANSFER', 'TR-20260911-0002') $$);
SELECT tst_assert('UC-A13', 'ICN01 TRS-01 비었음', tst_qty('ICN01','TRS-01','SKU-100001'), 0);
SELECT tst_assert('UC-A13', 'YIT01 RCV-01 40개', tst_qty('YIT01','RCV-01','SKU-100001'), 40);

-- A14. 슬로팅 재배치
SELECT tst_run('UC-A14', '슬로팅 이동 30개 (A-01-01-1 → B-01-01-1)', $$
  SELECT tst_post('move:SLOT-20260911-0001', 'MOVE', 'user:park.jh',
    '[{"wh":"ICN01","loc":"A-01-01-1","sku":"SKU-100001","lot":"DEFAULT","qty":-30},
      {"wh":"ICN01","loc":"B-01-01-1","sku":"SKU-100001","lot":"DEFAULT","qty":30}]'::jsonb,
    'SLOTTING', 'SLOT-20260911-0001') $$);
SELECT tst_assert('UC-A14', 'ICN01 판매 가능 수량 (A 52 + B 30)',
  (SELECT sellable_qty::INT FROM v_sellable_stock v JOIN warehouse w ON w.id=v.warehouse_id
    JOIN sku s ON s.id=v.sku_id WHERE w.code='ICN01' AND s.code='SKU-100001'), 82);
