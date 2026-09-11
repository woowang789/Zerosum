-- 문서의 권한 경계 표를 한 인스턴스에서 재현한다.
-- 검증 환경에는 레플리카가 없으므로 ai_ro도 프라이머리를 본다. 권한 범위는 동일하다.
CREATE ROLE migrator     LOGIN PASSWORD 'migrator';
CREATE ROLE app_admin    LOGIN PASSWORD 'app_admin';   -- 마스터 데이터 관리 화면
CREATE ROLE app_rw       LOGIN PASSWORD 'app_rw';      -- 재고 쓰기 경로
CREATE ROLE ai_ro        LOGIN PASSWORD 'ai_ro';
CREATE ROLE ai_proposer  LOGIN PASSWORD 'ai_proposer';

-- 스키마 소유자는 migrator. 나머지 롤은 부여받은 권한만 갖는다.
ALTER DATABASE zerosum OWNER TO migrator;
GRANT USAGE ON SCHEMA public TO app_admin, app_rw, ai_ro, ai_proposer;
ALTER SCHEMA public OWNER TO migrator;
