package com.zerosum.inventory.reconciliation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@link ReconciliationService#runOnce()}를 주기적으로 부르는 얇은 껍데기. 배치 본체는 그냥 호출
 * 가능한 서비스 메서드로 두고 스케줄러는 여기서만 관여한다. 테스트는
 * {@code zerosum.reconciliation.enabled=false}로 이 빈 자체를 꺼두고(AbstractIntegrationTest)
 * runOnce()를 직접 호출한다.
 */
@Component
@ConditionalOnProperty(prefix = "zerosum.reconciliation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ReconciliationScheduler {

    private final ReconciliationService service;

    ReconciliationScheduler(ReconciliationService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${zerosum.reconciliation.interval-ms:300000}")
    public void run() {
        service.runOnce();
    }
}
