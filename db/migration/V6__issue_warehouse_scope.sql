-- 6단계: 이슈의 창고 범위를 ai_proposer가 볼 수 있게 한다
--
-- V1~V5는 이미 적용된 마이그레이션이라 한 글자도 고치지 않는다.
-- 배경은 docs/07-ai-integration.md(권한 경계, "창고 접근 권한은 도구 내부에서 호출자 기준으로 강제한다").

-- ── 왜 뷰가 필요한가 ─────────────────────────────────────────────────────
-- AI 쓰기 표면 둘(create_proposal의 payload.issueId, write_issue_analysis의 issueId)은 이슈가
-- 호출자 창고의 것인지 대조해야 한다. 그런데 이슈는 location_id를 통해서만 창고에 매이고
-- (inventory_issue → location → warehouse), ai_proposer가 inventory_issue에서 읽을 수 있는 컬럼은
-- (id, status)뿐이다(V4의 컬럼 단위 GRANT) — location_id는 SELECT 목록에도 WHERE에도 넣을 수 없고,
-- location·warehouse 테이블 자체에도 권한이 없다. 즉 지금 권한으로는 "이 이슈가 어느 창고냐"에
-- 답할 방법이 아예 없다.
--
-- 그래서 V4의 v_balance_basis·v_warehouse_sku_basis와 같은 방식으로 푼다: 뷰가 소유자(migrator)
-- 권한으로 조인을 대신 해주고, ai_proposer에는 그 뷰의 SELECT만 준다. 컬럼 단위 GRANT의 원칙을
-- 넓히지 않으려고 이미 있는 v_open_issue를 재사용하지 않았다 — 그 뷰는 detail·ai_analysis·
-- location_id·acked_at까지 노출하는데, 여기서 필요한 사실은 딱 셋이다.
CREATE VIEW v_issue_scope AS
SELECT i.id AS issue_id, i.status, w.code AS warehouse_code
FROM inventory_issue i
JOIN location  loc ON loc.id = i.location_id
JOIN warehouse w   ON w.id   = loc.warehouse_id;

-- location_id가 없는 이슈는 이 뷰에서 빠진다 — 어느 창고에도 매이지 않은 이슈이므로 창고 하나로
-- 범위가 고정된 MCP 서버의 것일 수 없다. (현재 이슈를 여는 경로는 전부 location_id를 채운다:
-- ReconciliationService의 다섯 종류와 CountResultRepository의 COUNT_VARIANCE.)

GRANT SELECT ON v_issue_scope TO ai_proposer;
