package com.zerosum.inventory.proposal;

import com.zerosum.inventory.domain.BasisRef;
import java.util.List;

/**
 * 에이전트가 제안을 생성할 때 넘기는 입력. commandPayloadJson·agentMetaJson은 이미 JSON 텍스트로
 * 직렬화되어 들어온다(이 조각은 Jackson을 쓰지 않는다 — payload 조작은 저장소의 SQL이 담당한다).
 * basisRefs는 basis_snapshot을 서버(DB)가 직접 채우기 위한 관측 대상 좌표다.
 */
public record CreateProposalRequest(String proposalType, String commandPayloadJson, String rationale,
        String proposedBy, String agentMetaJson, List<BasisRef> basisRefs) {
}
