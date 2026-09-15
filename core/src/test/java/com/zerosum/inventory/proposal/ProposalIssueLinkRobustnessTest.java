package com.zerosum.inventory.proposal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zerosum.inventory.domain.BasisRef;
import com.zerosum.inventory.domain.ProposalException;
import com.zerosum.inventory.support.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 제안의 issueId는 command_payload 안에 있고, 그것을 쓴 것은 AI다. 승인 경로가 그 값을 무조건 믿으면
 * AI가 잘못 적은 id 하나로 제안이 승인 불가 상태에 갇힌다 — 사람이 버튼을 눌러도 매번 전체가 롤백되고
 * 제안은 PENDING인 채 남으므로, 생성 단계 검증이 막으려던 "버튼이 먹지 않는" 상태와 같은 모양이다.
 *
 * <p>이슈 종결은 복구 거래의 부수 효과지 목적이 아니다. 이슈가 이미 닫혀 있다는 것이 재고 정정을
 * 막을 이유가 되어서는 안 된다.
 */
class ProposalIssueLinkRobustnessTest extends AbstractIntegrationTest {

    @Autowired
    private ProposalGateway proposalGateway;

    @Autowired
    private ProposalCreationService proposalCreationService;

    @Test
    void alreadyResolvedIssueDoesNotBlockApproval() {
        receiveColdBrew(50);
        long issueId = insertOpenIssue();
        long proposalId = createIssueProposal(issueId);

        // 승인 전에 사람이 다른 경로로 이슈를 먼저 닫았다 (중복 대응, 수동 종결 등)
        jdbcClient.sql("""
                UPDATE inventory_issue
                SET status = 'RESOLVED', resolved_by = 'user:ops', resolved_at = now(),
                    resolution_note = '수동 종결'
                WHERE id = :id
                """).param("id", issueId).update();

        ApprovalOutcome outcome = proposalGateway.approve(proposalId, "user:ops");

        assertThat(outcome).as("이슈가 이미 닫혔다고 해서 재고 정정까지 막히면 안 된다")
                .isInstanceOf(Executed.class);
        assertThat(proposalStatus(proposalId)).isEqualTo("EXECUTED");
        assertThat(onHand("A-01-01-1")).as("정정이 실제로 반영됐다").isEqualTo(55);
        assertReconciliationClean();
    }

    @Test
    void unknownIssueIdIsRejectedAtCreation() {
        receiveColdBrew(50);

        assertThatThrownBy(() -> proposalCreationService.create(issueRequest(999_999L), "ICN01"))
                .as("존재하지 않는 이슈를 가리키는 제안은 승인 시점이 아니라 생성 시점에 거부해야 한다")
                .isInstanceOf(ProposalException.class);

        assertThat(proposalCount()).isZero();
        assertReconciliationClean();
    }

    private long createIssueProposal(long issueId) {
        CreateProposalOutcome outcome = proposalCreationService.create(issueRequest(issueId), "ICN01");
        return ((ProposalCreated) outcome).proposalId();
    }

    private CreateProposalRequest issueRequest(long issueId) {
        String payload = """
                {"txnType":"ADJUSTMENT","reasonCode":"CYCLE_COUNT","issueId":%d,"entries":[
                   {"wh":"ICN01","loc":"A-01-01-1","sku":"SKU-200002","lot":"L20260910-B","qty":5},
                   {"wh":"ICN01","loc":"V-ADJUST","sku":"SKU-200002","lot":"L20260910-B","qty":-5}]}
                """.formatted(issueId);
        return new CreateProposalRequest("ADJUSTMENT", payload, "이슈 복구", "agent:reconciler", null,
                List.of(BasisRef.balance("ICN01", "A-01-01-1", "SKU-200002", "L20260910-B")));
    }

    private long insertOpenIssue() {
        return jdbcClient.sql("""
                INSERT INTO inventory_issue (issue_type, severity, location_id, sku_id, lot_id, detail)
                SELECT 'PROJECTION_MISMATCH', 'CRITICAL', l.id, s.id, lo.id, '{}'::JSONB
                FROM location l, warehouse w, sku s, lot lo
                WHERE w.code = 'ICN01' AND l.warehouse_id = w.id AND l.code = 'A-01-01-1'
                  AND s.code = 'SKU-200002' AND lo.sku_id = s.id AND lo.lot_no = 'L20260910-B'
                RETURNING id
                """).query(Long.class).single();
    }

    private void receiveColdBrew(int qty) {
        postAndExpectSuccess(request("receipt:ISSUELINK:" + qty, "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-200002", "L20260910-B", -qty),
                line("ICN01", "A-01-01-1", "SKU-200002", "L20260910-B", qty)));
    }

    private String proposalStatus(long proposalId) {
        return jdbcClient.sql("SELECT status FROM action_proposal WHERE id = :id")
                .param("id", proposalId).query(String.class).single();
    }

    private int onHand(String locationCode) {
        return jdbcClient.sql("""
                SELECT b.on_hand_qty FROM stock_balance b
                JOIN location l ON l.id = b.location_id
                JOIN warehouse w ON w.id = l.warehouse_id
                JOIN sku s ON s.id = b.sku_id
                WHERE w.code = 'ICN01' AND l.code = :loc AND s.code = 'SKU-200002'
                """).param("loc", locationCode).query(Integer.class).single();
    }

    private int proposalCount() {
        return jdbcClient.sql("SELECT count(*) FROM action_proposal").query(Integer.class).single();
    }
}
