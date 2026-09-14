package com.zerosum.inventory.ai;

import com.zerosum.inventory.domain.IssueException;
import com.zerosum.inventory.repository.AiQueryRepository;
import com.zerosum.inventory.repository.AiQueryRepository.CountHistoryRow;
import com.zerosum.inventory.repository.AiQueryRepository.LedgerRow;
import com.zerosum.inventory.repository.AiQueryRepository.OpenIssueRow;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * MCP 조회 도구 4개(get_available_stock·get_ledger·list_open_issues·get_issue_context, docs/07)와
 * 메서드 이름을 1:1로 맞춘 서비스 — 전송 어댑터는 이 네 메서드에만 위임하면 된다.
 *
 * <p>location은 UNIQUE(warehouse_id, code)라 로케이션 코드만으로는 창고를 가릴 수 없다 — 그래서 창고
 * 접근 권한은 여기서 근거를 판단하지 않고(권한 출처는 4단계 범위 밖) 조회 도구 4개 모두 호출자가 넘긴
 * warehouseCode로만 범위를 강제로 좁힌다. 대신 결과 건수 상한은 LLM이 조절하지 못하도록 서비스 내부
 * 상수로 고정한다 — application.yml에 두지 않는다(최소 구현).
 */
@Service
public class AiQueryService {

    private static final int AVAILABLE_STOCK_LIMIT = 200;
    private static final int LEDGER_LIMIT = 200;
    private static final int OPEN_ISSUE_LIMIT = 100;
    // 이슈 컨텍스트의 "앵커 전후 N건" — 앵커를 포함해 앞으로 최대 이 건수, 뒤로 최대 이 건수를 본다.
    private static final int LEDGER_WINDOW = 5;

    private final AiQueryRepository repo;

    AiQueryService(AiQueryRepository repo) {
        this.repo = repo;
    }

    public List<AiQueryRepository.AvailableStockRow> getAvailableStock(String warehouseCode, String skuCode) {
        return repo.availableStock(warehouseCode, skuCode, AVAILABLE_STOCK_LIMIT);
    }

    public List<LedgerRow> getLedger(String warehouseCode, String skuCode, String locationCode, Instant from,
            Instant to) {
        return repo.ledger(warehouseCode, skuCode, locationCode, from, to, LEDGER_LIMIT);
    }

    public List<OpenIssueRow> listOpenIssues(String warehouseCode) {
        return repo.openIssues(warehouseCode, OPEN_ISSUE_LIMIT);
    }

    /**
     * 이슈 원인 분석 컨텍스트. 앵커 규칙: {@code COALESCE(detail의 ledgerEntryId, 파티션 최신 원장 id)}.
     * CHAIN_BREAK은 detail에 ledgerEntryId가 있어(ReconciliationService) 최초 깨짐 지점이 그대로
     * 앵커가 되고, 나머지 타입은 파티션의 마지막 줄이 앵커가 된다. sku_id·lot_id가 널인 이슈
     * (COUNT_FLAG_MISMATCH)는 파티션이 없으므로 앵커를 0으로 두고 전후 원장·실사 이력은 빈 목록으로
     * 남긴 채 이슈 행만 채운다.
     */
    public IssueContext getIssueContext(String warehouseCode, long issueId) {
        OpenIssueRow issue = repo.issue(warehouseCode, issueId)
                .orElseThrow(() -> new IssueException("ISSUE_NOT_FOUND", "이슈 %d를 찾을 수 없다".formatted(issueId)));

        if (issue.skuId() == null && issue.lotId() == null) {
            return new IssueContext(issue, 0L, List.of(), List.of());
        }

        long anchor = repo.anchorLedgerEntryId(issueId, issue.locationId(), issue.skuId(), issue.lotId());
        List<LedgerRow> surrounding = repo.ledgerAround(issue.locationId(), issue.skuId(), issue.lotId(), anchor,
                LEDGER_WINDOW);
        List<CountHistoryRow> countHistory = repo.countHistory(issue.locationId(), issue.skuId(), issue.lotId());
        return new IssueContext(issue, anchor, surrounding, countHistory);
    }
}
