package com.zerosum.inventory.count;

/**
 * 차이가 없었거나(정정 거래 없음) 오차 이내 차이라 같은 트랜잭션에서 자동 정정까지 끝났다.
 * {@code resolutionTxnId}는 차이가 전혀 없었으면 널이다.
 */
public record Confirmed(Long resolutionTxnId) implements CountSubmitOutcome {
}
