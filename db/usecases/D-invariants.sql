-- ══ D. 불변식 위반은 정상적으로 실패해야 한다 ════════════════
-- 앱 검사를 통과해버린 상황을 가정해 DB 제약이 최후 방어선으로 서는지 본다.

-- I1. 물리 로케이션 실재고는 음수가 될 수 없다
SELECT tst_run('UC-D01a', '[앱] 가용 초과 출고 → 애플리케이션이 먼저 막는다', $$
  SELECT tst_post('ship:ORD-20260911-0020-1:1', 'SHIPMENT', 'user:park.jh',
    '[{"wh":"ICN01","loc":"B-01-01-1","sku":"SKU-100001","lot":"DEFAULT","qty":-999},
      {"wh":"ICN01","loc":"V-CUSTOMER","sku":"SKU-100001","lot":"DEFAULT","qty":999}]'::jsonb,
    'ORDER', 'ORD-20260911-0020') $$, 'INSUFFICIENT_STOCK');
SELECT tst_run('UC-D01b', '[DB] 앱을 건너뛰고 실재고를 음수로 UPDATE → CHECK', $$
  UPDATE stock_balance SET on_hand_qty = -1
   WHERE location_id = tst_loc('ICN01','B-01-01-1') $$, '23514');

-- I2. 할당량은 0 이상, 실재고 이하
SELECT tst_run('UC-D02a', '[DB] 실재고보다 큰 할당량 → CHECK', $$
  UPDATE stock_balance SET allocated_qty = on_hand_qty + 1
   WHERE location_id = tst_loc('ICN01','B-01-01-1') $$, '23514');
SELECT tst_run('UC-D02b', '[DB] 음수 할당량 → CHECK', $$
  UPDATE stock_balance SET allocated_qty = -1
   WHERE location_id = tst_loc('ICN01','B-01-01-1') $$, '23514');

-- 할당된 재고는 조정으로 줄일 수 없다 (할당 해제가 먼저다)
-- 마우스는 A-02-01-1 한 곳에만 있어 할당이 어느 잔액 행에 붙을지 분명하다
SELECT tst_run('UC-D03a', '주문 ORD-20260911-0021 마우스 12개 할당 (재고 15 중)', $$
  SELECT tst_allocate('alloc:ORD-20260911-0021-1', 'ORD-20260911-0021-1', 'ICN01', 'SKU-300001', 12, FALSE) $$);
SELECT tst_assert('UC-D03a', 'A-02-01-1 가용 3 (실재고 15 − 할당 12)',
  tst_avail('ICN01','A-02-01-1','SKU-300001'), 3);
SELECT tst_run('UC-D03b', '할당분까지 조정으로 차감 시도 → 거절', $$
  SELECT tst_post('adjust:INC-20260911-0030', 'ADJUSTMENT', 'user:choi.dw',
    '[{"wh":"ICN01","loc":"A-02-01-1","sku":"SKU-300001","lot":"DEFAULT","qty":-15},
      {"wh":"ICN01","loc":"V-ADJUST",  "sku":"SKU-300001","lot":"DEFAULT","qty":15}]'::jsonb,
    'INCIDENT', 'INC-20260911-0030', 'LOST') $$, 'INSUFFICIENT_STOCK');
SELECT tst_run('UC-D03c', '할당 해제 후 다시 조정 → 이번엔 통과', $$
  SELECT tst_release_alloc('release:ORD-20260911-0021-1', tst_alloc_ids('ORD-20260911-0021-1'));
  SELECT tst_post('adjust:INC-20260911-0030', 'ADJUSTMENT', 'user:choi.dw',
    '[{"wh":"ICN01","loc":"A-02-01-1","sku":"SKU-300001","lot":"DEFAULT","qty":-15},
      {"wh":"ICN01","loc":"V-ADJUST",  "sku":"SKU-300001","lot":"DEFAULT","qty":15}]'::jsonb,
    'INCIDENT', 'INC-20260911-0030', 'LOST') $$);
SELECT tst_assert('UC-D03c', 'A-02-01-1 실재고 0', tst_qty('ICN01','A-02-01-1','SKU-300001'), 0);

-- I3. 거래의 (SKU, 로트)별 수량 합은 0
SELECT tst_run('UC-D04a', '[앱] 합계가 맞지 않는 커맨드 → 커맨드 검증이 막는다', $$
  SELECT tst_post('move:BAD-0001', 'MOVE', 'user:park.jh',
    '[{"wh":"ICN01","loc":"B-01-01-1","sku":"SKU-100001","lot":"DEFAULT","qty":-5},
      {"wh":"ICN01","loc":"A-01-01-1","sku":"SKU-100001","lot":"DEFAULT","qty":3}]'::jsonb,
    'WORK_ORDER', 'BAD-0001') $$, 'NOT_ZERO_SUM');
SELECT tst_run('UC-D04b', '[DB] 기존 거래에 한쪽 원장만 추가 → 지연 제약 트리거', $$
  INSERT INTO inventory_ledger_entry (txn_id, warehouse_id, location_id, sku_id, lot_id, qty_delta, on_hand_after)
  SELECT t.id, (SELECT id FROM warehouse WHERE code='ICN01'), tst_loc('ICN01','B-01-01-1'),
         (SELECT id FROM sku WHERE code='SKU-100001'),
         (SELECT id FROM lot WHERE lot_no='DEFAULT' AND sku_id=(SELECT id FROM sku WHERE code='SKU-100001')),
         7, NULL
  FROM inventory_txn t WHERE t.idem_key='move:SLOT-20260911-0001' $$,
  'is not zero-sum', TRUE);

-- I4. 원장은 추가만 가능하다
SELECT tst_run('UC-D05a', '[권한] app_rw가 원장 UPDATE 시도 → 거부', $$
  UPDATE inventory_ledger_entry SET qty_delta = 999 WHERE id = (SELECT min(id) FROM inventory_ledger_entry) $$,
  '42501');
SELECT tst_run('UC-D05b', '[권한] app_rw가 원장 DELETE 시도 → 거부', $$
  DELETE FROM inventory_ledger_entry WHERE id = (SELECT min(id) FROM inventory_ledger_entry) $$, '42501');
SELECT tst_run('UC-D05c', '[권한] app_rw가 거래 헤더 UPDATE 시도 → 거부', $$
  UPDATE inventory_txn SET actor_id = 'hacker' WHERE id = (SELECT min(id) FROM inventory_txn) $$, '42501');

-- I6. 커맨드 하나는 최대 한 번 실행
SELECT tst_run('UC-D06', '[DB] 같은 멱등 키로 거래를 두 번 만들기 → UNIQUE', $$
  INSERT INTO inventory_txn (idem_key, txn_type, actor_type, actor_id, occurred_at)
  VALUES ('move:WO-20260911-0007', 'MOVE', 'USER', 'user:park.jh', now()) $$, '23505');

-- I9. 로트는 해당 SKU 소속, 로케이션은 해당 창고 소속
SELECT tst_run('UC-D07', '[DB] 다른 SKU의 로트로 잔액 행 생성 → 복합 외래키', $$
  INSERT INTO stock_balance (warehouse_id, location_id, sku_id, lot_id)
  VALUES ((SELECT id FROM warehouse WHERE code='ICN01'), tst_loc('ICN01','A-02-01-1'),
          (SELECT id FROM sku WHERE code='SKU-100001'),
          (SELECT id FROM lot WHERE lot_no='L20260901-A')) $$, '23503');
SELECT tst_run('UC-D08', '[DB] 다른 창고의 로케이션으로 잔액 행 생성 → 복합 외래키', $$
  INSERT INTO stock_balance (warehouse_id, location_id, sku_id, lot_id)
  VALUES ((SELECT id FROM warehouse WHERE code='YIT01'), tst_loc('ICN01','A-01-02-1'),
          (SELECT id FROM sku WHERE code='SKU-300001'),
          (SELECT id FROM lot WHERE lot_no='DEFAULT' AND sku_id=(SELECT id FROM sku WHERE code='SKU-300001'))) $$,
  '23503');

-- 거래 헤더의 CHECK 제약들
SELECT tst_run('UC-D09', '[DB] 사유 코드 없는 조정 → CHECK', $$
  INSERT INTO idempotency_record (idem_key, command_type, request_hash)
  VALUES ('bad:adjust-no-reason', 'ADJUSTMENT', repeat('0',64));
  INSERT INTO inventory_txn (idem_key, txn_type, actor_type, actor_id, occurred_at)
  VALUES ('bad:adjust-no-reason', 'ADJUSTMENT', 'USER', 'user:choi.dw', now()) $$, '23514');
SELECT tst_run('UC-D10', '[DB] REVERSAL인데 원거래 참조가 없다 → CHECK', $$
  INSERT INTO idempotency_record (idem_key, command_type, request_hash)
  VALUES ('bad:reversal-no-ref', 'REVERSAL', repeat('0',64));
  INSERT INTO inventory_txn (idem_key, txn_type, actor_type, actor_id, occurred_at)
  VALUES ('bad:reversal-no-ref', 'REVERSAL', 'USER', 'user:choi.dw', now()) $$, '23514');
SELECT tst_run('UC-D11', '[DB] REVERSAL이 아닌데 원거래를 참조한다 → CHECK', $$
  INSERT INTO idempotency_record (idem_key, command_type, request_hash)
  VALUES ('bad:move-with-ref', 'MOVE', repeat('0',64));
  INSERT INTO inventory_txn (idem_key, txn_type, actor_type, actor_id, occurred_at, reverses_txn_id)
  VALUES ('bad:move-with-ref', 'MOVE', 'USER', 'user:park.jh', now(), (SELECT min(id) FROM inventory_txn)) $$,
  '23514');
SELECT tst_run('UC-D12', '[DB] AI를 실행 주체로 기록 시도 → CHECK (actor_type은 USER/SYSTEM뿐)', $$
  INSERT INTO idempotency_record (idem_key, command_type, request_hash)
  VALUES ('bad:actor-ai', 'MOVE', repeat('0',64));
  INSERT INTO inventory_txn (idem_key, txn_type, actor_type, actor_id, occurred_at)
  VALUES ('bad:actor-ai', 'MOVE', 'AI', 'agent:rebalancer', now()) $$, '23514');
SELECT tst_run('UC-D13', '[DB] 같은 거래를 두 번 역분개 → UNIQUE', $$
  INSERT INTO idempotency_record (idem_key, command_type, request_hash)
  VALUES ('bad:double-reversal', 'REVERSAL', repeat('0',64));
  INSERT INTO inventory_txn (idem_key, txn_type, actor_type, actor_id, occurred_at, reverses_txn_id)
  VALUES ('bad:double-reversal', 'REVERSAL', 'USER', 'user:choi.dw', now(),
          (SELECT id FROM inventory_txn WHERE idem_key='adjust:INC-20260911-0012')) $$, '23505');
SELECT tst_run('UC-D14', '이미 출고된 입고를 역분개 → 음수 방지로 실패 (정정 순서를 알려준다)', $$
  SELECT tst_post('reverse:PO-20260911-0101', 'REVERSAL', 'user:choi.dw',
    '[{"wh":"ICN01","loc":"V-SUPPLIER","sku":"SKU-300001","lot":"DEFAULT","qty":20},
      {"wh":"ICN01","loc":"A-02-01-1", "sku":"SKU-300001","lot":"DEFAULT","qty":-20}]'::jsonb,
    'PO', 'PO-20260911-0101', 'REVERSAL',
    p_reverses_txn_id => (SELECT id FROM inventory_txn WHERE idem_key='receipt:PO-20260911-0101-1:1')) $$,
  'INSUFFICIENT_STOCK');

-- 그 밖의 값 제약
SELECT tst_run('UC-D15', '[DB] 변동량 0인 원장 → CHECK', $$
  INSERT INTO inventory_ledger_entry (txn_id, warehouse_id, location_id, sku_id, lot_id, qty_delta)
  VALUES ((SELECT min(id) FROM inventory_txn), (SELECT id FROM warehouse WHERE code='ICN01'),
          tst_loc('ICN01','A-01-01-1'), (SELECT id FROM sku WHERE code='SKU-100001'),
          (SELECT id FROM lot WHERE lot_no='DEFAULT' AND sku_id=(SELECT id FROM sku WHERE code='SKU-100001')),
          0) $$, '23514');
SELECT tst_run('UC-D17', '[DB] 수량 0짜리 할당 → CHECK', $$
  INSERT INTO allocation (idem_key, order_line_ref, balance_id, qty)
  VALUES ('move:WO-20260911-0007', 'ORD-BAD-1',
          (SELECT id FROM stock_balance WHERE location_id = tst_loc('ICN01','B-01-01-1')), 0) $$, '23514');
SELECT tst_run('UC-D18', '[DB] CONFIRMED인데 닫힌 시각이 없는 실사 세션 → CHECK', $$
  INSERT INTO count_session (location_id, status, started_by, submitted_at)
  VALUES (tst_loc('ICN01','A-01-02-1'), 'CONFIRMED', 'user:lee.sh', now()) $$, '23514');

-- 마스터 데이터는 app_rw의 일이 아니다
SELECT tst_run('UC-D26', '[권한] app_rw가 로케이션을 만든다 → 거부', $$
  INSERT INTO location (warehouse_id, code, location_type)
  VALUES ((SELECT id FROM warehouse WHERE code='ICN01'), 'Z-01-01-1', 'STORAGE') $$, '42501');
SELECT tst_run('UC-D27', '[권한] app_rw가 SKU 이름을 고친다 → 거부', $$
  UPDATE sku SET name = '바뀐 이름' WHERE code = 'SKU-100001' $$, '42501');
SELECT tst_run('UC-D28', '[권한] app_rw는 실사 표시 컬럼만 바꿀 수 있다 → 허용', $$
  UPDATE location SET count_session_id = NULL WHERE id = tst_loc('ICN01','A-02-01-1') $$);
SELECT tst_run('UC-D29', '[권한] app_rw가 로케이션 유형을 바꾼다 → 거부', $$
  UPDATE location SET location_type = 'DAMAGED' WHERE id = tst_loc('ICN01','A-02-01-1') $$, '42501');
