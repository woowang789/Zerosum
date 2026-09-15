package com.zerosum.inventory.web.proposal;

import com.zerosum.inventory.domain.ProposalException;
import com.zerosum.inventory.proposal.ApprovalOutcome;
import com.zerosum.inventory.proposal.ProposalBasisReviewService;
import com.zerosum.inventory.proposal.ProposalGateway;
import com.zerosum.inventory.repository.ProposalRepository;
import com.zerosum.inventory.web.security.AccessGuard;
import com.zerosum.inventory.web.security.WarehouseScope;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사람이 보는 제안 검토 창구. 목록의 창고 범위는 {@link WarehouseScope} 타입이 강제한다 — 컨트롤러가
 * 원본 파라미터를 읽지 않으므로 창고 검증을 빠뜨릴 수 없다.
 *
 * <p>{@code {id}}만 받는 엔드포인트(상세·승인·거부)는 그 리졸버가 자동으로 막아주지 않는다 — 제안의
 * 창고를 대상에서 직접 읽어 {@link AccessGuard#requireWarehouse}로 대조한다. 승인자·거부자도 요청이
 * 아니라 {@link Authentication}(SecurityContext)에서만 읽는다 — DTO에 그런 필드를 두지 않는다.
 */
@RestController
@RequestMapping("/api/proposals")
public class ProposalController {

    private static final int LIST_LIMIT = 100;

    private final ProposalRepository proposalRepo;
    private final ProposalBasisReviewService basisReviewService;
    private final ProposalGateway proposalGateway;

    ProposalController(ProposalRepository proposalRepo, ProposalBasisReviewService basisReviewService,
            ProposalGateway proposalGateway) {
        this.proposalRepo = proposalRepo;
        this.basisReviewService = basisReviewService;
        this.proposalGateway = proposalGateway;
    }

    @GetMapping
    public List<ProposalRepository.PendingProposalRow> listPending(WarehouseScope warehouse) {
        return proposalRepo.listPending(warehouse.code(), LIST_LIMIT);
    }

    /** 상세 + 근거 대조. 화면의 핵심 — 승인 버튼을 누르기 전에 basis_snapshot이 아직 유효한지 보여준다. */
    @GetMapping("/{id}")
    public ProposalDetailResponse detail(@PathVariable long id, Authentication authentication) {
        ProposalRepository.ProposalDetail detail = requireProposal(id);
        AccessGuard.requireWarehouse(authentication, warehouseCodeOf(detail));
        return new ProposalDetailResponse(detail, basisReviewService.review(id));
    }

    @PostMapping("/{id}/approve")
    public ApprovalOutcome approve(@PathVariable long id, Authentication authentication) {
        AccessGuard.requireAnyRole(authentication, "SUPERVISOR");
        ProposalRepository.ProposalDetail detail = requireProposal(id);
        AccessGuard.requireWarehouse(authentication, warehouseCodeOf(detail));
        return proposalGateway.approve(id, authentication.getName());
    }

    @PostMapping("/{id}/reject")
    public void reject(@PathVariable long id, @RequestBody RejectRequest request, Authentication authentication) {
        AccessGuard.requireAnyRole(authentication, "SUPERVISOR");
        ProposalRepository.ProposalDetail detail = requireProposal(id);
        AccessGuard.requireWarehouse(authentication, warehouseCodeOf(detail));
        proposalGateway.reject(id, authentication.getName(), request.note());
    }

    private ProposalRepository.ProposalDetail requireProposal(long id) {
        return proposalRepo.detail(id)
                .orElseThrow(() -> new ProposalException("PROPOSAL_NOT_FOUND", "제안 %d를 찾을 수 없다".formatted(id)));
    }

    // 제안 생성(ProposalCreationService#validate)이 모든 entries를 같은 창고로 강제하므로 첫 엔트리만 봐도
    // 된다 — ProposalRepository#listPending의 창고 필터와 같은 전제다.
    private static String warehouseCodeOf(ProposalRepository.ProposalDetail detail) {
        return detail.entries().get(0).warehouseCode();
    }

    public record ProposalDetailResponse(ProposalRepository.ProposalDetail proposal,
            ProposalBasisReviewService.BasisReview basisReview) {
    }

    public record RejectRequest(String note) {
    }
}
