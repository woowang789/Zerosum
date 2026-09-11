package com.zerosum.inventory.count;

/**
 * v_sellable_stock 한 행. docs/05-count-session.md 판매 가능 수량 — 실사 중인 로케이션의 (실재고 − 할당량)은
 * {@code sellableQty}에서 빠지고 {@code inCountQty}로 분리된다. 정책 강제(채널 노출)는 범위 밖이며
 * 코어는 이 둘을 나눠 제공하기만 한다.
 */
public record SellableStock(int sellableQty, int inCountQty) {
}
