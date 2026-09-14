package com.zerosum.inventory.proposal;

/** basis 재검증 실패. 원장에는 아무것도 쓰지 않고 제안만 STALE로 커밋한다 (예외가 아니라 결과 값이다). */
public record Stale(long proposalId) implements ApprovalOutcome {
}
