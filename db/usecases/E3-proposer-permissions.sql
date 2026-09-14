-- ══ E3. AI 권한 경계 (ai_proposer 계정으로 실행) ═════════════
SELECT tst_run('UC-E12', 'ai_proposer가 제안을 만든다 → 허용', $$
  INSERT INTO action_proposal (proposal_type, command_payload, basis_snapshot, rationale, proposed_by, expires_at)
  VALUES ('MOVE','{"txnType":"MOVE","entries":[]}','{"observations":[]}','권한 경계 확인','agent:rebalancer',
          now() + interval '1 hour') $$);
SELECT tst_run('UC-E13', 'ai_proposer가 제안을 승인 상태로 바꾼다 → 거부', $$
  UPDATE action_proposal SET status='EXECUTED' WHERE id = (SELECT max(id) FROM action_proposal) $$, '42501');
SELECT tst_run('UC-E14', 'ai_proposer가 재고를 바꾼다 → 거부', $$
  UPDATE stock_balance SET on_hand_qty = 0 WHERE id = 1 $$, '42501');
SELECT tst_run('UC-E15', 'ai_proposer가 원장에 직접 쓴다 → 거부', $$
  INSERT INTO inventory_ledger_entry (txn_id, warehouse_id, location_id, sku_id, lot_id, qty_delta)
  VALUES (1,1,1,1,1,5) $$, '42501');

-- ── inventory_issue 컬럼 단위 GRANT (4단계: AI 원인 분석) ──────────
SELECT tst_run('UC-E19', 'ai_proposer가 ai_analysis만 UPDATE → 허용', $$
  UPDATE inventory_issue SET ai_analysis = '{}'::JSONB WHERE id = (SELECT max(id) FROM inventory_issue) $$);
SELECT tst_run('UC-E20', 'ai_proposer가 status를 함께 UPDATE하려 한다 → 거부', $$
  UPDATE inventory_issue SET ai_analysis = '{}'::JSONB, status = 'ACKED'
  WHERE id = (SELECT max(id) FROM inventory_issue) $$, '42501');
SELECT tst_run('UC-E21', 'ai_proposer가 acked_by를 읽으려 한다 → 거부', $$
  SELECT acked_by FROM inventory_issue $$, '42501');
SELECT tst_run('UC-E22', 'ai_proposer가 v_balance_basis를 읽는다 → 허용', $$
  SELECT count(*) FROM v_balance_basis $$);
