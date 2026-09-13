package com.zerosum.inventory.outbox;

import com.zerosum.inventory.domain.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 기본 소비자. 이상 탐지·수요 예측 같은 실제 소비자는 범위 밖이므로 (docs/06-events-reconciliation.md)
 * 받은 이벤트를 기록만 하고 아무 것도 하지 않는다.
 */
@Component
public class LoggingOutboxConsumer implements OutboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(LoggingOutboxConsumer.class);

    @Override
    public void onEvent(OutboxEvent event) {
        log.info("아웃박스 이벤트 수신: id={}, eventType={}, partitionKey={}",
                event.id(), event.eventType(), event.partitionKey());
    }
}
