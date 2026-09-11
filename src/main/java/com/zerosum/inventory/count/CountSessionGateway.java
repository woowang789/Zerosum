package com.zerosum.inventory.count;

import java.time.Duration;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Component;

/**
 * 실사 세션 서비스의 공개 진입점. 데드락·직렬화 오류 재시도를 {@code @Transactional} 경계 바깥에서 감싼다
 * ({@link com.zerosum.inventory.posting.PostingGateway}·{@link com.zerosum.inventory.allocation.AllocationGateway}와
 * 같은 구조).
 */
@Component
public class CountSessionGateway {

    private final CountSessionService countSessionService;
    private final RetryTemplate retryTemplate;

    public CountSessionGateway(CountSessionService countSessionService) {
        this.countSessionService = countSessionService;
        RetryPolicy retryPolicy = RetryPolicy.builder()
                .maxRetries(3)
                .delay(Duration.ofMillis(20))
                .jitter(Duration.ofMillis(20))
                .includes(ConcurrencyFailureException.class)
                .build();
        this.retryTemplate = new RetryTemplate(retryPolicy);
    }

    public long start(StartCountRequest request) {
        return retryTemplate.invoke(() -> countSessionService.start(request));
    }

    public CountSubmitOutcome submit(SubmitCountRequest request) {
        return retryTemplate.invoke(() -> countSessionService.submit(request));
    }

    public Long resolve(ResolveCountRequest request) {
        return retryTemplate.invoke(() -> countSessionService.resolve(request));
    }

    public void abandon(AbandonCountRequest request) {
        retryTemplate.invoke(() -> {
            countSessionService.abandon(request);
            return null;
        });
    }
}
