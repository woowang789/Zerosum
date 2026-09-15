package com.zerosum.inventory.web.issue;

import com.zerosum.inventory.domain.IssueException;
import com.zerosum.inventory.reconciliation.ReconciliationService;
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
 * 사람이 보는 이슈 처리 창구. {@link com.zerosum.inventory.web.proposal.ProposalController}와 같은
 * 원칙 — 목록의 창고 범위는 {@link WarehouseScope}가 강제하고, {@code {id}}만 받는 상세·인지·종결은
 * 이슈의 창고를 직접 읽어 {@link AccessGuard#requireWarehouse}로 대조한다. 인지자·종결자도 요청이
 * 아니라 {@link Authentication}에서만 읽는다.
 */
@RestController
@RequestMapping("/api/issues")
public class IssueController {

    private static final int LIST_LIMIT = 100;

    private final IssueRepository issueRepo;
    private final ReconciliationService reconciliationService;

    IssueController(IssueRepository issueRepo, ReconciliationService reconciliationService) {
        this.issueRepo = issueRepo;
        this.reconciliationService = reconciliationService;
    }

    @GetMapping
    public List<IssueRepository.OpenIssueRow> listOpen(WarehouseScope warehouse) {
        return issueRepo.listOpen(warehouse.code(), LIST_LIMIT);
    }

    /** 상세 + 원인 분석 컨텍스트(같은 파티션의 원장·실사 이력). */
    @GetMapping("/{id}")
    public IssueDetailResponse detail(@PathVariable long id, Authentication authentication) {
        IssueRepository.OpenIssueRow issue = requireIssue(id);
        AccessGuard.requireWarehouse(authentication, issue.warehouseCode());
        List<IssueRepository.LedgerRow> ledger = issueRepo.ledgerFor(issue.locationId(), issue.skuId(),
                issue.lotId());
        List<IssueRepository.CountHistoryRow> countHistory = issueRepo.countHistoryFor(issue.locationId(),
                issue.skuId(), issue.lotId());
        return new IssueDetailResponse(issue, ledger, countHistory);
    }

    @PostMapping("/{id}/ack")
    public void acknowledge(@PathVariable long id, Authentication authentication) {
        AccessGuard.requireAnyRole(authentication, "OPERATOR", "SUPERVISOR");
        IssueRepository.OpenIssueRow issue = requireIssue(id);
        AccessGuard.requireWarehouse(authentication, issue.warehouseCode());
        reconciliationService.acknowledge(id, authentication.getName());
    }

    @PostMapping("/{id}/resolve")
    public void resolve(@PathVariable long id, @RequestBody ResolveRequest request, Authentication authentication) {
        AccessGuard.requireAnyRole(authentication, "SUPERVISOR");
        IssueRepository.OpenIssueRow issue = requireIssue(id);
        AccessGuard.requireWarehouse(authentication, issue.warehouseCode());
        reconciliationService.resolve(id, authentication.getName(), request.resolvedTxnId(), request.note());
    }

    private IssueRepository.OpenIssueRow requireIssue(long id) {
        return issueRepo.findOpen(id)
                .orElseThrow(() -> new IssueException("ISSUE_NOT_FOUND", "이슈 %d를 찾을 수 없다".formatted(id)));
    }

    public record IssueDetailResponse(IssueRepository.OpenIssueRow issue, List<IssueRepository.LedgerRow> ledger,
            List<IssueRepository.CountHistoryRow> countHistory) {
    }

    public record ResolveRequest(String note, Long resolvedTxnId) {
    }
}
