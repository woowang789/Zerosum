package com.zerosum.inventory.reconciliation;

import com.zerosum.inventory.repository.ReconciliationRepository;
import com.zerosum.inventory.repository.ReconciliationRepository.AllocationMismatch;
import com.zerosum.inventory.repository.ReconciliationRepository.ChainBreak;
import com.zerosum.inventory.repository.ReconciliationRepository.CountFlagMismatch;
import com.zerosum.inventory.repository.ReconciliationRepository.ProjectionMismatch;
import com.zerosum.inventory.repository.ReconciliationRepository.VirtualLocationBalance;
import org.springframework.stereotype.Service;

/**
 * 정합 검증 배치 본체 (docs/06-events-reconciliation.md). ①~⑤를 돌려 불일치를 inventory_issue에
 * 기록만 한다 — 자동으로 보정하지 않는다. 자동 보정은 버그의 흔적을 지워 같은 문제가 반복되게 만든다.
 *
 * <p>쿼리 정본은 {@link ReconciliationRepository}이고, 여기서는 그 결과를 이슈로 옮겨 적는 역할만
 * 한다. 이슈 중복 방지(같은 불일치는 OPEN·ACKED 상태로 이미 있으면 새로 만들지 않음)는
 * {@link ReconciliationRepository#recordIssueIfAbsent}가 맡는다.
 *
 * <p><b>배치는 이슈를 스스로 닫지 않는다.</b> 다음 실행에서 같은 불일치가 더 이상 관측되지 않아도
 * 기존 이슈를 자동으로 RESOLVED 처리하지 않는다 — 증상이 사라진 것이 원인이 해결됐다는 뜻은 아니다.
 * 코드의 버그는 그대로인데 다른 거래가 우연히 숫자를 맞춰버린 경우와 구분할 수 없기 때문이다. 종결
 * 판단은 사람의 몫으로 남기고({@link #resolve}), 4단계에서는 AI 원인 분석 + 사람 승인이 그 자리를
 * 채울 예정이다.
 *
 * <p>스케줄러({@link ReconciliationScheduler})는 이 클래스의 {@link #runOnce()}를 주기적으로 부르는
 * 얇은 껍데기일 뿐이다 — 테스트는 스케줄러를 꺼두고 이 메서드를 직접 호출한다.
 */
@Service
public class ReconciliationService {

    private final ReconciliationRepository repo;

    ReconciliationService(ReconciliationRepository repo) {
        this.repo = repo;
    }

    /** 한 번 실행. 새로 기록한 이슈 수를 돌려준다 (테스트 검증용). */
    public int runOnce() {
        int created = 0;
        created += recordProjectionMismatches();
        created += recordAllocationMismatches();
        created += recordChainBreaks();
        created += recordVirtualLocationBalances();
        created += recordCountFlagMismatches();
        return created;
    }

    // ── 이슈 처리 (사람이 부른다 — acknowledge()·resolve() 둘 다 배치가 스스로 호출하지 않는다) ──

    /** 사람이 이슈를 인지했다는 표시. 알림을 멈추되 "봐야 할 이슈" 목록에는 계속 남는다. */
    public void acknowledge(long issueId, String who) {
        repo.acknowledge(issueId, who);
    }

    /**
     * 이슈 종결. resolvedTxnId는 조정 거래로 바로잡은 경우에만 있고, 코드 수정 등 거래 없이 종결한
     * 경우 널이다 — 그때는 resolutionNote로 이유를 남긴다. 4단계에서는 AI가 ai_analysis에 원인
     * 후보를 쓰고 action_proposal로 복구 커맨드를 제안, 사람이 승인해 실행된 거래 id가 이 메서드의
     * resolvedTxnId로 들어오는 흐름이 될 예정이다 — 지금은 그 자리만 비워 둔다.
     */
    public void resolve(long issueId, String who, Long resolvedTxnId, String resolutionNote) {
        repo.resolve(issueId, who, resolvedTxnId, resolutionNote);
    }

    private int recordProjectionMismatches() {
        int created = 0;
        for (ProjectionMismatch m : repo.findProjectionMismatches()) {
            String detail = "{\"onHandQty\": %d, \"ledgerQty\": %d}".formatted(m.onHandQty(), m.ledgerQty());
            if (repo.recordIssueIfAbsent("PROJECTION_MISMATCH", "CRITICAL", m.locationId(), m.skuId(), m.lotId(),
                    detail)) {
                created++;
            }
        }
        return created;
    }

    private int recordAllocationMismatches() {
        int created = 0;
        for (AllocationMismatch m : repo.findAllocationMismatches()) {
            String detail = "{\"balanceId\": %d, \"allocatedQty\": %d, \"activeQty\": %d}"
                    .formatted(m.balanceId(), m.allocatedQty(), m.activeQty());
            if (repo.recordIssueIfAbsent("ALLOCATION_MISMATCH", "HIGH", m.locationId(), m.skuId(), m.lotId(),
                    detail)) {
                created++;
            }
        }
        return created;
    }

    private int recordChainBreaks() {
        int created = 0;
        // 원인 분석의 출발점이 되는 원장 줄과 거래 id를 detail에 담는다 (3단계 작업 지시).
        for (ChainBreak m : repo.findChainBreaks()) {
            String detail = "{\"ledgerEntryId\": %d, \"txnId\": %d, \"onHandAfter\": %d, \"expected\": %d}"
                    .formatted(m.ledgerEntryId(), m.txnId(), m.onHandAfter(), m.expected());
            if (repo.recordIssueIfAbsent("CHAIN_BREAK", "CRITICAL", m.locationId(), m.skuId(), m.lotId(), detail)) {
                created++;
            }
        }
        return created;
    }

    private int recordVirtualLocationBalances() {
        int created = 0;
        for (VirtualLocationBalance m : repo.findVirtualLocationBalances()) {
            String detail = "{\"balanceId\": %d}".formatted(m.balanceId());
            if (repo.recordIssueIfAbsent("VIRTUAL_LOCATION_BALANCE", "MEDIUM", m.locationId(), m.skuId(), m.lotId(),
                    detail)) {
                created++;
            }
        }
        return created;
    }

    private int recordCountFlagMismatches() {
        int created = 0;
        for (CountFlagMismatch m : repo.findCountFlagMismatches()) {
            String flagged = m.flaggedSessionId() == null ? "null" : String.valueOf(m.flaggedSessionId());
            String active = m.activeSessionId() == null ? "null" : String.valueOf(m.activeSessionId());
            String detail = "{\"flaggedSessionId\": %s, \"activeSessionId\": %s}".formatted(flagged, active);
            // SKU·로트와 무관한 로케이션 단위 불일치라 sku_id·lot_id는 null이다.
            if (repo.recordIssueIfAbsent("COUNT_FLAG_MISMATCH", "LOW", m.locationId(), null, null, detail)) {
                created++;
            }
        }
        return created;
    }
}
