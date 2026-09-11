-- ══ E. AI 제안과 승인 ════════════════════════════════════════
-- 에이전트가 "만료 임박 로트를 픽존으로 당기자"는 이동을 제안한다.

SELECT tst_run('UC-E01', '에이전트가 이동 제안 생성', $$
  SELECT tst_create_proposal('MOVE',
    '{"txnType":"MOVE","entries":[
       {"wh":"ICN01","loc":"A-01-01-2","sku":"SKU-200002","lot":"L20260910-B","qty":-20},
       {"wh":"ICN01","loc":"A-01-02-1","sku":"SKU-200002","lot":"L20260910-B","qty":20}]}'::jsonb,
    '만료 10-10 로트가 상단 랙에 있어 소진이 느리다. 픽존 A-01-02-1로 20개 당길 것을 제안한다.',
    'agent:rebalancer',
    '[{"wh":"ICN01","loc":"A-01-01-2","sku":"SKU-200002","lot":"L20260910-B"}]'::jsonb) $$);
SELECT tst_assert('UC-E01', 'basis_snapshot은 서버가 DB에서 직접 채운다 (가용 70)',
  (SELECT (basis_snapshot->'observations'->0->>'available_qty')::INT
     FROM action_proposal ORDER BY id DESC LIMIT 1), 70);
SELECT tst_assert('UC-E01', '상태 PENDING',
  (SELECT status FROM action_proposal ORDER BY id DESC LIMIT 1), 'PENDING');

SELECT tst_run('UC-E02', '에이전트가 재시도로 같은 제안을 다시 넣는다 → 대기 중 제안은 하나만', $$
  INSERT INTO action_proposal (proposal_type, command_payload, basis_snapshot, rationale, proposed_by, expires_at)
  SELECT proposal_type, command_payload, basis_snapshot, rationale, proposed_by, expires_at
    FROM action_proposal ORDER BY id DESC LIMIT 1 $$, 'uq_proposal_pending');

SELECT tst_run('UC-E03', '사람이 승인 → 실행', $$
  SELECT tst_approve_proposal((SELECT max(id) FROM action_proposal), 'user:choi.dw') $$);
SELECT tst_assert('UC-E03', '제안 EXECUTED',
  (SELECT status FROM action_proposal ORDER BY id DESC LIMIT 1), 'EXECUTED');
SELECT tst_assert('UC-E03', '실행 거래의 주체는 승인자 (AI가 아니다)',
  (SELECT t.actor_id FROM inventory_txn t
    JOIN action_proposal p ON p.executed_txn_id = t.id ORDER BY p.id DESC LIMIT 1), 'user:choi.dw');
SELECT tst_assert('UC-E03', '거래가 제안을 역참조한다',
  (SELECT t.proposal_id = p.id FROM inventory_txn t
    JOIN action_proposal p ON p.executed_txn_id = t.id ORDER BY p.id DESC LIMIT 1), TRUE);
SELECT tst_assert('UC-E03', '픽존 A-01-02-1에 20개 도착',
  tst_qty('ICN01','A-01-02-1','SKU-200002','L20260910-B'), 20);

SELECT tst_run('UC-E04', '이미 실행된 제안을 다시 승인 → 아무 일도 일어나지 않는다', $$
  SELECT tst_approve_proposal((SELECT max(id) FROM action_proposal), 'user:choi.dw') $$);
SELECT tst_assert('UC-E04', '거래는 여전히 1건 (멱등 키 proposal:{id})',
  (SELECT count(*)::INT FROM inventory_txn WHERE idem_key like 'proposal:%'), 1);

-- E05. 제안 후 재고가 크게 변하면 실행하지 않고 STALE로 닫는다
SELECT tst_run('UC-E05a', '새 제안 생성 (근거: A-01-01-2 가용 50)', $$
  SELECT tst_create_proposal('MOVE',
    '{"txnType":"MOVE","entries":[
       {"wh":"ICN01","loc":"A-01-01-2","sku":"SKU-200002","lot":"L20260910-B","qty":-30},
       {"wh":"ICN01","loc":"B-01-01-1","sku":"SKU-200002","lot":"L20260910-B","qty":30}]}'::jsonb,
    '픽존 보충', 'agent:rebalancer',
    '[{"wh":"ICN01","loc":"A-01-01-2","sku":"SKU-200002","lot":"L20260910-B"}]'::jsonb) $$);
SELECT tst_run('UC-E05b', '승인 전에 40개가 출고되어 근거가 무너진다', $$
  SELECT tst_post('ship:ORD-20260911-0030-1:1', 'SHIPMENT', 'user:park.jh',
    '[{"wh":"ICN01","loc":"A-01-01-2", "sku":"SKU-200002","lot":"L20260910-B","qty":-40},
      {"wh":"ICN01","loc":"V-CUSTOMER","sku":"SKU-200002","lot":"L20260910-B","qty":40}]'::jsonb,
    'ORDER', 'ORD-20260911-0030') $$);
SELECT tst_run('UC-E05c', '승인 시도 → STALE', $$
  SELECT tst_approve_proposal((SELECT max(id) FROM action_proposal), 'user:choi.dw') $$);
SELECT tst_assert('UC-E05c', '제안 STALE',
  (SELECT status FROM action_proposal ORDER BY id DESC LIMIT 1), 'STALE');
SELECT tst_assert('UC-E05c', 'STALE 판정은 커밋되어 남는다 (예외로 롤백되지 않았다)',
  (SELECT decided_by FROM action_proposal ORDER BY id DESC LIMIT 1), 'user:choi.dw');
SELECT tst_assert('UC-E05c', '원장에는 아무것도 쓰이지 않았다',
  (SELECT count(*)::INT FROM inventory_txn WHERE idem_key = 'proposal:' || (SELECT max(id) FROM action_proposal)), 0);

-- E06. 유효기간이 지난 제안
SELECT tst_run('UC-E06a', '이미 만료된 제안 생성', $$
  SELECT tst_create_proposal('MOVE',
    '{"txnType":"MOVE","entries":[
       {"wh":"ICN01","loc":"A-01-01-2","sku":"SKU-200002","lot":"L20260910-B","qty":-5},
       {"wh":"ICN01","loc":"B-01-01-1","sku":"SKU-200002","lot":"L20260910-B","qty":5}]}'::jsonb,
    '만료 테스트', 'agent:rebalancer', '[]'::jsonb, interval '-1 minute') $$);
SELECT tst_run('UC-E06b', '만료된 제안 승인 → EXPIRED', $$
  SELECT tst_approve_proposal((SELECT max(id) FROM action_proposal), 'user:choi.dw') $$);
SELECT tst_assert('UC-E06b', '제안 EXPIRED',
  (SELECT status FROM action_proposal ORDER BY id DESC LIMIT 1), 'EXPIRED');

-- 제안 상태 제약 (app_rw 권한으로 확인)
SELECT tst_run('UC-D21', '[DB] EXECUTED인데 실행 거래가 없는 제안 → CHECK', $$
  INSERT INTO action_proposal (proposal_type, command_payload, basis_snapshot, rationale, proposed_by, status, decided_by, decided_at, expires_at)
  VALUES ('MOVE', '{}', '{}', '근거', 'agent:rebalancer', 'EXECUTED', 'user:choi.dw', now(), now() + interval '1 hour') $$,
  '23514');
