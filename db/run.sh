#!/usr/bin/env bash
# 스키마 검증 전체 실행: 컨테이너를 새로 띄우고 스키마·목 데이터·유스케이스를 순서대로 돌린다.
set -euo pipefail
D="$(cd "$(dirname "$0")" && pwd)"
C=zerosum-pg
IMG=postgres:16

echo "══ 1. PostgreSQL 16 컨테이너 기동 ═════════════════════════"
docker rm -f "$C" >/dev/null 2>&1 || true
docker run -d --name "$C" -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=zerosum -p 55433:5432 "$IMG" >/dev/null
for _ in $(seq 1 60); do docker exec "$C" psql -U postgres -d zerosum -tAc 'select 1' >/dev/null 2>&1 && break; done
docker exec "$C" psql -U postgres -d zerosum -tAc 'select version()'

echo "══ 2. 스키마·목 데이터 적재 ═══════════════════════════════"
apply() { docker exec -i "$C" psql -v ON_ERROR_STOP=1 -U "$1" -d zerosum -q < "$D/$2"; echo "   ✔ $2"; }
apply postgres 00-roles.sql
apply migrator  migration/V1__init.sql
apply migrator  migration/V2__grants_and_views.sql
apply migrator  migration/V3__issue_columns_and_indexes.sql
apply app_admin 03-seed.sql
apply migrator  04-harness.sql

echo "══ 3. 유스케이스 실행 ═════════════════════════════════════"
uc() { docker exec -i "$C" psql -U "$1" -d zerosum -tAq -f - < "$D/usecases/$2" >/dev/null 2>&1; echo "   ✔ $2 ($1)"; }
uc app_rw      A-inbound-outbound.sql
uc app_rw      B-idempotency.sql
uc app_rw      C-count-session.sql
uc app_rw      D-invariants.sql
uc app_admin   D2-master-constraints.sql
uc app_rw      E-proposal.sql
uc ai_ro       E2-ai-permissions.sql
uc ai_proposer E3-proposer-permissions.sql
bash "$D/run-concurrency.sh"
uc migrator    F-reconciliation.sql      # 동시성까지 끝난 최종 상태에서 정합 검증

echo "══ 4. 결과 ════════════════════════════════════════════════"
docker exec "$C" psql -U migrator -d zerosum -c \
 "SELECT substring(case_id from 4 for 1) AS 그룹, count(*) AS 건수,
         count(*) FILTER (WHERE outcome='PASS') AS pass,
         count(*) FILTER (WHERE outcome='FAIL') AS fail
    FROM tst_result GROUP BY 1 ORDER BY 1;"
docker exec "$C" psql -U migrator -d zerosum -c \
 "SELECT case_id, title, detail FROM tst_result WHERE outcome='FAIL' ORDER BY seq;"
docker exec "$C" psql -U migrator -d zerosum -tAc \
 "SELECT CASE WHEN count(*) FILTER (WHERE outcome='FAIL')=0
              THEN '전체 ' || count(*) || '건 PASS'
              ELSE count(*) FILTER (WHERE outcome='FAIL') || '건 FAIL' END FROM tst_result;"
