package com.zerosum.inventory.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.zerosum.inventory.domain.BasisRef;
import com.zerosum.inventory.proposal.ApprovalOutcome;
import com.zerosum.inventory.proposal.CreateProposalOutcome;
import com.zerosum.inventory.proposal.CreateProposalRequest;
import com.zerosum.inventory.proposal.Executed;
import com.zerosum.inventory.proposal.ProposalCreated;
import com.zerosum.inventory.proposal.ProposalCreationService;
import com.zerosum.inventory.proposal.ProposalGateway;
import com.zerosum.inventory.reconciliation.ReconciliationService;
import com.zerosum.inventory.support.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 4단계 합의 흐름 전체(docs/06-events-reconciliation.md, docs/07-ai-integration.md)를 이어 붙여 본다:
 * 배치 탐지 → AI 원인 분석({@link AiAnalysisService}) → AI 제안({@link ProposalCreationService}) →
 * 사람 승인({@link ProposalGateway#approve}) → 그 실행 거래 id로
 * {@link ReconciliationService#resolve}. AI는 이 사슬 어디에서도 상태를 직접 바꾸지 않는다 — 마지막
 * resolve() 호출은 언제나 사람(승인자)이 부른다.
 */
class IssueResolutionFlowTest extends AbstractIntegrationTest {

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private AiAnalysisService aiAnalysisService;

    @Autowired
    private ProposalCreationService proposalCreationService;

    @Autowired
    private ProposalGateway proposalGateway;

    @Test
    void approvedAdjustmentResolvesIssueWithSameTxnId() {
        postAndExpectSuccess(request("receipt:AI-FLOW-ADJ-0001:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -50),
                line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", 50)));
        long issueId = insertOpenIssue();

        // ① AI 원인 분석: inventory_issue.ai_analysis에 원인 후보를 남긴다 (자기 창고 이슈에만 쓸 수 있다)
        aiAnalysisService.writeIssueAnalysis("ICN01", issueId, "{\"cause\": \"실사 오차로 추정\"}");
        assertThat(aiAnalysisJson(issueId)).contains("실사 오차로 추정");

        // ② AI 제안: 복구 커맨드를 action_proposal로 제안한다 (물리적 차이를 조정 거래로 반영)
        String payload = """
                {"txnType":"ADJUSTMENT","reasonCode":"CYCLE_COUNT","entries":[
                   {"wh":"ICN01","loc":"A-01-01-1","sku":"SKU-100001","lot":"DEFAULT","qty":5},
                   {"wh":"ICN01","loc":"V-ADJUST","sku":"SKU-100001","lot":"DEFAULT","qty":-5}]}
                """;
        CreateProposalRequest createRequest = new CreateProposalRequest("ADJUSTMENT", payload, "실사에서 +5 오차 확인",
                "agent:ai", null,
                List.of(BasisRef.balance("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT")));
        CreateProposalOutcome created = proposalCreationService.create(createRequest, "ICN01");
        long proposalId = ((ProposalCreated) created).proposalId();

        // ③ 사람이 승인해 실행 — 실행 거래의 주체는 승인자다 (AI가 아니다)
        ApprovalOutcome outcome = proposalGateway.approve(proposalId, "user:ops");
        assertThat(outcome).isInstanceOf(Executed.class);
        long txnId = ((Executed) outcome).txnId();

        // ④ 그 거래 id를 resolve()의 resolvedTxnId로 넘긴다 — 부르는 것은 사람(승인자)이다
        reconciliationService.resolve(issueId, "user:ops", txnId, null);

        IssueRow row = issueRow(issueId);
        assertThat(row.status()).isEqualTo("RESOLVED");
        assertThat(row.resolvedTxnId()).as("승인·실행된 거래 id와 같다").isEqualTo(txnId);
        assertThat(row.resolvedBy()).as("종결 주체는 승인한 사람이다").isEqualTo("user:ops");
        assertReconciliationClean();
    }

    @Test
    void chainBreakResolvesWithNoteAndNullTxnId() throws SQLException {
        postAndExpectSuccess(request("receipt:AI-FLOW-CB-0001:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -40),
                line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", 40)));
        Ids ids = resolveIds("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT");
        long ledgerEntryId = jdbcClient.sql("""
                SELECT min(id) FROM inventory_ledger_entry WHERE location_id = :loc AND on_hand_after IS NOT NULL
                """)
                .param("loc", ids.locationId())
                .query(Long.class)
                .single();
        corruptLedgerOnHandAfter(ledgerEntryId, 5);

        assertThat(reconciliationService.runOnce()).isEqualTo(1);
        long issueId = jdbcClient.sql("SELECT id FROM inventory_issue WHERE issue_type = 'CHAIN_BREAK'")
                .query(Long.class)
                .single();

        // CHAIN_BREAK은 조정 거래로 고칠 수 없다 — 이미 기록된 on_hand_after는 app_rw가 UPDATE할 수 없고
        // (원장 UPDATE 권한이 없다) 고쳐서도 안 된다(증거다). resolutionNote만으로 종결한다.
        reconciliationService.resolve(issueId, "user:ops", null, "원장 손상 원인 확인. 과거 원장은 증거로 남기고 코드만 수정했다.");

        IssueRow row = issueRow(issueId);
        assertThat(row.status()).isEqualTo("RESOLVED");
        assertThat(row.resolvedTxnId()).as("CHAIN_BREAK은 거래 없이 종결한다").isNull();
        assertThat(row.resolutionNote()).isEqualTo("원장 손상 원인 확인. 과거 원장은 증거로 남기고 코드만 수정했다.");

        corruptLedgerOnHandAfter(ledgerEntryId, -5); // 원복
        assertReconciliationClean();
    }

    @Test
    void batchNeverClosesIssueByItself() {
        postAndExpectSuccess(request("receipt:AI-FLOW-REGRESSION-0001:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -20),
                line("ICN01", "B-01-01-1", "SKU-100001", "DEFAULT", 20)));
        corruptOnHandQty("B-01-01-1", "SKU-100001", 4);

        assertThat(reconciliationService.runOnce()).isEqualTo(1);
        long issueId = jdbcClient.sql("SELECT id FROM inventory_issue WHERE issue_type = 'PROJECTION_MISMATCH'")
                .query(Long.class)
                .single();

        // 원인 제거 — 증상이 사라진다
        corruptOnHandQty("B-01-01-1", "SKU-100001", -4);

        assertThat(reconciliationService.runOnce()).as("증상이 사라져도 배치는 새 이슈를 만들지 않는다").isZero();
        assertThat(issueRow(issueId).status()).as("배치는 기존 이슈를 스스로 닫지 않는다 — 여전히 OPEN").isEqualTo("OPEN");
        assertReconciliationClean();
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────────────

    /**
     * ReconciliationIssueLifecycleTest와 같은 기법: 배치와 무관하게 이슈 하나를 직접 만든다.
     *
     * <p>location_id를 채운다 — 이슈가 창고에 매이는 유일한 길이고(inventory_issue → location →
     * warehouse), AI 쓰기 표면 둘(write_issue_analysis·create_proposal의 issueId)이 그것으로 호출자
     * 창고를 대조하기 때문이다. 실제로 이슈를 여는 경로도 전부 채운다(ReconciliationService 다섯 종류,
     * CountResultRepository의 COUNT_VARIANCE) — 비워 두면 이 픽스처만 현실에 없는 모양이 된다.
     */
    private long insertOpenIssue() {
        return jdbcClient.sql("""
                INSERT INTO inventory_issue (issue_type, severity, location_id, detail)
                SELECT 'TEST_ISSUE', 'LOW', l.id, '{}'::JSONB
                FROM location l JOIN warehouse w ON w.id = l.warehouse_id
                WHERE w.code = 'ICN01' AND l.code = 'A-01-01-1'
                RETURNING id
                """)
                .query(Long.class)
                .single();
    }

    private String aiAnalysisJson(long issueId) {
        return jdbcClient.sql("SELECT ai_analysis::TEXT FROM inventory_issue WHERE id = :id")
                .param("id", issueId)
                .query(String.class)
                .single();
    }

    private record IssueRow(String status, Long resolvedTxnId, String resolvedBy, String resolutionNote) {
    }

    private IssueRow issueRow(long issueId) {
        return jdbcClient.sql("""
                SELECT status, resolved_txn_id, resolved_by, resolution_note FROM inventory_issue WHERE id = :id
                """)
                .param("id", issueId)
                .query((rs, rowNum) -> new IssueRow(rs.getString("status"), (Long) rs.getObject("resolved_txn_id"),
                        rs.getString("resolved_by"), rs.getString("resolution_note")))
                .single();
    }

    // app_rw(jdbcClient)에는 원장 UPDATE 권한이 없다(I4) — migrator 연결로 직접 조작한다
    // (ReconciliationChainAndVirtualLocationTest와 같은 이유).
    private void corruptLedgerOnHandAfter(long ledgerEntryId, int delta) throws SQLException {
        try (Connection migrator = migratorConnection();
                PreparedStatement ps = migrator.prepareStatement(
                        "UPDATE inventory_ledger_entry SET on_hand_after = on_hand_after + ? WHERE id = ?")) {
            ps.setInt(1, delta);
            ps.setLong(2, ledgerEntryId);
            ps.executeUpdate();
        }
    }

    private void corruptOnHandQty(String locationCode, String skuCode, int delta) {
        jdbcClient.sql("""
                UPDATE stock_balance SET on_hand_qty = on_hand_qty + :delta
                WHERE location_id = (SELECT id FROM location WHERE code = :loc
                                      AND warehouse_id = (SELECT id FROM warehouse WHERE code = 'ICN01'))
                  AND sku_id = (SELECT id FROM sku WHERE code = :sku)
                """)
                .param("delta", delta)
                .param("loc", locationCode)
                .param("sku", skuCode)
                .update();
    }
}
