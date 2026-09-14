package com.zerosum.inventory.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@link OutboxRelayService#relayOnce()}를 주기적으로 부르는 얇은 껍데기. 배치 본체는 그냥 호출 가능한
 * 서비스 메서드로 두고 스케줄러는 여기서만 관여한다 — 테스트가 스케줄러 발화를 기다리면 느리고
 * 불안정해지기 때문이다. 테스트는 {@code zerosum.outbox.relay.enabled=false}로 이 빈 자체를 꺼두고
 * (AbstractIntegrationTest) relayOnce()를 직접 호출한다.
 */
@Component
@ConditionalOnProperty(prefix = "zerosum.outbox.relay", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayScheduler {

    private final OutboxRelayService relayService;

    OutboxRelayScheduler(OutboxRelayService relayService) {
        this.relayService = relayService;
    }

    @Scheduled(fixedDelayString = "${zerosum.outbox.relay.interval-ms:5000}")
    public void relay() {
        relayService.relayOnce();
    }
}
