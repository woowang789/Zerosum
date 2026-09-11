-- ══ F. 정합 검증 배치 (migrator 권한으로 실행) ═══════════════
-- 하루치 거래를 모두 처리한 뒤 다섯 가지 검증이 모두 0건인지 본다.
SELECT tst_assert('UC-F01', chk || ' 불일치 건수', cnt::INT, 0) FROM tst_recon();

-- F02. 잔액을 일부러 틀어놓으면 ①이 잡아낸다
SELECT tst_run('UC-F02a', '잔액 투영을 몰래 3개 늘린다 (버그 시뮬레이션)', $$
  UPDATE stock_balance SET on_hand_qty = on_hand_qty + 3 WHERE location_id = tst_loc('ICN01','B-01-01-1') $$);
SELECT tst_assert('UC-F02b', '① 잔액 투영 검증이 불일치를 잡는다',
  (SELECT cnt::INT FROM tst_recon() WHERE chk like '①%') > 0, TRUE);
SELECT tst_run('UC-F02c', '원복', $$
  UPDATE stock_balance SET on_hand_qty = on_hand_qty - 3 WHERE location_id = tst_loc('ICN01','B-01-01-1') $$);

-- F03. 할당량을 틀어놓으면 ②가 잡아낸다
SELECT tst_run('UC-F03a', 'ACTIVE 할당 없이 할당량만 올린다', $$
  UPDATE stock_balance SET allocated_qty = 1
   WHERE location_id = tst_loc('ICN01','DMG-01') AND on_hand_qty > 0 $$);
SELECT tst_assert('UC-F03b', '② 할당 검증이 불일치를 잡는다',
  (SELECT cnt::INT FROM tst_recon() WHERE chk like '②%') > 0, TRUE);
SELECT tst_run('UC-F03c', '원복', $$
  UPDATE stock_balance SET allocated_qty = 0 WHERE location_id = tst_loc('ICN01','DMG-01') $$);

-- F04. 원장 체인을 끊어놓으면 ③이 최초 불일치 거래를 짚는다
SELECT tst_run('UC-F04a', '원장의 잔량 기록을 하나 조작한다', $$
  UPDATE inventory_ledger_entry SET on_hand_after = on_hand_after + 5
   WHERE id = (SELECT min(e.id) FROM inventory_ledger_entry e
               WHERE e.location_id = tst_loc('ICN01','A-01-01-1') AND e.on_hand_after IS NOT NULL) $$);
SELECT tst_assert('UC-F04b', '③ 체인 검증이 끊어진 지점을 찾는다',
  (SELECT cnt::INT FROM tst_recon() WHERE chk like '③%') > 0, TRUE);
SELECT tst_assert('UC-F04c', '끊긴 지점이 조작한 그 원장 줄을 가리킨다',
  (SELECT ledger_entry_id FROM tst_chain_break()),
  (SELECT min(e.id) FROM inventory_ledger_entry e
    WHERE e.location_id = tst_loc('ICN01','A-01-01-1') AND e.on_hand_after IS NOT NULL));
SELECT tst_run('UC-F04d', '원복', $$
  UPDATE inventory_ledger_entry SET on_hand_after = on_hand_after - 5
   WHERE id = (SELECT min(e.id) FROM inventory_ledger_entry e
               WHERE e.location_id = tst_loc('ICN01','A-01-01-1') AND e.on_hand_after IS NOT NULL) $$);

-- F05. 가상 로케이션 잔액 행은 DB가 막지 않는다. 배치가 잡는 대상이다
SELECT tst_run('UC-F05a', '공급사 가상 로케이션에 잔액 행을 만든다', $$
  INSERT INTO stock_balance (warehouse_id, location_id, sku_id, lot_id)
  VALUES ((SELECT id FROM warehouse WHERE code='ICN01'), tst_loc('ICN01','V-SUPPLIER'),
          (SELECT id FROM sku WHERE code='SKU-100001'),
          (SELECT id FROM lot WHERE lot_no='DEFAULT' AND sku_id=(SELECT id FROM sku WHERE code='SKU-100001'))) $$);
SELECT tst_assert('UC-F05b', '④ 가상 로케이션 검증이 잡는다',
  (SELECT cnt::INT FROM tst_recon() WHERE chk like '④%'), 1);
SELECT tst_run('UC-F05c', '원복', $$
  DELETE FROM stock_balance WHERE location_id = tst_loc('ICN01','V-SUPPLIER') $$);

-- F06. 실사 표시와 세션이 어긋나면 ⑤가 잡는다
SELECT tst_run('UC-F06a', '세션 없이 로케이션 표시만 남은 상태를 만든다', $$
  INSERT INTO count_session (location_id, started_by) VALUES (tst_loc('ICN01','A-01-02-1'), 'user:lee.sh');
  UPDATE location SET count_session_id = (SELECT max(id) FROM count_session) WHERE id = tst_loc('ICN01','A-01-02-1');
  UPDATE count_session SET status='ABANDONED', closed_by='user:lee.sh', closed_at=now()
   WHERE id = (SELECT max(id) FROM count_session) $$);
SELECT tst_assert('UC-F06b', '⑤ 실사 표시 검증이 잡는다',
  (SELECT cnt::INT FROM tst_recon() WHERE chk like '⑤%'), 1);
SELECT tst_run('UC-F06c', '원복', $$
  UPDATE location SET count_session_id = NULL WHERE id = tst_loc('ICN01','A-01-02-1') $$);

-- F07. 원복 후 다시 전부 0건
SELECT tst_assert('UC-F07', chk || ' 최종 불일치 건수', cnt::INT, 0) FROM tst_recon();
