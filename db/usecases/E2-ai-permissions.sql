-- ══ E2. AI 권한 경계 (ai_ro 계정으로 실행) ═══════════════════
SELECT tst_run('UC-E07', 'ai_ro가 조회 뷰를 읽는다 → 허용', $$ SELECT count(*) FROM v_available_stock $$);
SELECT tst_run('UC-E08', 'ai_ro가 잔액 테이블을 직접 읽는다 → 거부', $$ SELECT count(*) FROM stock_balance $$, '42501');
SELECT tst_run('UC-E09', 'ai_ro가 원장을 직접 읽는다 → 거부', $$ SELECT count(*) FROM inventory_ledger_entry $$, '42501');
SELECT tst_run('UC-E10', 'ai_ro가 재고를 직접 바꾼다 → 거부', $$
  UPDATE stock_balance SET on_hand_qty = 0 WHERE id = 1 $$, '42501');
SELECT tst_run('UC-E11', 'ai_ro가 제안을 만든다 → 거부 (제안은 ai_proposer의 일)', $$
  INSERT INTO action_proposal (proposal_type, command_payload, basis_snapshot, rationale, proposed_by, expires_at)
  VALUES ('MOVE','{}','{}','x','agent:x', now() + interval '1 hour') $$, '42501');

-- ── V4 신규 뷰(4단계: AI 조회 도구) ────────────────────────────
SELECT tst_run('UC-E16', 'ai_ro가 새 뷰 3개(v_ledger·v_open_issue·v_count_history)를 읽는다 → 허용', $$
  SELECT (SELECT count(*) FROM v_ledger) + (SELECT count(*) FROM v_open_issue)
       + (SELECT count(*) FROM v_count_history) $$);
SELECT tst_run('UC-E17', 'ai_ro가 inventory_issue를 직접 읽는다 → 거부', $$
  SELECT count(*) FROM inventory_issue $$, '42501');
SELECT tst_run('UC-E18', 'ai_ro가 마스터 테이블(warehouse)을 직접 읽는다 → 거부', $$
  SELECT count(*) FROM warehouse $$, '42501');
