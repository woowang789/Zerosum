package com.zerosum.inventory.outbox;

import com.zerosum.inventory.domain.OutboxEvent;

/**
 * 아웃박스 이벤트 소비자. 처음부터 Kafka를 두지 않고, 릴레이가 같은 애플리케이션 안의 구현을 직접
 * 호출하는 것으로 시작한다 (docs/06-events-reconciliation.md) — 나중에 발행 대상만 Kafka로 바꾸면 된다.
 * 이상 탐지·수요 예측 같은 실제 소비자는 범위 밖이라 기본 구현({@link LoggingOutboxConsumer})만 둔다.
 *
 * <p>예외를 던지면 그 이벤트는 발행 실패로 간주되어 published_at이 갱신되지 않고, 다음 릴레이 사이클에
 * 다시 전달된다 — 전달 보장은 최소 한 번이므로 구현은 이벤트 id로 중복을 걸러야 한다.
 */
public interface OutboxConsumer {

    void onEvent(OutboxEvent event);
}
