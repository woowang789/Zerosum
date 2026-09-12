package com.zerosum.inventory.posting;

import com.zerosum.inventory.domain.LockedBalances;
import com.zerosum.inventory.domain.PostingOutcome;
import java.time.Duration;
import java.util.function.Predicate;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Component;

/**
 * 포스팅 서비스의 공개 진입점. 데드락·직렬화 오류 재시도를 @Transactional 경계 바깥에서 감싼다
 * (docs/04-write-path.md 구현 메모). {@link PostingService#post}는 그 자체로 @Transactional 프록시 빈의
 * public 메서드라 자기 호출 문제가 없고, 여기서는 그 호출을 재시도로 감싸기만 한다.
 * 재시도마다 새 트랜잭션으로 처음부터 다시 실행되며, 멱등 키 선점이 각 시도의 첫 단계이므로
 * 재시도가 중복 실행으로 이어지지 않는다.
 *
 * <p>재시도 대상은 {@link ConcurrencyFailureException}(Postgres 데드락 40P01·직렬화 실패 40001을
 * Spring이 번역한 예외들의 공통 상위 타입)뿐이다. INSUFFICIENT_STOCK 같은 {@link PostingException}은
 * 재시도해도 결과가 바뀌지 않는 비즈니스 오류이므로 그대로 호출자에게 전파된다.
 */
@Component
public class PostingGateway {

    private final PostingService postingService;
    private final RetryTemplate retryTemplate;

    public PostingGateway(PostingService postingService) {
        this.postingService = postingService;
        RetryPolicy retryPolicy = RetryPolicy.builder()
                .maxRetries(3)
                .delay(Duration.ofMillis(20))
                .jitter(Duration.ofMillis(20))
                .includes(ConcurrencyFailureException.class)
                .build();
        this.retryTemplate = new RetryTemplate(retryPolicy);
    }

    public PostingOutcome post(PostingRequest request, Predicate<LockedBalances> precondition) {
        return retryTemplate.invoke(() -> postingService.post(request, precondition));
    }
}
