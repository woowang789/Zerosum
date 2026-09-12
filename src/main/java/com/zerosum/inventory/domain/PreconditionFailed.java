package com.zerosum.inventory.domain;

/** 잠금 직후 선행 조건이 실패했다. 원장에는 아무것도 쓰지 않는다 (예외가 아니라 결과로 반환). */
public record PreconditionFailed(BalanceSnapshot current) implements PostingOutcome {
}
