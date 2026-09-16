package com.zerosum.inventory.mcp;

import com.zerosum.inventory.ai.AiAnalysisService;
import com.zerosum.inventory.ai.AiQueryService;
import com.zerosum.inventory.ai.IssueContext;
import com.zerosum.inventory.domain.BasisRef;
import com.zerosum.inventory.proposal.CreateProposalOutcome;
import com.zerosum.inventory.proposal.CreateProposalRequest;
import com.zerosum.inventory.proposal.ProposalCreationService;
import com.zerosum.inventory.repository.AiQueryRepository.AvailableStockRow;
import com.zerosum.inventory.repository.AiQueryRepository.LedgerRow;
import com.zerosum.inventory.repository.AiQueryRepository.OpenIssueRow;
import java.time.Instant;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * MCP 도구 6개. {@link AiQueryService}·{@link AiAnalysisService}·{@link ProposalCreationService}에
 * 위임만 한다 — 여기에 비즈니스 로직을 넣지 않는다.
 *
 * <p><b>창고 접근 권한.</b> {@code AiQueryService}의 조회 메서드 넷은 첫 인자가 warehouseCode다.
 * STDIO 전송은 호출자 신원을 알려주지 않으므로, 이 값을 {@code @Tool} 메서드의 파라미터로 두면 LLM이
 * 아무 창고나 채워 넣어 4단계에서 막은 권한 누수가 그대로 뚫린다(location은
 * UNIQUE(warehouse_id, code)라 로케이션 코드만으로는 창고를 가릴 수 없다 — 시드 데이터도 같은 코드를
 * 두 창고에 갖고 있다). 그래서 warehouseCode는 도구 시그니처에 나타나지 않고, 설정
 * ({@code zerosum.mcp.warehouse-code})에서 주입받아 필드로만 들고 있다 — 즉 "MCP 서버 프로세스 하나 =
 * 창고 하나 권한"이다. {@code create_proposal}의 proposedBy(에이전트 식별자)도 같은 이유로 LLM이
 * 정하지 않고 설정({@code zerosum.mcp.proposed-by})에서 온다. 쓰기 도구 둘도 같은
 * warehouseCode를 넘긴다 — {@code create_proposal}은 {@link ProposalCreationService#create}에,
 * {@code write_issue_analysis}는 {@link AiAnalysisService#writeIssueAnalysis}에. 조회 넷과 마찬가지로
 * 코어가 범위를 강제하고 여기는 "나는 누구인가"만 넘긴다. DB 권한은 이 경계를 대신 지켜주지 못한다 —
 * ai_proposer의 {@code UPDATE (ai_analysis)}도 {@code INSERT ON action_proposal}도 테이블 전체에 걸린
 * 권한이라 창고를 가리지 않는다.
 */
@Component
public class InventoryTools {

    private final AiQueryService aiQueryService;
    private final AiAnalysisService aiAnalysisService;
    private final ProposalCreationService proposalCreationService;
    private final String warehouseCode;
    private final String proposedBy;

    public InventoryTools(AiQueryService aiQueryService, AiAnalysisService aiAnalysisService,
            ProposalCreationService proposalCreationService,
            @Value("${zerosum.mcp.warehouse-code}") String warehouseCode,
            @Value("${zerosum.mcp.proposed-by}") String proposedBy) {
        this.aiQueryService = aiQueryService;
        this.aiAnalysisService = aiAnalysisService;
        this.proposalCreationService = proposalCreationService;
        this.warehouseCode = warehouseCode;
        this.proposedBy = proposedBy;
    }

    @Tool(name = "get_available_stock",
            description = "이 MCP 서버에 고정된 창고의 가용 재고를 조회한다(창고 범위는 서버 설정에 고정돼 있어 "
                    + "다른 창고는 조회할 수 없다). skuCode를 비우면 창고 전체를 본다. 결과는 최대 200건이다.")
    public List<AvailableStockRow> getAvailableStock(
            @ToolParam(description = "조회할 SKU 코드. 비우면 창고 전체를 본다", required = false) String skuCode) {
        return aiQueryService.getAvailableStock(warehouseCode, skuCode);
    }

    @Tool(name = "get_ledger",
            description = "이 MCP 서버에 고정된 창고의 원장(재고 이동 이력)을 SKU·로케이션·기간으로 필터링해 조회한다"
                    + "(창고 범위는 서버 설정에 고정돼 있다). 필터는 모두 선택이며 비우면 조건에서 제외된다. "
                    + "결과는 발생 순으로 최대 200건이다.")
    public List<LedgerRow> getLedger(
            @ToolParam(description = "SKU 코드. 비우면 전체 SKU", required = false) String skuCode,
            @ToolParam(description = "로케이션 코드. 비우면 전체 로케이션", required = false) String locationCode,
            @ToolParam(description = "조회 시작 시각(포함, ISO-8601). 비우면 하한 없음", required = false) Instant from,
            @ToolParam(description = "조회 종료 시각(포함, ISO-8601). 비우면 상한 없음", required = false) Instant to) {
        return aiQueryService.getLedger(warehouseCode, skuCode, locationCode, from, to);
    }

    @Tool(name = "list_open_issues",
            description = "이 MCP 서버에 고정된 창고의 미해결 이슈(OPEN·ACKED) 목록을 감지 시각이 오래된 순으로 "
                    + "조회한다(창고 범위는 서버 설정에 고정돼 있다). 결과는 최대 100건이다.")
    public List<OpenIssueRow> listOpenIssues() {
        return aiQueryService.listOpenIssues(warehouseCode);
    }

    @Tool(name = "get_issue_context",
            description = "이슈 하나의 원인 분석 컨텍스트를 조회한다 — 이슈 원본, 앵커(문제 지점) 원장, 앵커 전후 "
                    + "원장 이력, 같은 위치·SKU·로트의 실사 이력을 함께 묶어 돌려준다(창고 범위는 서버 설정에 고정돼 "
                    + "있다). issueId는 이 서버가 담당하는 창고의 이슈가 아니면 조회되지 않는다.")
    public IssueContext getIssueContext(@ToolParam(description = "조회할 이슈 id") long issueId) {
        return aiQueryService.getIssueContext(warehouseCode, issueId);
    }

    @Tool(name = "write_issue_analysis",
            description = "이슈의 AI 원인 분석 결과(JSON)를 기록한다. 이슈의 ai_analysis 컬럼만 갱신하며 "
                    + "status 등 다른 필드는 바꾸지 않는다(DB 권한으로 막혀 있다). OPEN·ACKED 상태의 이슈에만 쓸 수 있고, "
                    + "issueId는 이 서버가 담당하는 창고의 이슈가 아니면 기록되지 않는다.")
    public void writeIssueAnalysis(
            @ToolParam(description = "분석 결과를 기록할 이슈 id") long issueId,
            @ToolParam(description = "분석 결과 JSON 문자열") String analysisJson) {
        aiAnalysisService.writeIssueAnalysis(warehouseCode, issueId, analysisJson);
    }

    @Tool(name = "create_proposal",
            description = "재고 변경 제안(MOVE 또는 ADJUSTMENT)을 생성한다. 제안은 즉시 실행되지 않고 사람의 "
                    + "승인을 거친다. 같은 내용의 제안이 이미 대기 중이면 새로 만들지 않고 그 제안의 id를 돌려준다. "
                    + "제안자는 이 MCP 서버에 고정돼 있어 파라미터로 지정하지 않는다.")
    public CreateProposalOutcome createProposal(
            @ToolParam(description = "제안 유형: MOVE 또는 ADJUSTMENT") String proposalType,
            @ToolParam(description = "실행할 커맨드 payload(JSON 문자열)") String commandPayloadJson,
            @ToolParam(description = "제안 근거 설명") String rationale,
            @ToolParam(description = "에이전트 메타데이터(JSON 문자열)") String agentMetaJson,
            @ToolParam(description = "제안 승인 시 재검증할 관측 대상 좌표 목록(basis_snapshot)") List<BasisRef> basisRefs) {
        CreateProposalRequest request = new CreateProposalRequest(proposalType, commandPayloadJson, rationale,
                proposedBy, agentMetaJson, basisRefs);
        return proposalCreationService.create(request, warehouseCode);
    }
}
