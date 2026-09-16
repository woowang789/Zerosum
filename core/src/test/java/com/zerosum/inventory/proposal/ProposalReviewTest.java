package com.zerosum.inventory.proposal;

import static org.assertj.core.api.Assertions.assertThat;

import com.zerosum.inventory.domain.BasisRef;
import com.zerosum.inventory.repository.ProposalRepository;
import com.zerosum.inventory.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 제안 승인 화면이 필요로 하는 코어 경로(docs/08-testing-roadmap.md 4단계 완료 기준: 대기 목록·상세·근거
 * 대조·거부·이슈 연결). 근거 대조(ProposalBasisReviewService)는 {@link BasisRecheck}를 그대로 쓰므로
 * 승인 시점 재검증(ProposalApprovalService)과 같은 결과를 내야 한다 — 그 일치를 확인하는 것이 이 파일의
 * 핵심 테스트다.
 */
class ProposalReviewTest extends AbstractIntegrationTest {

    @Autowired
    private ProposalCreationService proposalCreationService;

    @Autowired
    private ProposalGateway proposalGateway;

    @Autowired
    private ProposalRepository proposalRepository;

    @Autowired
    private ProposalBasisReviewService proposalBasisReviewService;

    @Test
    void listPendingReturnsOnlyOwnWarehouseAndUnexpired() {
        long ownId = createProposal("MOVE", movePayload("ICN01", "A-01-01-2", "A-01-02-1", 5), List.of(), "ICN01");
        createProposal("MOVE", movePayload("YIT01", "A-01-01-2", "A-01-02-1", 6), List.of(), "YIT01");
        long expiredId = createProposal("MOVE", movePayload("ICN01", "A-01-01-2", "A-01-02-1", 7), List.of(),
                "ICN01");
        expireProposal(expiredId);

        List<ProposalRepository.PendingProposalRow> rows = proposalRepository.listPending("ICN01", 10);

        assertThat(rows).extracting(ProposalRepository.PendingProposalRow::id)
                .as("다른 창고 제안과 만료된 제안은 섞이지 않는다")
                .containsExactly(ownId);
        assertReconciliationClean();
    }

    @Test
    void detailComparisonMatchesApprovalOutcome() {
        receiveColdBrew("A-01-01-2", 100); // validId의 근거 잔액
        receiveColdBrew("B-01-01-1", 100); // brokenId의 근거 잔액
        long validId = createProposal("MOVE", movePayload("ICN01", "A-01-01-2", "A-01-02-1", 20),
                List.of(BasisRef.balance("ICN01", "A-01-01-2", "SKU-200002", "L20260910-B")), "ICN01");
        long brokenId = createProposal("MOVE", movePayload("ICN01", "B-01-01-1", "A-02-01-1", 21),
                List.of(BasisRef.balance("ICN01", "B-01-01-1", "SKU-200002", "L20260910-B")), "ICN01");

        // 승인 전에 brokenId의 근거 잔액만 허용 오차(10%, 10개)를 넘게 무너뜨린다. validId의 근거는 그대로다.
        ship("B-01-01-1", 50);

        ProposalBasisReviewService.BasisReview validReview = proposalBasisReviewService.review(validId);
        ProposalBasisReviewService.BasisReview brokenReview = proposalBasisReviewService.review(brokenId);

        assertThat(validReview.allValid()).as("근거가 그대로면 화면은 유효하다고 본다").isTrue();
        assertThat(validReview.balance()).hasSize(1);
        assertThat(validReview.balance().get(0).comparison())
                .isEqualTo(new BasisRecheck.Comparison(100, 100, 0, 10.0, true));

        assertThat(brokenReview.allValid()).as("근거가 무너지면 화면은 무효하다고 본다").isFalse();
        assertThat(brokenReview.balance()).hasSize(1);
        assertThat(brokenReview.balance().get(0).comparison())
                .isEqualTo(new BasisRecheck.Comparison(100, 50, -50, 10.0, false));

        // 상세 조회(할 일 2)도 같은 제안을 올바르게 돌려주는지 함께 확인한다.
        ProposalRepository.ProposalDetail detail = proposalRepository.detail(validId).orElseThrow();
        assertThat(detail.proposalType()).isEqualTo("MOVE");
        assertThat(detail.status()).isEqualTo("PENDING");
        assertThat(detail.entries()).hasSize(2);
        assertThat(detail.issueId()).isNull();

        // 핵심: 화면의 판정과 승인 결과가 어긋나지 않는다.
        ApprovalOutcome validOutcome = proposalGateway.approve(validId, "user:choi.dw");
        assertThat(validOutcome).as("화면이 유효하다고 본 제안은 실행된다").isInstanceOf(Executed.class);

        ApprovalOutcome brokenOutcome = proposalGateway.approve(brokenId, "user:choi.dw");
        assertThat(brokenOutcome).as("화면이 무너졌다고 본 제안은 STALE로 닫힌다").isEqualTo(new Stale(brokenId));

        assertReconciliationClean();
    }

    @Test
    void rejectRecordsWhoAndNote() {
        long id = createProposal("MOVE", movePayload("ICN01", "A-01-01-2", "A-01-02-1", 8), List.of(), "ICN01");

        proposalGateway.reject(id, "user:ops", "수량이 실측과 다르다");

        assertThat(proposalStatus(id)).isEqualTo("REJECTED");
        assertThat(decidedBy(id)).isEqualTo("user:ops");
        assertThat(decidedAt(id)).isNotNull();
        assertThat(decisionNote(id)).isEqualTo("수량이 실측과 다르다");
        assertThat(idemKeyTxnCount("proposal:" + id)).as("거부는 원장에 아무것도 남기지 않는다").isZero();
        assertReconciliationClean();
    }

    @Test
    void rejectedProposalCannotBeApproved() {
        long id = createProposal("MOVE", movePayload("ICN01", "A-01-01-2", "A-01-02-1", 9), List.of(), "ICN01");
        proposalGateway.reject(id, "user:ops", "재작성 요청");

        ApprovalOutcome outcome = proposalGateway.approve(id, "user:choi.dw");

        assertThat(outcome).isEqualTo(new AlreadyDecided(id, "REJECTED"));
        assertReconciliationClean();
    }

    @Test
    void approvingIssueProposalResolvesTheIssue() {
        receiveColdBrew("A-01-01-1", 50);
        long issueId = insertOpenIssue();
        String payload = """
                {"txnType":"ADJUSTMENT","reasonCode":"CYCLE_COUNT","issueId":%d,"entries":[
                   {"wh":"ICN01","loc":"A-01-01-1","sku":"SKU-200002","lot":"L20260910-B","qty":5},
                   {"wh":"ICN01","loc":"V-ADJUST","sku":"SKU-200002","lot":"L20260910-B","qty":-5}]}
                """.formatted(issueId);
        long proposalId = createProposal("ADJUSTMENT", payload,
                List.of(BasisRef.balance("ICN01", "A-01-01-1", "SKU-200002", "L20260910-B")), "ICN01");

        ApprovalOutcome outcome = proposalGateway.approve(proposalId, "user:ops");

        assertThat(outcome).isInstanceOf(Executed.class);
        long txnId = ((Executed) outcome).txnId();
        assertThat(issueStatus(issueId)).as("이슈에서 나온 제안이 승인되면 이슈도 자동으로 종결된다").isEqualTo("RESOLVED");
        assertThat(resolvedTxnId(issueId)).isEqualTo(txnId);
        assertReconciliationClean();
    }

    // ── 픽스처 헬퍼 ──────────────────────────────────────────────────────────────────

    private String movePayload(String warehouseCode, String fromLoc, String toLoc, int qty) {
        return """
                {"txnType":"MOVE","entries":[
                   {"wh":"%s","loc":"%s","sku":"SKU-200002","lot":"L20260910-B","qty":-%d},
                   {"wh":"%s","loc":"%s","sku":"SKU-200002","lot":"L20260910-B","qty":%d}]}
                """.formatted(warehouseCode, fromLoc, qty, warehouseCode, toLoc, qty);
    }

    private void receiveColdBrew(String locationCode, int qty) {
        postAndExpectSuccess(request("receipt:REVIEW-" + locationCode + ":" + qty, "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-200002", "L20260910-B", -qty),
                line("ICN01", locationCode, "SKU-200002", "L20260910-B", qty)));
    }

    private void ship(String locationCode, int qty) {
        postAndExpectSuccess(request("ship:REVIEW-" + locationCode + ":" + qty, "SHIPMENT", null, null,
                line("ICN01", locationCode, "SKU-200002", "L20260910-B", -qty),
                line("ICN01", "V-CUSTOMER", "SKU-200002", "L20260910-B", qty)));
    }

    private long createProposal(String proposalType, String payloadJson, List<BasisRef> basisRefs,
            String allowedWarehouseCode) {
        CreateProposalRequest request = new CreateProposalRequest(proposalType, payloadJson, "테스트 사유",
                "agent:test", null, basisRefs);
        CreateProposalOutcome outcome = proposalCreationService.create(request, allowedWarehouseCode);
        return ((ProposalCreated) outcome).proposalId();
    }

    private void expireProposal(long proposalId) {
        jdbcClient.sql("UPDATE action_proposal SET expires_at = now() - interval '1 minute' WHERE id = :id")
                .param("id", proposalId)
                .update();
    }

    /**
     * 제안이 가리킬 이슈 하나. location_id를 채운다 — 이슈가 창고에 매이는 유일한 길이고
     * (inventory_issue → location → warehouse), 제안 생성 검증이 그것으로 호출자 창고를 대조하기
     * 때문이다. 실제로 이슈를 여는 경로도 전부 채운다(ReconciliationService, CountResultRepository).
     * 이 제안이 고치는 로케이션(A-01-01-1)에 건다.
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

    private String decisionNote(long id) {
        return jdbcClient.sql("SELECT decision_note FROM action_proposal WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    private int idemKeyTxnCount(String idemKey) {
        return jdbcClient.sql("SELECT count(*) FROM inventory_txn WHERE idem_key = :idemKey")
                .param("idemKey", idemKey)
                .query(Integer.class)
                .single();
    }

    private String issueStatus(long issueId) {
        return jdbcClient.sql("SELECT status FROM inventory_issue WHERE id = :id")
                .param("id", issueId)
                .query(String.class)
                .single();
    }

    private Long resolvedTxnId(long issueId) {
        return jdbcClient.sql("SELECT resolved_txn_id FROM inventory_issue WHERE id = :id")
                .param("id", issueId)
                .query((rs, rowNum) -> (Long) rs.getObject("resolved_txn_id"))
                .single();
    }
}
