-- ── 마스터 목 데이터 ──────────────────────────────────────────
-- 국내 이커머스 3PL 센터 두 곳을 가정한다. 코드 체계는 실제 WMS에서 흔한 형식을 따랐다.
--   창고   : {공항/지역 3자}{일련 2자}          예) ICN01
--   로케이션: {존}-{통로 2자}-{랙 2자}-{단 1자}  예) A-01-03-2
--   SKU    : SKU-{6자리}                       예) SKU-200001
--   로트   : L{입고일 YYYYMMDD}-{일련 1자}      예) L20260820-A

INSERT INTO warehouse (code, name) VALUES
  ('ICN01', '인천 1센터'),
  ('YIT01', '용인 1센터');

-- 로케이션: 물리 + 가상. 가상 로케이션은 창고마다 하나씩 둔다(location.warehouse_id가 NOT NULL).
INSERT INTO location (warehouse_id, code, location_type)
SELECT w.id, v.code, v.location_type
FROM warehouse w
CROSS JOIN (VALUES
  ('RCV-01',      'RECEIVING'),
  ('RCV-02',      'RECEIVING'),
  ('A-01-01-1',   'STORAGE'),
  ('A-01-01-2',   'STORAGE'),
  ('A-01-02-1',   'STORAGE'),
  ('A-02-01-1',   'STORAGE'),
  ('B-01-01-1',   'STORAGE'),
  ('RTN-01',      'RETURN_HOLD'),
  ('DMG-01',      'DAMAGED'),
  ('TRS-01',      'TRANSIT'),
  ('V-SUPPLIER',  'V_SUPPLIER'),
  ('V-CUSTOMER',  'V_CUSTOMER'),
  ('V-ADJUST',    'V_ADJUSTMENT')
) AS v(code, location_type);

INSERT INTO sku (code, name, lot_managed) VALUES
  ('SKU-100001', '스탠다드 코튼 반팔티 화이트 M', FALSE),
  ('SKU-100002', '스탠다드 코튼 반팔티 블랙 L',   FALSE),
  ('SKU-200001', '유기농 아몬드 200g',            TRUE),
  ('SKU-200002', '콜드브루 원액 1L',              TRUE),
  ('SKU-300001', '무선 마우스 MX-210 블랙',       FALSE);

-- 로트 미관리 SKU도 DEFAULT 로트를 하나 갖는다 (재고 키에 NULL을 넣지 않기 위해)
INSERT INTO lot (sku_id, lot_managed, lot_no, expiry_date)
SELECT id, FALSE, 'DEFAULT', NULL FROM sku WHERE lot_managed = FALSE;

-- 로트 관리 SKU. 오늘이 2026-09-11이라는 전제로 유통기한을 잡았다.
INSERT INTO lot (sku_id, lot_managed, lot_no, expiry_date) VALUES
  ((SELECT id FROM sku WHERE code = 'SKU-200001'), TRUE, 'L20260820-A', DATE '2027-02-20'),
  ((SELECT id FROM sku WHERE code = 'SKU-200001'), TRUE, 'L20260905-B', DATE '2027-03-05'),
  ((SELECT id FROM sku WHERE code = 'SKU-200002'), TRUE, 'L20260901-A', DATE '2026-10-01'),  -- 임박
  ((SELECT id FROM sku WHERE code = 'SKU-200002'), TRUE, 'L20260910-B', DATE '2026-10-10');
