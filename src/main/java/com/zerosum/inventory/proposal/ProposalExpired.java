package com.zerosum.inventory.proposal;

/** 유효 기간이 지난 제안을 승인 시도함. 실행하지 않고 EXPIRED로 닫는다. */
public record ProposalExpired(long proposalId) implements ApprovalOutcome {
}
