-- 3단계: inventory_issue 확장과 인덱스
--
-- V1__init.sql·V2__grants_and_views.sql은 이미 적용된 마이그레이션이라 한 글자도 고치지 않는다.
-- 배경은 docs/08-testing-roadmap.md 구현 순서 3단계, docs/06-events-reconciliation.md 참고.

-- 실사 차이 이슈를 detail->>'countSessionId' JSONB 문자열 비교로 다시 찾아야 했고 외래키도 없었다.
ALTER TABLE inventory_issue
  ADD COLUMN count_session_id BIGINT REFERENCES count_session (id);

-- 처리 이력: count_session의 started_by/closed_by/closed_at, action_proposal의 decided_by/decided_at과
-- 같은 목적이다. 상태가 OPEN/ACKED/RESOLVED 3단계인데 누가 언제 처리했는지가 남지 않았다.
-- resolution_note는 resolved_txn_id가 NULL인 종결(예: ③ CHAIN_BREAK처럼 app_rw 권한으로는 원장을
-- 못 고쳐 코드 수정만으로 종결하는 경우)의 이유를 남기는 자리다.
ALTER TABLE inventory_issue
  ADD COLUMN acked_by        VARCHAR(100),
  ADD COLUMN acked_at        TIMESTAMPTZ,
  ADD COLUMN resolved_by     VARCHAR(100),
  ADD COLUMN resolved_at     TIMESTAMPTZ,
  ADD COLUMN resolution_note TEXT;

-- 상태와 처리 이력 컬럼의 짝을 강제한다 (count_session·action_proposal과 같은 패턴). resolved_at을
-- 빠뜨린 UPDATE는 조용히 채우지 않고 거절한다 — 이 설계는 "DB가 틀린 데이터를 막는다"이지 "조용히
-- 고친다"가 아니다(ReconciliationService의 배치 자동 보정 금지와 같은 원칙). db/04-harness.sql의
-- tst_count_resolve도 이 컬럼을 직접 채우도록 함께 고쳤다.
-- OPEN → RESOLVED 직행(ACKED를 건너뜀)을 허용하므로 RESOLVED에 acked_at을 요구하지는 않는다.
ALTER TABLE inventory_issue
  ADD CHECK ((status = 'RESOLVED') = (resolved_at IS NOT NULL)),
  ADD CHECK (status <> 'OPEN' OR acked_at IS NULL);

-- 지금은 기본키 말고 인덱스가 없다. "봐야 할 이슈"(OPEN·ACKED) 목록을 최신순으로 조회하는 경로를 위한
-- 부분 인덱스 — allocation의 idx_alloc_active, count_session의 uq_count_session_active와 같은 패턴이다.
CREATE INDEX idx_issue_open_detected ON inventory_issue (status, detected_at)
  WHERE status IN ('OPEN', 'ACKED');

-- app_rw 권한: V2__grants_and_views.sql이 이미 inventory_issue 테이블 전체에
-- GRANT SELECT, INSERT, UPDATE를 부여했다. PostgreSQL의 테이블 단위 GRANT는 이후 추가되는 컬럼에도
-- 그대로 적용되므로(컬럼별로 다시 줄 필요가 있는 경우는 위 GRANT가 테이블 전체가 아니라 특정 컬럼만
-- 지정했을 때뿐이다 — location.count_session_id가 그 예), 여기서 새로 GRANT할 것은 없다.
