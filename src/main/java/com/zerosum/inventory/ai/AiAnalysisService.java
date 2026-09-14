package com.zerosum.inventory.ai;

import com.zerosum.inventory.repository.AiProposalRepository;
import org.springframework.stereotype.Service;

/**
 * AI 원인 분석 결과를 남기는 유일한 쓰기 도구. inventory_issue의 status·acked_*·resolved_*는 여기서도
 * 건드릴 수 없다 — ai_proposer 계정 자체가 그 컬럼들에 권한이 없어(V4 마이그레이션의 컬럼 단위 GRANT)
 * DB가 막는다.
 */
@Service
public class AiAnalysisService {

    private final AiProposalRepository repo;

    AiAnalysisService(AiProposalRepository repo) {
        this.repo = repo;
    }

    public void writeIssueAnalysis(long issueId, String analysisJson) {
        repo.writeIssueAnalysis(issueId, analysisJson);
    }
}
