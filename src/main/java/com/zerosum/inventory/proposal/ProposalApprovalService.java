package com.zerosum.inventory.proposal;

import com.zerosum.inventory.domain.PostingOutcome;
import com.zerosum.inventory.domain.Posted;
import com.zerosum.inventory.domain.PreconditionFailed;
import com.zerosum.inventory.domain.WarehouseSkuObservation;
import com.zerosum.inventory.posting.PostingLineInput;
import com.zerosum.inventory.repository.ProposalRepository;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 제안 승인 코어. 승인 순서는 docs/07-ai-integration.md 제안 실행 규칙 의사 코드, db/04-harness.sql
 * tst_approve_proposal과 대응한다.
 *
 * <p><b>STALE은 예외가 아니라 결과 값이다.</b> basis 재검증 실패를 예외로 던지면 Spring 트랜잭션이
 * 롤백 전용으로 표시되어 STALE 기록 자체가 사라진다 — {@link com.zerosum.inventory.posting.PostingService}가
 * {@link PreconditionFailed}를 예외가 아니라 결과로 돌려주는 설계를 그대로 잇는다.
 */
@Service
public class ProposalApprovalService {

    private final ProposalRepository proposalRepo;
    private final ProposalCommandExecutor executor;
    private final double tolerancePct;

    public ProposalApprovalService(ProposalRepository proposalRepo, ProposalCommandExecutor executor,
            // 허용 오차는 기본값을 두지 않는다 — zerosum.count.tolerance와 같은 규칙으로 운영 설정에서만 온다.
            @Value("${zerosum.proposal.basis.tolerance-pct}") double tolerancePct) {
        this.proposalRepo = proposalRepo;
        this.executor = executor;
        this.tolerancePct = tolerancePct;
    }

    @Transactional
    public ApprovalOutcome approve(long proposalId, String approver) {
        // ① 제안 행 잠금 — 승인 연타·동시 승인을 직렬화한다
        ProposalRepository.LockedProposal proposal = proposalRepo.lockForUpdate(proposalId);

        // ② 이미 결정된 제안이면 아무것도 하지 않는다
        if (!"PENDING".equals(proposal.status())) {
            return new AlreadyDecided(proposal.id(), proposal.status());
        }

        // ③ 만료됨
        if (proposal.expired()) {
            proposalRepo.markExpired(proposal.id(), approver);
            return new ProposalExpired(proposal.id());
        }

        BasisRecheck recheck = new BasisRecheck(tolerancePct);

        // ④ 잠글 수 없는 관측값(warehouse_sku 스코프)을 포스팅 전에 비교한다 — 잔액 행을 잠근 뒤 집계 뷰를
        // 읽으면 잠금을 쥔 채 추가 읽기를 하게 되기 때문이다.
        List<WarehouseSkuObservation> currentWarehouseSku = proposalRepo
                .currentWarehouseSku(proposal.warehouseSkuObservations());
        if (!recheck.warehouseSkuObservationsHold(proposal.warehouseSkuObservations(), currentWarehouseSku)) {
            proposalRepo.markStale(proposal.id(), approver);
            return new Stale(proposal.id());
        }

        // ⑤ 포스팅. PostingGateway를 거치지 않는다 — 재시도는 ProposalGateway가 트랜잭션 바깥에서 감싼다.
        List<PostingLineInput> lines = proposalRepo.payloadLines(proposal.id()).stream()
                .map(l -> new PostingLineInput(l.warehouseCode(), l.locationCode(), l.skuCode(), l.lotNo(), l.qty()))
                .toList();
        String reasonCode = proposalRepo.reasonCodeOf(proposal.id());

        // ⑥ 잔액 행 관측값 비교는 잠근 직후에(포스팅 서비스 안에서) 실행되는 선행 조건으로 넘긴다
        PostingOutcome outcome = executor.execute(proposal.id(), proposal.proposalType(), reasonCode, lines, approver,
                recheck.balanceObservationsHold(proposal.balanceObservations()));

        // ⑦ 결과 분기
        return switch (outcome) {
            case Posted posted -> {
                proposalRepo.markExecuted(proposal.id(), approver, posted.txnId());
                yield new Executed(proposal.id(), posted.txnId());
            }
            case PreconditionFailed ignored -> {
                proposalRepo.markStale(proposal.id(), approver);
                yield new Stale(proposal.id());
            }
        };
    }
}
