package com.zerosum.inventory.proposal;

/** 제안 생성 결과. 새로 만들었으면 {@link ProposalCreated}, 재시도로 걸러졌으면 {@link ProposalDuplicate}. */
public sealed interface CreateProposalOutcome permits ProposalCreated, ProposalDuplicate {
}
