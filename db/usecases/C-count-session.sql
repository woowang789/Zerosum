-- ══ C. 실사 세션 ═════════════════════════════════════════════
-- B-01-01-1의 티셔츠 30개를 대상으로 순환 실사를 돈다.

SELECT tst_run('UC-C01', '실사 시작 (B-01-01-1)', $$
  SELECT tst_count_start('count:CC-20260911-0001', 'ICN01', 'B-01-01-1', 'user:lee.sh') $$);
SELECT tst_assert('UC-C01', '로케이션에 진행 중 세션이 표시된다',
  (tst_session('ICN01','B-01-01-1') IS NOT NULL), TRUE);

SELECT tst_run('UC-C02', '실사 중 로케이션에서 출고 시도 → 거절', $$
  SELECT tst_post('ship:ORD-20260911-0011-1:1', 'SHIPMENT', 'user:park.jh',
    '[{"wh":"ICN01","loc":"B-01-01-1","sku":"SKU-100001","lot":"DEFAULT","qty":-5},
      {"wh":"ICN01","loc":"V-CUSTOMER","sku":"SKU-100001","lot":"DEFAULT","qty":5}]'::jsonb,
    'ORDER', 'ORD-20260911-0011') $$, 'COUNT_IN_PROGRESS');

SELECT tst_run('UC-C03', '같은 로케이션에 실사를 또 시작 → 거절', $$
  SELECT tst_count_start('count:CC-20260911-0002', 'ICN01', 'B-01-01-1', 'user:jo.mk') $$,
  'uq_count_session_active');

SELECT tst_run('UC-C04', '30개 그대로 세어 제출 → 차이 없음', $$
  SELECT tst_count_submit('count:CC-20260911-0001:submit', tst_session('ICN01','B-01-01-1'),
    '[{"sku":"SKU-100001","lot":"DEFAULT","qty":30}]'::jsonb, 'user:lee.sh', 1, 0.05) $$);
SELECT tst_assert('UC-C04', '세션 CONFIRMED',
  (SELECT status FROM count_session WHERE id = (SELECT max(id) FROM count_session)), 'CONFIRMED');
SELECT tst_assert('UC-C04', '로케이션 표시 해제', tst_session('ICN01','B-01-01-1'), NULL::BIGINT);
SELECT tst_assert('UC-C04', '차이가 없으므로 정정 거래도 없다',
  (SELECT resolution_txn_id FROM count_session WHERE id = (SELECT max(id) FROM count_session)), NULL::BIGINT);

-- C05. 차이 1개는 허용 오차(1개 이하이면서 5% 이하) 안이라 같은 트랜잭션에서 자동 정정된다
SELECT tst_run('UC-C05a', '실사 재시작', $$
  SELECT tst_count_start('count:CC-20260911-0003', 'ICN01', 'B-01-01-1', 'user:lee.sh') $$);
SELECT tst_run('UC-C05b', '29개로 세어 제출 (차이 −1, 오차 이내) → 자동 정정', $$
  SELECT tst_count_submit('count:CC-20260911-0003:submit', tst_session('ICN01','B-01-01-1'),
    '[{"sku":"SKU-100001","lot":"DEFAULT","qty":29}]'::jsonb, 'user:lee.sh', 1, 0.05) $$);
SELECT tst_assert('UC-C05b', '실재고 29로 정정', tst_qty('ICN01','B-01-01-1','SKU-100001'), 29);
SELECT tst_assert('UC-C05b', '정정 거래가 ADJUSTMENT로 남는다',
  (SELECT t.txn_type FROM inventory_txn t
    WHERE t.id = (SELECT resolution_txn_id FROM count_session WHERE id=(SELECT max(id) FROM count_session))),
  'ADJUSTMENT');
SELECT tst_assert('UC-C05b', '로케이션 표시 해제', tst_session('ICN01','B-01-01-1'), NULL::BIGINT);

-- C06~C08. 오차를 넘는 차이는 사람이 검토한다
SELECT tst_run('UC-C06a', '실사 재시작', $$
  SELECT tst_count_start('count:CC-20260911-0004', 'ICN01', 'B-01-01-1', 'user:lee.sh') $$);
SELECT tst_run('UC-C06b', '24개로 세어 제출 (차이 −5, 오차 초과) → REVIEW', $$
  SELECT tst_count_submit('count:CC-20260911-0004:submit', tst_session('ICN01','B-01-01-1'),
    '[{"sku":"SKU-100001","lot":"DEFAULT","qty":24}]'::jsonb, 'user:lee.sh', 1, 0.05) $$);
SELECT tst_assert('UC-C06b', '세션 REVIEW',
  (SELECT status FROM count_session WHERE id=(SELECT max(id) FROM count_session)), 'REVIEW');
SELECT tst_assert('UC-C06b', '실재고는 아직 29 (정정 전)', tst_qty('ICN01','B-01-01-1','SKU-100001'), 29);
SELECT tst_assert('UC-C06b', 'COUNT_VARIANCE 이슈가 열린다',
  (SELECT count(*)::INT FROM inventory_issue WHERE issue_type='COUNT_VARIANCE' AND status='OPEN'), 1);
SELECT tst_run('UC-C07', 'REVIEW 상태 로케이션에서 출고 → 거절', $$
  SELECT tst_post('ship:ORD-20260911-0012-1:1', 'SHIPMENT', 'user:park.jh',
    '[{"wh":"ICN01","loc":"B-01-01-1","sku":"SKU-100001","lot":"DEFAULT","qty":-5},
      {"wh":"ICN01","loc":"V-CUSTOMER","sku":"SKU-100001","lot":"DEFAULT","qty":5}]'::jsonb,
    'ORDER', 'ORD-20260911-0012') $$, 'COUNT_IN_PROGRESS');
SELECT tst_run('UC-C08', '검토 후 정정 승인 → 표시 해제 + 조정 포스팅 + CONFIRMED', $$
  SELECT tst_count_resolve('count:CC-20260911-0004:resolve',
    (SELECT id FROM count_session WHERE status='REVIEW'), 'user:choi.dw') $$);
SELECT tst_assert('UC-C08', '실재고 24로 정정', tst_qty('ICN01','B-01-01-1','SKU-100001'), 24);
SELECT tst_assert('UC-C08', '로케이션 표시 해제', tst_session('ICN01','B-01-01-1'), NULL::BIGINT);
SELECT tst_assert('UC-C08', '이슈 RESOLVED',
  (SELECT count(*)::INT FROM inventory_issue WHERE issue_type='COUNT_VARIANCE' AND status='OPEN'), 0);

-- C09. 재실사가 필요해 중단
SELECT tst_run('UC-C09a', '실사 시작 후 중단', $$
  SELECT tst_count_start('count:CC-20260911-0005', 'ICN01', 'B-01-01-1', 'user:lee.sh') $$);
SELECT tst_run('UC-C09b', '정정 없이 ABANDONED', $$
  SELECT tst_count_abandon('count:CC-20260911-0005:abandon', tst_session('ICN01','B-01-01-1'), 'user:lee.sh') $$);
SELECT tst_assert('UC-C09b', '표시 해제', tst_session('ICN01','B-01-01-1'), NULL::BIGINT);
SELECT tst_assert('UC-C09b', '실재고 변화 없음', tst_qty('ICN01','B-01-01-1','SKU-100001'), 24);

-- C10~C12. 실사 중 재고는 판매 가능 수량에서 빠지고 할당 후보에서도 빠진다
SELECT tst_run('UC-C10', 'A-01-01-1 실사 시작 (티셔츠 52개가 실사 중이 된다)', $$
  SELECT tst_count_start('count:CC-20260911-0006', 'ICN01', 'A-01-01-1', 'user:lee.sh') $$);
SELECT tst_assert('UC-C10', '판매 가능 수량 24 (B-01-01-1만)',
  (SELECT sellable_qty::INT FROM v_sellable_stock v JOIN warehouse w ON w.id=v.warehouse_id
    JOIN sku s ON s.id=v.sku_id WHERE w.code='ICN01' AND s.code='SKU-100001'), 24);
SELECT tst_assert('UC-C10', '실사 중 수량 52로 분리 표기',
  (SELECT in_count_qty::INT FROM v_sellable_stock v JOIN warehouse w ON w.id=v.warehouse_id
    JOIN sku s ON s.id=v.sku_id WHERE w.code='ICN01' AND s.code='SKU-100001'), 52);
SELECT tst_run('UC-C11', 'allowInCount=false로 30개 할당 → 가용 24개뿐이라 부족', $$
  SELECT tst_allocate('alloc:ORD-20260911-0013-1', 'ORD-20260911-0013-1', 'ICN01', 'SKU-100001', 30, FALSE) $$,
  'INSUFFICIENT_STOCK');
SELECT tst_run('UC-C12', 'allowInCount=true로 30개 할당 → 실사 중 로케이션도 후보에 포함', $$
  SELECT tst_allocate('alloc:ORD-20260911-0014-1', 'ORD-20260911-0014-1', 'ICN01', 'SKU-100001', 30, TRUE) $$);
SELECT tst_run('UC-C12b', '정리: 할당 해제 후 실사 중단', $$
  SELECT tst_release_alloc('release:ORD-20260911-0014-1', tst_alloc_ids('ORD-20260911-0014-1'));
  SELECT tst_count_abandon('count:CC-20260911-0006:abandon', tst_session('ICN01','A-01-01-1'), 'user:lee.sh') $$);
