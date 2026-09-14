package com.zerosum.inventory.ai;

import com.zerosum.inventory.repository.AiQueryRepository;
import java.util.List;

/**
 * {@code getIssueContext}의 반환 묶음: 이슈 행 + 앵커 원장 id + 앵커 전후 원장 + 같은 파티션의 실사 이력.
 * sku_id·lot_id가 널인 이슈(COUNT_FLAG_MISMATCH)는 파티션이 없어 anchorLedgerEntryId가 0이고
 * surroundingLedger·countHistory는 빈 목록이다 (AiQueryService#getIssueContext).
 */
public record IssueContext(AiQueryRepository.OpenIssueRow issue, long anchorLedgerEntryId,
        List<AiQueryRepository.LedgerRow> surroundingLedger,
        List<AiQueryRepository.CountHistoryRow> countHistory) {
}
