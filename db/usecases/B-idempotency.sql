-- ══ B. 멱등성 ════════════════════════════════════════════════
-- B01. 네트워크 타임아웃으로 같은 요청을 다시 보냈다
SELECT tst_run('UC-B01', '같은 멱등 키로 적치를 재요청', $$
  SELECT tst_post('move:WO-20260911-0007', 'MOVE', 'user:park.jh',
    '[{"wh":"ICN01","loc":"RCV-01",   "sku":"SKU-100001","lot":"DEFAULT","qty":-120},
      {"wh":"ICN01","loc":"A-01-01-1","sku":"SKU-100001","lot":"DEFAULT","qty":120}]'::jsonb,
    'WORK_ORDER', 'WO-20260911-0007') $$);
SELECT tst_assert('UC-B01', '거래는 여전히 1건',
  (SELECT count(*)::INT FROM inventory_txn WHERE idem_key='move:WO-20260911-0007'), 1);
SELECT tst_assert('UC-B01', '재고도 그대로 (이중 적치 없음)', tst_qty('ICN01','RCV-01','SKU-100001'), 0);

-- B02. 같은 키인데 수량이 다르다 → 호출자 버그이므로 거절해야 한다
SELECT tst_run('UC-B02', '같은 멱등 키에 다른 수량 → 409', $$
  SELECT tst_post('move:WO-20260911-0007', 'MOVE', 'user:park.jh',
    '[{"wh":"ICN01","loc":"RCV-01",   "sku":"SKU-100001","lot":"DEFAULT","qty":-99},
      {"wh":"ICN01","loc":"A-01-01-1","sku":"SKU-100001","lot":"DEFAULT","qty":99}]'::jsonb,
    'WORK_ORDER', 'WO-20260911-0007') $$, 'IDEM_CONFLICT_409');

-- B03. 재고 부족으로 롤백된 키는 기록도 함께 사라져, 재고가 채워진 뒤 재시도하면 성공한다
SELECT tst_run('UC-B03a', '재고 없는 로케이션에서 출고 시도 → 실패', $$
  SELECT tst_post('ship:ORD-20260911-0009-1:1', 'SHIPMENT', 'user:park.jh',
    '[{"wh":"ICN01","loc":"A-02-01-1","sku":"SKU-300001","lot":"DEFAULT","qty":-5},
      {"wh":"ICN01","loc":"V-CUSTOMER","sku":"SKU-300001","lot":"DEFAULT","qty":5}]'::jsonb,
    'ORDER', 'ORD-20260911-0009') $$, 'NO_STOCK');
SELECT tst_assert('UC-B03a', '멱등 키 기록도 롤백되어 남지 않는다',
  (SELECT count(*)::INT FROM idempotency_record WHERE idem_key='ship:ORD-20260911-0009-1:1'), 0);
SELECT tst_run('UC-B03b', '마우스 20개 입고·적치', $$
  SELECT tst_post('receipt:PO-20260911-0101-1:1', 'RECEIPT', 'user:kim.ys',
    '[{"wh":"ICN01","loc":"V-SUPPLIER","sku":"SKU-300001","lot":"DEFAULT","qty":-20},
      {"wh":"ICN01","loc":"A-02-01-1","sku":"SKU-300001","lot":"DEFAULT","qty":20}]'::jsonb,
    'PO', 'PO-20260911-0101') $$);
SELECT tst_run('UC-B03c', '같은 멱등 키로 출고 재시도 → 이번엔 성공', $$
  SELECT tst_post('ship:ORD-20260911-0009-1:1', 'SHIPMENT', 'user:park.jh',
    '[{"wh":"ICN01","loc":"A-02-01-1","sku":"SKU-300001","lot":"DEFAULT","qty":-5},
      {"wh":"ICN01","loc":"V-CUSTOMER","sku":"SKU-300001","lot":"DEFAULT","qty":5}]'::jsonb,
    'ORDER', 'ORD-20260911-0009') $$);
SELECT tst_assert('UC-B03c', 'A-02-01-1 실재고 15', tst_qty('ICN01','A-02-01-1','SKU-300001'), 15);
