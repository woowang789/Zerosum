package com.zerosum.inventory.allocation;

import com.zerosum.inventory.domain.AllocationId;
import java.time.Duration;
import java.util.List;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Component;

/**
 * 할당 서비스의 공개 진입점. 데드락·직렬화 오류 재시도를 @Transactional 경계 바깥에서 감싼다
 * ({@link com.zerosum.inventory.posting.PostingGateway}와 같은 구조 — docs/04-write-path.md 구현 메모).
 */
@Component
public class AllocationGateway {

    private final AllocationService allocationService;
    private final RetryTemplate retryTemplate;

    public AllocationGateway(AllocationService allocationService) {
        this.allocationService = allocationService;
        RetryPolicy retryPolicy = RetryPolicy.builder()
                .maxRetries(3)
                .delay(Duration.ofMillis(20))
                .jitter(Duration.ofMillis(20))
                .includes(ConcurrencyFailureException.class)
                .build();
        this.retryTemplate = new RetryTemplate(retryPolicy);
    }

    public AllocationResult allocate(AllocateRequest request) {
        return retryTemplate.invoke(() -> allocationService.allocate(request));
    }

    public int release(String idemKey, List<AllocationId> allocationIds) {
        return retryTemplate.invoke(() -> allocationService.release(idemKey, allocationIds));
    }
}
