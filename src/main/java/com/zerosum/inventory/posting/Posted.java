package com.zerosum.inventory.posting;

/** 정상 포스팅됨. {@code txnId}는 새로 만들어졌거나(최초 실행), 멱등 재요청으로 되돌려준 기존 거래 id다. */
public record Posted(long txnId) implements PostingOutcome {
}
