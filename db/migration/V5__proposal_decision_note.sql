-- 5단계: 제안 결정 메모
--
-- V1~V4는 이미 적용된 마이그레이션이라 한 글자도 고치지 않는다.
-- 배경은 docs/08-testing-roadmap.md 4단계 완료 기준(승인 화면), docs/07-ai-integration.md.

-- 승인·거부 양쪽에서 사람이 남기는 메모. inventory_issue.resolution_note(V3)와 같은 목적이다.
-- 지금까지는 이 칸이 없어 거부 사유를 어디에도 적을 수 없었는데, docs/07-ai-integration.md는
-- "원 제안은 REJECTED로 닫고 수정본을 새 제안으로 올려야 AI가 무엇을 틀렸는지 기록이 남는다"고
-- 적어뒀다 — 적을 칸이 없으면 그 기록도 없다. 승인 쪽에도 같은 컬럼을 두는 것은 REJECTED 전용으로
-- 나누지 않기 위해서다(승인에도 참고 메모를 남길 수 있다).
ALTER TABLE action_proposal
  ADD COLUMN decision_note TEXT;
