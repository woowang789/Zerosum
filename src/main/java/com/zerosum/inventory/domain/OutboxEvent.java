package com.zerosum.inventory.domain;

/**
 * 아웃박스 이벤트 한 건 (미발행 조회 결과). repository(조회)와 outbox(소비) 양쪽이 함께 쓰는 값 타입이라
 * domain에 둔다 (docs/06-events-reconciliation.md).
 */
public record OutboxEvent(long id, String eventType, String partitionKey, String payload) {
}
