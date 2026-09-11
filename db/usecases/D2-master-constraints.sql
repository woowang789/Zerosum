-- ══ D2. 마스터 데이터 제약 (app_admin 권한으로 실행) ═════════
-- 마스터 쓰기는 app_rw가 아니라 app_admin의 일이다.
SELECT tst_run('UC-D16a', 'app_admin이 신규 보관 로케이션을 만든다 → 허용', $$
  INSERT INTO location (warehouse_id, code, location_type)
  VALUES ((SELECT id FROM warehouse WHERE code='ICN01'), 'C-01-01-1', 'STORAGE') $$);
SELECT tst_run('UC-D16b', '[DB] 정의되지 않은 로케이션 유형 → CHECK', $$
  INSERT INTO location (warehouse_id, code, location_type)
  VALUES ((SELECT id FROM warehouse WHERE code='ICN01'), 'X-99-99-9', 'FREEZER') $$, '23514');
SELECT tst_run('UC-D19', '[DB] 같은 창고에 같은 로케이션 코드 → UNIQUE', $$
  INSERT INTO location (warehouse_id, code, location_type)
  VALUES ((SELECT id FROM warehouse WHERE code='ICN01'), 'A-01-01-1', 'STORAGE') $$, '23505');
SELECT tst_run('UC-D20', '[DB] 같은 SKU에 같은 로트 번호 → UNIQUE', $$
  INSERT INTO lot (sku_id, lot_managed, lot_no, expiry_date)
  VALUES ((SELECT id FROM sku WHERE code='SKU-200002'), TRUE, 'L20260901-A', DATE '2026-11-01') $$, '23505');

-- I11. 로트 미관리 SKU의 로트는 DEFAULT 하나뿐 (검증 결과 반영된 제약)
SELECT tst_run('UC-D23', '[DB] 로트 미관리 SKU에 DEFAULT 로트를 하나 더 → UNIQUE', $$
  INSERT INTO lot (sku_id, lot_managed, lot_no, expiry_date)
  VALUES ((SELECT id FROM sku WHERE code='SKU-100001'), FALSE, 'DEFAULT', NULL) $$,
  'lot_sku_id_lot_no_key');
SELECT tst_run('UC-D24', '[DB] 로트 미관리 SKU에 DEFAULT가 아닌 로트 번호 → CHECK', $$
  INSERT INTO lot (sku_id, lot_managed, lot_no, expiry_date)
  VALUES ((SELECT id FROM sku WHERE code='SKU-300001'), FALSE, 'L20260911-Y', NULL) $$, '23514');
SELECT tst_run('UC-D25', '[DB] 로트의 lot_managed가 SKU와 다르다 → 복합 외래키', $$
  INSERT INTO lot (sku_id, lot_managed, lot_no, expiry_date)
  VALUES ((SELECT id FROM sku WHERE code='SKU-100002'), TRUE, 'L20260911-Z', DATE '2027-01-01') $$, '23503');
SELECT tst_assert('UC-D23', '로트 미관리 SKU 3종은 여전히 로트가 1개씩',
  (SELECT count(*)::INT FROM (SELECT s.id FROM sku s JOIN lot l ON l.sku_id=s.id
     WHERE NOT s.lot_managed GROUP BY s.id HAVING count(*) <> 1) x), 0);
