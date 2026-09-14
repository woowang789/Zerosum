package com.zerosum.inventory.proposal;

import com.zerosum.inventory.domain.ProposalException;
import com.zerosum.inventory.repository.AiProposalRepository;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 에이전트의 제안 생성 창구. 여기서 거부하지 않으면 승인 시점 예외로 넘어가 제안이 PENDING인 채로
 * "버튼이 먹지 않는" 상태가 되므로, 승인 단계가 아니라 생성 단계에서 미리 걸러야 하는 규칙만 본다
 * (예: inventory_txn의 CHECK가 txn_type='ADJUSTMENT'면 reason_code NOT NULL을 요구한다).
 *
 * <p>ai_proposer는 트랜잭션 매니저 없이 autocommit 단문만 쓰므로(docs/07-ai-integration.md) 이 서비스에는
 * {@code @Transactional}을 붙이지 않는다.
 */
@Service
public class ProposalCreationService {

    // 4단계 범위: TRANSFER·RECEIPT_DRAFT·RESOLVE_COUNT는 아직 지원하지 않는다.
    private static final Set<String> SUPPORTED_TYPES = Set.of("MOVE", "ADJUSTMENT");

    private final AiProposalRepository repository;
    private final Duration ttl;

    public ProposalCreationService(AiProposalRepository repository,
            @Value("${zerosum.proposal.ttl-minutes}") long ttlMinutes) {
        this.repository = repository;
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    /**
     * @param allowedWarehouseCode 호출자(어댑터)가 자신이 누구인지 밝히는 값 — 이 제안이 건드릴 수 있는
     *         유일한 창고. 널을 허용해 "제한 없음"을 표현하지 않는다: 모든 호출자가 범위를 명시해야 한다.
     */
    public CreateProposalOutcome create(CreateProposalRequest request, String allowedWarehouseCode) {
        validate(request, allowedWarehouseCode);

        OptionalLong created = repository.insertCanonical(request.proposalType(), request.commandPayloadJson(),
                request.rationale(), request.proposedBy(), request.agentMetaJson(), request.basisRefs(), ttl);
        if (created.isPresent()) {
            return new ProposalCreated(created.getAsLong());
        }

        // uq_proposal_pending과 충돌해 0행 → 이미 대기 중인 제안의 id를 찾아 돌려준다.
        // 에이전트는 언제 재시도해도 같은 id를 받는다.
        OptionalLong existing = repository.findPending(request.proposalType(), request.commandPayloadJson());
        if (existing.isPresent()) {
            return new ProposalDuplicate(existing.getAsLong());
        }
        throw new ProposalException("PROPOSAL_RACE_LOST",
                "제안 충돌은 감지했지만 PENDING 제안을 찾지 못했다 (그 사이 승인·만료된 것으로 보인다)");
    }

    private void validate(CreateProposalRequest request, String allowedWarehouseCode) {
        if (!SUPPORTED_TYPES.contains(request.proposalType())) {
            throw new ProposalException("UNSUPPORTED_PROPOSAL_TYPE",
                    "지원하지 않는 proposalType: %s".formatted(request.proposalType()));
        }

        // 정규화(AiProposalRepository)가 보는 것과 같은 방식 — PostgreSQL이 JSONB로 파싱한 결과 —
        // 으로 payload를 본다. 원본 JSON 텍스트를 정규식으로 검사하면 정규화가 버리는 위치의 값에 속는다.
        AiProposalRepository.PayloadValidation payload = repository.validatePayload(request.commandPayloadJson());

        if (!request.proposalType().equals(payload.txnType())) {
            throw new ProposalException("PROPOSAL_TYPE_MISMATCH",
                    "proposalType(%s)과 payload의 txnType(%s)이 다르다"
                            .formatted(request.proposalType(), payload.txnType()));
        }
        if (!payload.hasEntries()) {
            throw new ProposalException("ENTRIES_REQUIRED",
                    "entries가 비어 있는 제안은 승인해도 만들 거래가 없다");
        }
        if ("ADJUSTMENT".equals(request.proposalType()) && payload.reasonCode() == null) {
            throw new ProposalException("REASON_CODE_REQUIRED",
                    "ADJUSTMENT 제안은 payload 최상위에 reasonCode가 있어야 한다 (승인 시점에 inventory_txn CHECK로 막힌다)");
        }

        // 창고 스코핑은 여기(create_proposal, AI 쓰기 표면)에서만 강제한다 — PostingService 같은 일반
        // 쓰기 경로는 호출자가 이미 신뢰된 내부 서비스라 이 제약이 없다. entries[].wh는 실제로 실행될
        // 커맨드이므로 반드시 보고, basisRefs.warehouseCode는 LLM이 주는 근거 캡처 좌표라 같은 김에 본다.
        if (payload.warehouseCodes().stream().anyMatch(wh -> !allowedWarehouseCode.equals(wh))) {
            throw new ProposalException("WAREHOUSE_OUT_OF_SCOPE",
                    "commandPayloadJson의 entries에 이 호출자 권한(%s) 밖 창고가 있다: %s"
                            .formatted(allowedWarehouseCode, payload.warehouseCodes()));
        }
        if (request.basisRefs().stream().anyMatch(ref -> !allowedWarehouseCode.equals(ref.warehouseCode()))) {
            throw new ProposalException("WAREHOUSE_OUT_OF_SCOPE",
                    "basisRefs에 이 호출자 권한(%s) 밖 창고가 있다".formatted(allowedWarehouseCode));
        }
    }
}
