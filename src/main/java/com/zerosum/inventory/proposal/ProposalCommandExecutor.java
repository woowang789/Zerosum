package com.zerosum.inventory.proposal;

import com.zerosum.inventory.domain.LockedBalances;
import com.zerosum.inventory.domain.PostingOutcome;
import com.zerosum.inventory.domain.ProposalException;
import com.zerosum.inventory.posting.PostingLineInput;
import com.zerosum.inventory.posting.PostingRequest;
import com.zerosum.inventory.posting.PostingService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;

/**
 * 제안 승인 커맨드를 포스팅 서비스로 실행한다. 트랜잭션을 열지 않는다 — 승인 트랜잭션
 * ({@link ProposalApprovalService#approve})에 실려 돈다. {@code PostingGateway}를 거치지 않는 이유는
 * 승인이 이미 {@code @Transactional}이라 게이트웨이의 재시도 루프가 진행 중인 트랜잭션 안에서 돌게 되기
 * 때문이다 — 재시도는 {@link ProposalGateway}가 트랜잭션 바깥에서 감싼다.
 */
@Component
public class ProposalCommandExecutor {

    // 4단계 범위: TRANSFER·RECEIPT_DRAFT·RESOLVE_COUNT는 아직 지원하지 않는다 (ProposalCreationService와 같은 제약).
    public static final Set<String> SUPPORTED_TYPES = Set.of("MOVE", "ADJUSTMENT");

    private final PostingService postingService;

    public ProposalCommandExecutor(PostingService postingService) {
        this.postingService = postingService;
    }

    /** 멱등 키 {@code "proposal:" + proposalId}, source_type {@code "PROPOSAL"}로 포스팅 서비스를 직접 호출한다. */
    public PostingOutcome execute(long proposalId, String txnType, String reasonCode, List<PostingLineInput> lines,
            String approver, Predicate<LockedBalances> precondition) {
        if (!SUPPORTED_TYPES.contains(txnType)) {
            throw new ProposalException("UNSUPPORTED_PROPOSAL_TYPE",
                    "승인 단계에서 지원하지 않는 proposalType: %s".formatted(txnType));
        }
        String idemKey = "proposal:" + proposalId;
        PostingRequest request = new PostingRequest(idemKey, txnType, "USER", approver, lines, "PROPOSAL", idemKey,
                reasonCode, null, Instant.now(), List.of(), proposalId);
        return postingService.post(request, precondition);
    }
}
