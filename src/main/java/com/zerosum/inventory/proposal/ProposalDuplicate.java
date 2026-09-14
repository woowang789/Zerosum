package com.zerosum.inventory.proposal;

/** uq_proposal_pending과 충돌해 새로 만들지 않고 되돌려준 기존 PENDING 제안의 id. */
public record ProposalDuplicate(long existingProposalId) implements CreateProposalOutcome {
}
