package com.zerosum.inventory.proposal;

import java.time.Duration;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Component;

/**
 * 제안 생성·승인의 공개 진입점. {@link PostingGateway}와 같은 구조로, 승인 쪽 데드락·직렬화 오류 재시도를
 * {@code @Transactional} 경계 바깥에서 감싼다 — {@link ProposalApprovalService#approve}가 그 자체로
 * {@code @Transactional} 프록시 빈의 public 메서드라 자기 호출 문제가 없고, 여기서는 그 호출을 재시도로
 * 감싸기만 한다. 생성({@link #create})은 ai_proposer가 트랜잭션 없이 autocommit 단문만 쓰므로 재시도가 필요
 * 없어 그대로 위임한다.
 *
 * @see com.zerosum.inventory.posting.PostingGateway
 */
@Component
public class ProposalGateway {

    private final ProposalCreationService creationService;
    private final ProposalApprovalService approvalService;
    private final RetryTemplate retryTemplate;

    public ProposalGateway(ProposalCreationService creationService, ProposalApprovalService approvalService) {
        this.creationService = creationService;
        this.approvalService = approvalService;
        RetryPolicy retryPolicy = RetryPolicy.builder()
                .maxRetries(3)
                .delay(Duration.ofMillis(20))
                .jitter(Duration.ofMillis(20))
                .includes(ConcurrencyFailureException.class)
                .build();
        this.retryTemplate = new RetryTemplate(retryPolicy);
    }

    public CreateProposalOutcome create(CreateProposalRequest request, String allowedWarehouseCode) {
        return creationService.create(request, allowedWarehouseCode);
    }

    public ApprovalOutcome approve(long proposalId, String approver) {
        return retryTemplate.invoke(() -> approvalService.approve(proposalId, approver));
    }

    /** 거부도 approve()와 같은 이유로 재시도로 감싼다 — {@link ProposalApprovalService#reject}도 프록시 빈의 public 메서드다. */
    public void reject(long proposalId, String rejectedBy, String note) {
        retryTemplate.invoke(() -> approvalService.reject(proposalId, rejectedBy, note));
    }
}
