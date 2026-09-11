package com.zerosum.inventory.count;

import java.util.List;

/**
 * 실사 제출 커맨드. db/04-harness.sql tst_count_submit의 입력과 대응한다. 허용 오차(tolQty/tolPct)는
 * 여기 담지 않는다 — 호출자가 매번 정하는 값이 아니라 운영 설정에서 주입되는 값이라 CountSessionService가
 * 생성자로 받아 쓴다 (05-count-session.md: "허용 오차는 코드에 박지 않고 설정으로 뺀다").
 */
public record SubmitCountRequest(String idemKey, long sessionId, List<CountLineInput> lines, String submittedBy) {
}
