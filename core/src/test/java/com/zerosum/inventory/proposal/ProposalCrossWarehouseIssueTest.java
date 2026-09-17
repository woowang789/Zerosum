package com.zerosum.inventory.proposal;

import static org.assertj.core.api.Assertions.assertThat;

import com.zerosum.inventory.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 승인이 남의 창고 이슈를 닫지 않는다.
 *
 * <p>제안의 {@code issueId}는 payload 안에 있고 그것을 쓴 것은 AI다. 생성 단계가 호출자 창고와 대조하지만
 * <b>그것만으로는 경계가 아니다</b> — 여기서 검증하는 상태(이미 PENDING인 제안)는 그 검사를 지나온 적이
 * 없고, {@code ai_proposer}는 {@code action_proposal}에 테이블 단위 INSERT 권한이 있어 MCP를 거치지 않고
 * 직접 넣을 수도 있다(하네스 UC-E12가 그 경로다). 실제 피해는 종결 시점에 난다.
 *
 * <p>피해가 비대칭이라 더 나쁘다. 배치가 만드는 이슈(PROJECTION_MISMATCH 등)는 같은 불일치가 남아 있으면
 * 다시 열리지만, 실사에서 나오는 COUNT_VARIANCE는 제출 시 <b>한 번만</b> 생성되므로 잘못 닫히면
 * 되살아나는 경로가 없다. 담당자의 "봐야 할 이슈" 목록에서 조용히 사라진다.
 *
 * <p>어긋나면 <b>이슈만</b> 건드리지 않는다 — 재고 정정은 그대로 실행된다. 이슈 종결은 승인 트랜잭션의
 * 목적이 아니라 부수 효과라는 기존 결정({@code resolveIfOpen})과 같은 이유다.
 */
class ProposalCrossWarehouseIssueTest extends AbstractIntegrationTest {

    @Autowired
    private ProposalGateway proposalGateway;

    @Test
    void approvalDoesNotResolveAnotherWarehouseIssue() {
        // ICN01에 재고를 둔다 — 제안이 실제로 움직일 것.
        postAndExpectSuccess(request("receipt:XWH:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-200002", "L20260910-B", -50),
                line("ICN01", "A-01-01-1", "SKU-200002", "L20260910-B", 50)));

        long foreignIssueId = insertOpenIssueAt("YIT01");
        long proposalId = insertPendingProposalPointingAt(foreignIssueId);

        ApprovalOutcome outcome = proposalGateway.approve(proposalId, "user:icn-supervisor");

        assertThat(outcome).as("재고 정정 자체는 실행된다").isInstanceOf(Executed.class);
        assertThat(onHand()).as("ICN01 재고는 정정됐다").isEqualTo(55);
        assertThat(issueStatus(foreignIssueId))
                .as("ICN01 승인이 YIT01 이슈를 닫아서는 안 된다")
                .isEqualTo("OPEN");
        assertReconciliationClean();
    }

    /**
     * 생성 검증을 거치지 않고 들어온 제안 — ai_proposer의 테이블 단위 INSERT가 그 경로다.
     *
     * <p>basis_snapshot은 AiProposalRepository#insertCanonical이 만드는 것과 같은 모양으로 A-01-01-1의
     * 현재 잔액을 담는다. 이 테스트가 보는 것은 "재고 정정은 실행되고 남의 창고 이슈만 안 닫힌다"이므로,
     * 승인이 근거 재검증(ProposalApprovalService ③-2·④·⑥)을 통과해 실제로 포스팅까지 가야 한다.
     */
    private long insertPendingProposalPointingAt(long issueId) {
        return jdbcClient.sql("""
                INSERT INTO action_proposal (proposal_type, command_payload, basis_snapshot, rationale,
                                             proposed_by, expires_at)
                SELECT 'ADJUSTMENT', CAST(:payload AS JSONB),
                       jsonb_build_object('captured_at', now(), 'observations',
                         jsonb_build_array(jsonb_build_object('scope', 'balance', 'balance_id', b.balance_id,
                                                              'available_qty', b.available_qty))),
                       '남의 창고 이슈를 가리키는 제안', 'agent:probe', now() + INTERVAL '60 minutes'
                FROM v_balance_basis b
                WHERE b.warehouse_code = 'ICN01' AND b.location_code = 'A-01-01-1'
                  AND b.sku_code = 'SKU-200002' AND b.lot_no = 'L20260910-B'
                RETURNING id
                """)
                .param("payload", """
                        {"txnType":"ADJUSTMENT","reasonCode":"CYCLE_COUNT","issueId":%d,"entries":[
                          {"wh":"ICN01","loc":"A-01-01-1","sku":"SKU-200002","lot":"L20260910-B","qty":5},
                          {"wh":"ICN01","loc":"V-ADJUST","sku":"SKU-200002","lot":"L20260910-B","qty":-5}]}
                        """.formatted(issueId))
                .query(Long.class)
                .single();
    }

    private long insertOpenIssueAt(String warehouseCode) {
        return jdbcClient.sql("""
                INSERT INTO inventory_issue (issue_type, severity, location_id, sku_id, lot_id, detail)
                SELECT 'PROJECTION_MISMATCH', 'CRITICAL', l.id, s.id, lo.id, '{}'::JSONB
                FROM location l
                JOIN warehouse w ON w.id = l.warehouse_id AND w.code = :wh
                JOIN sku s ON s.code = 'SKU-200002'
                JOIN lot lo ON lo.sku_id = s.id AND lo.lot_no = 'L20260910-B'
                WHERE l.code = 'A-01-01-1'
                RETURNING id
                """).param("wh", warehouseCode).query(Long.class).single();
    }

    private String issueStatus(long issueId) {
        return jdbcClient.sql("SELECT status FROM inventory_issue WHERE id = :id")
                .param("id", issueId).query(String.class).single();
    }

    private int onHand() {
        return onHandQty("ICN01", "A-01-01-1", "SKU-200002", "L20260910-B");
    }
}
