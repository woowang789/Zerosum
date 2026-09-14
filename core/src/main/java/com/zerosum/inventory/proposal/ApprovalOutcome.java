package com.zerosum.inventory.proposal;

/** 제안 승인 결과. docs/07-ai-integration.md 제안 실행 규칙의 결과 타입. */
public sealed interface ApprovalOutcome permits Executed, AlreadyDecided, Stale, ProposalExpired {
}
