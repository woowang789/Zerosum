package com.zerosum.inventory.proposal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zerosum.inventory.count.CountSessionGateway;
import com.zerosum.inventory.count.StartCountRequest;
import com.zerosum.inventory.domain.BasisRef;
import com.zerosum.inventory.domain.PostingException;
import com.zerosum.inventory.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 제안 승인 코어의 기본 경로 (docs/07-ai-integration.md 제안 실행 규칙, db/usecases/E-proposal.sql UC-E03·
 * UC-E04·UC-E06). 동시성·STALE 재검증은 다음 조각이 다룬다.
 */
class ProposalApprovalTest extends AbstractIntegrationTest {

    @Autowired
    private ProposalCreationService proposalCreationService;

    @Autowired
    private ProposalGateway proposalGateway;

    @Autowired
    private CountSessionGateway countSessionGateway;

    private static final String MOVE_PAYLOAD = """
            {"txnType":"MOVE","entries":[
               {"wh":"ICN01","loc":"A-01-01-2","sku":"SKU-200002","lot":"L20260910-B","qty":-20},
               {"wh":"ICN01","loc":"A-01-02-1","sku":"SKU-200002","lot":"L20260910-B","qty":20}]}
            """;

    @Test
    void approvalExecutesOnceAndLinksTxnToProposal() {
        receiveColdBrew("A-01-01-2", 100);
        long id = createMoveProposal(MOVE_PAYLOAD);

        ApprovalOutcome outcome = proposalGateway.approve(id, "user:choi.dw");

        assertThat(outcome).isInstanceOf(Executed.class);
        long txnId = ((Executed) outcome).txnId();
        assertThat(proposalStatus(id)).isEqualTo("EXECUTED");

        TxnRow txn = txnRow(txnId);
        assertThat(txn.actorId()).as("실행 거래의 주체는 승인자 (AI가 아니다)").isEqualTo("user:choi.dw");
        assertThat(txn.actorType()).isEqualTo("USER");
        assertThat(txn.proposalId()).as("거래가 제안을 역참조한다").isEqualTo(id);
        assertThat(onHandQty("ICN01", "A-01-02-1", "SKU-200002", "L20260910-B")).as("픽존에 20개 도착").isEqualTo(20);
        assertReconciliationClean();
    }

    @Test
    void approvingExecutedProposalIsNoOp() {
        receiveColdBrew("A-01-01-2", 100);
        long id = createMoveProposal(MOVE_PAYLOAD);
        proposalGateway.approve(id, "user:choi.dw");

        ApprovalOutcome second = proposalGateway.approve(id, "user:choi.dw");

        assertThat(second).isEqualTo(new AlreadyDecided(id, "EXECUTED"));
        assertThat(idemKeyTxnCount("proposal:" + id)).as("거래는 여전히 1건").isEqualTo(1);
        assertReconciliationClean();
    }

    @Test
    void expiredProposalBecomesExpiredWithoutPosting() {
        long id = createProposal("MOVE", MOVE_PAYLOAD, List.of());
        expireProposal(id);

        ApprovalOutcome outcome = proposalGateway.approve(id, "user:choi.dw");

        assertThat(outcome).isEqualTo(new ProposalExpired(id));
        assertThat(idemKeyTxnCount("proposal:" + id)).as("원장에는 아무것도 쓰이지 않는다").isZero();
        assertThat(decidedBy(id)).isEqualTo("user:choi.dw");
        assertThat(decidedAt(id)).isNotNull();
        assertReconciliationClean();
    }

    @Test
    void existingCallersStillPostWithNullProposalId() {
        long txnId = postAndExpectSuccess(request("receipt:NO-PROPOSAL", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-200002", "L20260910-B", -30),
                line("ICN01", "A-01-01-2", "SKU-200002", "L20260910-B", 30)));

        assertThat(txnRow(txnId).proposalId()).as("제안 없이 만든 거래는 proposal_id가 널이다").isNull();
        assertReconciliationClean();
    }

    @Test
    void moveIntoLocationUnderCountRollsBackAndKeepsPending() {
        receiveColdBrew("A-01-01-2", 100);
        countSessionGateway.start(new StartCountRequest("ICN01", "B-01-01-1", "user:lee.sh"));

        String payload = """
                {"txnType":"MOVE","entries":[
                   {"wh":"ICN01","loc":"A-01-01-2","sku":"SKU-200002","lot":"L20260910-B","qty":-20},
                   {"wh":"ICN01","loc":"B-01-01-1","sku":"SKU-200002","lot":"L20260910-B","qty":20}]}
                """;
        long id = createMoveProposal(payload);

        assertThatThrownBy(() -> proposalGateway.approve(id, "user:choi.dw"))
                .isInstanceOf(PostingException.class)
                .extracting(ex -> ((PostingException) ex).code())
                .isEqualTo("COUNT_IN_PROGRESS");

        assertThat(proposalStatus(id)).as("실패로 전체 롤백되어 제안은 PENDING 유지").isEqualTo("PENDING");
        assertThat(idemKeyTxnCount("proposal:" + id)).isZero();
        assertReconciliationClean();
    }

    // ── 픽스처 헬퍼 ──────────────────────────────────────────────────────────────────

    private void receiveColdBrew(String locationCode, int qty) {
        postAndExpectSuccess(request("receipt:PROP-APPROVE-" + locationCode + ":" + qty, "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-200002", "L20260910-B", -qty),
                line("ICN01", locationCode, "SKU-200002", "L20260910-B", qty)));
    }

    private long createMoveProposal(String payloadJson) {
        return createProposal("MOVE", payloadJson,
                List.of(BasisRef.balance("ICN01", "A-01-01-2", "SKU-200002", "L20260910-B")));
    }

    private long createProposal(String proposalType, String payloadJson,
            List<BasisRef> basisRefs) {
        CreateProposalRequest request = new CreateProposalRequest(proposalType, payloadJson, "테스트 사유",
                "agent:test", null, basisRefs);
        CreateProposalOutcome outcome = proposalCreationService.create(request, "ICN01");
        return ((ProposalCreated) outcome).proposalId();
    }

    private void expireProposal(long proposalId) {
        jdbcClient.sql("UPDATE action_proposal SET expires_at = now() - interval '1 minute' WHERE id = :id")
                .param("id", proposalId)
                .update();
    }

    private String proposalStatus(long id) {
        return jdbcClient.sql("SELECT status FROM action_proposal WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    private String decidedBy(long id) {
        return jdbcClient.sql("SELECT decided_by FROM action_proposal WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    private Instant decidedAt(long id) {
        return jdbcClient.sql("SELECT decided_at FROM action_proposal WHERE id = :id")
                .param("id", id)
                .query(Instant.class)
                .single();
    }

    private int idemKeyTxnCount(String idemKey) {
        return jdbcClient.sql("SELECT count(*) FROM inventory_txn WHERE idem_key = :idemKey")
                .param("idemKey", idemKey)
                .query(Integer.class)
                .single();
    }

    private record TxnRow(String actorId, String actorType, Long proposalId) {
    }

    private TxnRow txnRow(long txnId) {
        return jdbcClient.sql("SELECT actor_id, actor_type, proposal_id FROM inventory_txn WHERE id = :id")
                .param("id", txnId)
                .query((rs, rowNum) -> new TxnRow(rs.getString("actor_id"), rs.getString("actor_type"),
                        (Long) rs.getObject("proposal_id")))
                .single();
    }
}
