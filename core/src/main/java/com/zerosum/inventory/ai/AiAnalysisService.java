package com.zerosum.inventory.ai;

import com.zerosum.inventory.repository.AiProposalRepository;
import org.springframework.stereotype.Service;

/**
 * AI 원인 분석 결과를 남기는 유일한 쓰기 도구. inventory_issue의 status·acked_*·resolved_*는 여기서도
 * 건드릴 수 없다 — ai_proposer 계정 자체가 그 컬럼들에 권한이 없어(V4 마이그레이션의 컬럼 단위 GRANT)
 * DB가 막는다.
 *
 * <p>첫 인자가 warehouseCode인 것은 {@link AiQueryService}의 조회 메서드 넷과 같은 규칙이다 — 창고
 * 권한은 LLM이 정하지 않고 호출자(MCP 서버 프로세스)가 "나는 누구인가"로 넘긴다. DB 권한은 어느
 * 창고의 이슈냐를 가려주지 않으므로(ai_analysis UPDATE 권한은 테이블 전체에 걸린다) 범위는 코어가
 * 강제해야 한다.
 */
@Service
public class AiAnalysisService {

    private final AiProposalRepository repo;

    AiAnalysisService(AiProposalRepository repo) {
        this.repo = repo;
    }

    public void writeIssueAnalysis(String warehouseCode, long issueId, String analysisJson) {
        repo.writeIssueAnalysis(warehouseCode, issueId, analysisJson);
    }
}
