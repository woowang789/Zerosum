package com.zerosum.inventory.proposal;

/** 이미 PENDING이 아닌 제안을 다시 승인 시도함 (연타·재승인). 아무것도 하지 않고 현재 상태만 돌려준다. */
public record AlreadyDecided(long proposalId, String status) implements ApprovalOutcome {
}
