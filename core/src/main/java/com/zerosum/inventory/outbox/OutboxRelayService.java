package com.zerosum.inventory.outbox;

import com.zerosum.inventory.domain.OutboxEvent;
import com.zerosum.inventory.repository.OutboxRepository;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 아웃박스 릴레이 한 사이클 (docs/06-events-reconciliation.md). {@code published_at IS NULL}을
 * 커서로 쓴다 — "마지막으로 읽은 id 이후" 방식은 id가 INSERT 시점에 정해지지만 커밋은 늦을 수 있어
 * 작은 id가 영영 건너뛰어질 수 있다.
 *
 * <p>이벤트별로 실패를 가둔다 — 한 이벤트의 소비가 실패해도 나머지는 계속 처리하고, 성공한(=브로커 확인을
 * 받은) id만 모아 한 번에 published_at을 갱신한다. 갱신 전에 죽으면 같은 이벤트가 다시 발행되므로
 * 전달 보장은 최소 한 번이고, 소비자는 이벤트 id로 중복을 걸러야 한다.
 *
 * <p>지금은 단일 인스턴스만 가정해 조회에 {@code FOR UPDATE SKIP LOCKED}를 걸지 않았다 — 여러 인스턴스로
 * 늘리기 전까지는 불필요한 잠금 비용만 늘리고, 늘어날 때 {@link com.zerosum.inventory.repository.OutboxRepository#selectUnpublished}
 * 한 곳만 고치면 된다.
 *
 * <p>스케줄러({@link OutboxRelayScheduler})는 이 클래스의 {@link #relayOnce()}를 주기적으로 부르는
 * 얇은 껍데기일 뿐이다 — 테스트는 스케줄러를 꺼두고 이 메서드를 직접 호출한다.
 */
@Service
public class OutboxRelayService {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayService.class);
    private static final int BATCH_SIZE = 100;

    private final OutboxRepository outboxRepository;
    private final OutboxConsumer consumer;

    OutboxRelayService(OutboxRepository outboxRepository, OutboxConsumer consumer) {
        this.outboxRepository = outboxRepository;
        this.consumer = consumer;
    }

    /** 한 사이클 실행. 새로 발행에 성공한 이벤트 수를 돌려준다 (테스트 검증용). */
    public int relayOnce() {
        List<OutboxEvent> unpublished = outboxRepository.selectUnpublished(BATCH_SIZE);
        List<Long> publishedIds = new ArrayList<>();
        for (OutboxEvent event : unpublished) {
            try {
                consumer.onEvent(event);
                publishedIds.add(event.id());
            } catch (RuntimeException e) {
                log.warn("아웃박스 이벤트 {} 소비 실패, 다음 사이클에 재시도한다", event.id(), e);
            }
        }
        if (!publishedIds.isEmpty()) {
            outboxRepository.markPublished(publishedIds);
        }
        return publishedIds.size();
    }
}
