package com.zerosum.inventory.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.zerosum.inventory.domain.OutboxEvent;
import com.zerosum.inventory.repository.OutboxRepository;
import com.zerosum.inventory.support.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 아웃박스 릴레이 (docs/06-events-reconciliation.md). 스케줄러는 테스트에서 꺼져 있으므로
 * (AbstractIntegrationTest, zerosum.outbox.relay.enabled=false) OutboxRelayService#relayOnce()를
 * 직접 호출한다. 소비자는 Spring 빈을 쓰지 않고 이 클래스와 같은 패키지라 접근 가능한 package-private
 * 생성자로 직접 new해 시나리오별로 원하는 동작(성공 기록·실패)을 주입한다.
 */
class OutboxRelayTest extends AbstractIntegrationTest {

    @Autowired
    private OutboxRepository outboxRepository;

    @Test
    void relayPublishesUnpublishedEventAndFillsPublishedAt() {
        postAndExpectSuccess(request("receipt:OUTBOX-REL-0001:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -10),
                line("ICN01", "RCV-01", "SKU-100001", "DEFAULT", 10)));
        assertThat(unpublishedCount()).isEqualTo(1);

        List<OutboxEvent> received = new ArrayList<>();
        OutboxRelayService relay = new OutboxRelayService(outboxRepository, received::add);

        assertThat(relay.relayOnce()).isEqualTo(1);
        assertThat(received).hasSize(1);
        assertThat(received.get(0).eventType()).isEqualTo("StockPosted");
        assertThat(unpublishedCount()).as("발행 후 published_at이 채워진다").isZero();
    }

    @Test
    void alreadyPublishedEventIsNotSentAgain() {
        postAndExpectSuccess(request("receipt:OUTBOX-REL-0002:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -5),
                line("ICN01", "RCV-01", "SKU-100001", "DEFAULT", 5)));

        AtomicInteger calls = new AtomicInteger();
        OutboxRelayService relay = new OutboxRelayService(outboxRepository, e -> calls.incrementAndGet());

        assertThat(relay.relayOnce()).isEqualTo(1);
        assertThat(relay.relayOnce()).as("이미 발행된 이벤트는 다시 넘기지 않는다").isZero();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void consumerFailureLeavesPublishedAtNullForRetryOnNextCycle() {
        postAndExpectSuccess(request("receipt:OUTBOX-REL-0003:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -7),
                line("ICN01", "RCV-01", "SKU-100001", "DEFAULT", 7)));

        AtomicInteger attempts = new AtomicInteger();
        OutboxRelayService failingRelay = new OutboxRelayService(outboxRepository, event -> {
            attempts.incrementAndGet();
            throw new RuntimeException("소비자 장애 시뮬레이션");
        });

        assertThat(failingRelay.relayOnce()).as("실패한 이벤트는 발행 성공 수에 포함되지 않는다").isZero();
        assertThat(attempts.get()).isEqualTo(1);
        assertThat(unpublishedCount()).as("published_at이 갱신되지 않아 다음 사이클 대상으로 남는다").isEqualTo(1);

        // 다음 사이클: 이번에는 성공하는 소비자로 재시도하면 같은 이벤트가 다시 전달된다
        List<OutboxEvent> received = new ArrayList<>();
        OutboxRelayService retryRelay = new OutboxRelayService(outboxRepository, received::add);
        assertThat(retryRelay.relayOnce()).isEqualTo(1);
        assertThat(received).hasSize(1);
        assertThat(unpublishedCount()).isZero();
    }

    private int unpublishedCount() {
        return jdbcClient.sql("SELECT count(*) FROM outbox_event WHERE published_at IS NULL")
                .query(Integer.class)
                .single();
    }
}
