package com.zerosum.inventory.domain;

/** basis_snapshot의 balance 스코프 관측값 — 잔액 행 하나의 승인 시점 재검증 기준. */
public record BalanceObservation(long balanceId, int availableQty) {
}
