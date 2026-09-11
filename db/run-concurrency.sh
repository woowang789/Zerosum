#!/usr/bin/env bash
# ══ G. 동시성 (여러 커넥션을 병렬로 띄워 잠금 규칙을 확인한다) ══
set -uo pipefail
C=zerosum-pg
T=$(mktemp -d)
q()  { docker exec -i "$C" psql -U "$1" -d zerosum -tAq -c "$2"; }
run(){ docker exec -i "$C" psql -U app_rw -d zerosum -tAq -f - < "$1"; }

echo "── 준비: 동시성 테스트용 재고 입고"
q migrator "SELECT tst_post('receipt:CONC-SETUP-1','RECEIPT','system:seed',
  '[{\"wh\":\"ICN01\",\"loc\":\"V-SUPPLIER\",\"sku\":\"SKU-100002\",\"lot\":\"DEFAULT\",\"qty\":-50},
    {\"wh\":\"ICN01\",\"loc\":\"A-02-01-1\",\"sku\":\"SKU-100002\",\"lot\":\"DEFAULT\",\"qty\":50}]'::jsonb,'PO','PO-CONC-1');
 SELECT tst_post('receipt:CONC-SETUP-2','RECEIPT','system:seed',
  '[{\"wh\":\"ICN01\",\"loc\":\"V-SUPPLIER\",\"sku\":\"SKU-200001\",\"lot\":\"L20260820-A\",\"qty\":-200},
    {\"wh\":\"ICN01\",\"loc\":\"A-01-01-1\",\"sku\":\"SKU-200001\",\"lot\":\"L20260820-A\",\"qty\":100},
    {\"wh\":\"ICN01\",\"loc\":\"A-01-02-1\",\"sku\":\"SKU-200001\",\"lot\":\"L20260820-A\",\"qty\":100}]'::jsonb,'PO','PO-CONC-2');" >/dev/null

# ── G01. 가용 50개에 200번 동시 할당 → 정확히 50건만 성공해야 한다
echo "── G01: 가용 50개에 20커넥션 × 10회 = 200회 동시 할당"
for w in $(seq 1 20); do
  : > "$T/g01-$w.sql"
  for i in $(seq 1 10); do
    echo "SELECT tst_allocate('alloc:CONC-$w-$i','ORD-CONC-$w-$i','ICN01','SKU-100002',1,FALSE);" >> "$T/g01-$w.sql"
  done
done
seq 1 20 | xargs -P 20 -I{} bash -c "docker exec -i $C psql -U app_rw -d zerosum -tAq -f - < $T/g01-{}.sql" >/dev/null 2>"$T/g01.err"
G01_OK=$(q migrator "SELECT count(*) FROM allocation WHERE order_line_ref LIKE 'ORD-CONC-%' AND status='ACTIVE'")
G01_QTY=$(q migrator "SELECT allocated_qty FROM stock_balance WHERE location_id=tst_loc('ICN01','A-02-01-1')
            AND sku_id=(SELECT id FROM sku WHERE code='SKU-100002')")
G01_DL=$(grep -c 'deadlock detected' "$T/g01.err" || true)

# ── G02. 같은 멱등 키로 10번 동시 출고 → 거래는 1건
echo "── G02: 같은 멱등 키로 10커넥션 동시 출고"
cat > "$T/g02.sql" <<'SQL'
SELECT tst_post('ship:ORD-CONC-IDEM-1:1','SHIPMENT','user:park.jh',
  '[{"wh":"ICN01","loc":"A-01-02-1","sku":"SKU-200001","lot":"L20260820-A","qty":-3},
    {"wh":"ICN01","loc":"V-CUSTOMER","sku":"SKU-200001","lot":"L20260820-A","qty":3}]'::jsonb,
  'ORDER','ORD-CONC-IDEM');
SQL
seq 1 10 | xargs -P 10 -I{} bash -c "docker exec -i $C psql -U app_rw -d zerosum -tAq -f - < $T/g02.sql" >/dev/null 2>"$T/g02.err"
G02_TXN=$(q migrator "SELECT count(*) FROM inventory_txn WHERE idem_key='ship:ORD-CONC-IDEM-1:1'")
G02_QTY=$(q migrator "SELECT on_hand_qty FROM stock_balance WHERE location_id=tst_loc('ICN01','A-01-02-1')
            AND lot_id=(SELECT id FROM lot WHERE lot_no='L20260820-A')")

# ── G03. A→B와 B→A를 동시에 반복 → 데드락 0건, 두 로케이션 합계 불변
echo "── G03: 반대 방향 이동 10커넥션 × 20회 동시"
for w in $(seq 1 10); do
  : > "$T/g03-$w.sql"
  if (( w % 2 == 0 )); then FROM=A-01-01-1; TO=A-01-02-1; else FROM=A-01-02-1; TO=A-01-01-1; fi
  for i in $(seq 1 20); do
    cat >> "$T/g03-$w.sql" <<SQL
SELECT tst_post('move:CONC-$w-$i','MOVE','user:park.jh',
  '[{"wh":"ICN01","loc":"$FROM","sku":"SKU-200001","lot":"L20260820-A","qty":-1},
    {"wh":"ICN01","loc":"$TO",  "sku":"SKU-200001","lot":"L20260820-A","qty":1}]'::jsonb,'WORK_ORDER','WO-CONC');
SQL
  done
done
G03_BEFORE=$(q migrator "SELECT sum(on_hand_qty) FROM stock_balance WHERE sku_id=(SELECT id FROM sku WHERE code='SKU-200001')")
seq 1 10 | xargs -P 10 -I{} bash -c "docker exec -i $C psql -U app_rw -d zerosum -tAq -f - < $T/g03-{}.sql" >/dev/null 2>"$T/g03.err"
G03_AFTER=$(q migrator "SELECT sum(on_hand_qty) FROM stock_balance WHERE sku_id=(SELECT id FROM sku WHERE code='SKU-200001')")
G03_DL=$(grep -c 'deadlock detected' "$T/g03.err" || true)
G03_OK=$(q migrator "SELECT count(*) FROM inventory_txn WHERE idem_key LIKE 'move:CONC-%'")

# ── G04. 같은 로케이션에 실사를 10번 동시 시작 → 세션 1개
echo "── G04: 같은 로케이션에 10커넥션 동시 실사 시작"
for w in $(seq 1 10); do
  echo "SELECT tst_count_start('count:CONC-$w','ICN01','A-02-01-1','user:lee.sh');" > "$T/g04-$w.sql"
done
seq 1 10 | xargs -P 10 -I{} bash -c "docker exec -i $C psql -U app_rw -d zerosum -tAq -f - < $T/g04-{}.sql" >/dev/null 2>"$T/g04.err"
G04_S=$(q migrator "SELECT count(*) FROM count_session WHERE location_id=tst_loc('ICN01','A-02-01-1') AND status IN ('OPEN','REVIEW')")
q app_rw "SELECT tst_count_abandon('count:CONC-cleanup', tst_session('ICN01','A-02-01-1'), 'user:lee.sh')" >/dev/null

# ── 결과 기록
q migrator "
INSERT INTO tst_result (case_id, title, expect, outcome, detail) VALUES
 ('UC-G01','가용 50개에 200회 동시 할당 → 성공 건수','= 50',
   CASE WHEN $G01_OK=50 THEN 'PASS' ELSE 'FAIL' END, '성공 $G01_OK건'),
 ('UC-G01','할당량 합계가 가용과 정확히 일치','= 50',
   CASE WHEN $G01_QTY=50 THEN 'PASS' ELSE 'FAIL' END, '할당량 $G01_QTY'),
 ('UC-G01','데드락 발생 없음','= 0',
   CASE WHEN $G01_DL=0 THEN 'PASS' ELSE 'FAIL' END, '데드락 $G01_DL건'),
 ('UC-G02','같은 멱등 키 10회 동시 출고 → 거래 1건','= 1',
   CASE WHEN $G02_TXN=1 THEN 'PASS' ELSE 'FAIL' END, '거래 $G02_TXN건'),
 ('UC-G02','재고는 3개만 줄었다 (중복 차감 없음)','= 97',
   CASE WHEN $G02_QTY=97 THEN 'PASS' ELSE 'FAIL' END, '실재고 $G02_QTY'),
 ('UC-G03','반대 방향 이동 200회 동시 → 데드락 0건','= 0',
   CASE WHEN $G03_DL=0 THEN 'PASS' ELSE 'FAIL' END, '데드락 $G03_DL건, 성공 $G03_OK건'),
 ('UC-G03','두 로케이션 합계 불변','= $G03_BEFORE',
   CASE WHEN $G03_AFTER=$G03_BEFORE THEN 'PASS' ELSE 'FAIL' END, '이전 $G03_BEFORE / 이후 $G03_AFTER'),
 ('UC-G04','같은 로케이션 동시 실사 시작 10회 → 세션 1개','= 1',
   CASE WHEN $G04_S=1 THEN 'PASS' ELSE 'FAIL' END, '진행 중 세션 ${G04_S}개');" >/dev/null
rm -rf "$T"
echo "── G 완료"
