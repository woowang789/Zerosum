package com.zerosum.inventory.proposal;

/** 승인 → 포스팅까지 정상 실행됨. {@code txnId}는 새로 만들어진 거래(inventory_txn) id다. */
public record Executed(long proposalId, long txnId) implements ApprovalOutcome {
}
