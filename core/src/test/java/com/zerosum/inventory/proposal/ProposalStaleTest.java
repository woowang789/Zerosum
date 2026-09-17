package com.zerosum.inventory.proposal;

import static org.assertj.core.api.Assertions.assertThat;

import com.zerosum.inventory.domain.BasisRef;
import com.zerosum.inventory.support.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 제안 승인의 STALE 재검증 (docs/08-testing-roadmap.md 4단계 완료 기준 ②, db/usecases/E-proposal.sql
 * UC-E05). basis_snapshot과 실제 재고가 허용 오차(zerosum.proposal.basis.tolerance-pct=0.10)를 넘게
 * 어긋나면 실행하지 않고 STALE로 닫되, 그 판정 자체는 예외가 아니라 결과 값으로 커밋된다
 * (ProposalApprovalService 클래스 주석).
 */
class ProposalStaleTest extends AbstractIntegrationTest {

    @Autowired
    private ProposalCreationService proposalCreationService;

    @Autowired
    private ProposalGateway proposalGateway;

    private static final String MOVE_PAYLOAD = """
            {"txnType":"MOVE","entries":[
               {"wh":"ICN01","loc":"A-01-01-2","sku":"SKU-200002","lot":"L20260910-B","qty":-20},
               {"wh":"ICN01","loc":"A-01-02-1","sku":"SKU-200002","lot":"L20260910-B","qty":20}]}
            """;

    @Test
    void balanceDriftBeyondTolerance_marksStaleAndWritesNoLedger() {
        receiveColdBrew("A-01-01-2", 100);
        long id = createMoveProposalWithBalanceBasis();

        // 승인 전에 50개가 출고되어 근거(가용 100)가 허용 오차(10%, 10개)를 넘게 무너진다 (UC-E05 재현)
        shipFromA0101_2(50);

        ApprovalOutcome outcome = proposalGateway.approve(id, "user:choi.dw");

        assertThat(outcome).isEqualTo(new Stale(id));
        assertThat(proposalStatus(id)).isEqualTo("STALE");
        assertThat(idemKeyTxnCount("proposal:" + id)).as("원장·거래에는 아무것도 쓰이지 않는다").isZero();
        assertReconciliationClean();
    }

    @Test
    void staleDecisionSurvivesCommit() throws Exception {
        receiveColdBrew("A-01-01-2", 100);
        long id = createMoveProposalWithBalanceBasis();
        shipFromA0101_2(50);

        proposalGateway.approve(id, "user:choi.dw");

        // 승인 트랜잭션이 끝난(커밋된) 뒤, 완전히 별도인 커넥션으로 다시 조회해 결정이 남아 있는지 본다 —
        // 예외 경로였다면 롤백되어 사라졌을 값이다.
        try (Connection conn = migratorConnection();
                Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery(
                        "SELECT status, decided_by, decided_at FROM action_proposal WHERE id = " + id)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("status")).isEqualTo("STALE");
            assertThat(rs.getString("decided_by")).isEqualTo("user:choi.dw");
            assertThat(rs.getTimestamp("decided_at")).as("decided_at도 커밋 후 남아 있다").isNotNull();
        }
        assertReconciliationClean();
    }

    @Test
    void warehouseSkuDriftMarksStaleBeforeLocking() {
        receiveColdBrew("A-01-01-2", 100);
        long id = createProposal("MOVE", MOVE_PAYLOAD,
                List.of(BasisRef.warehouseSku("ICN01", "SKU-200002")));

        // 승인 전에 창고 전체 판매 가능 수량(관측 100)이 허용 오차를 넘게 흔들린다
        shipFromA0101_2(90);

        ApprovalOutcome outcome = proposalGateway.approve(id, "user:choi.dw");

        assertThat(outcome).isEqualTo(new Stale(id));
        assertThat(idempotencyRecordCount("proposal:" + id))
                .as("포스팅 전(④단계)에 걸러져 idempotency_record에 잠금 흔적조차 없다").isZero();
        assertReconciliationClean();
    }

    @Test
    void driftWithinTolerance_stillExecutes() {
        receiveColdBrew("A-01-01-2", 100);
        long id = createMoveProposalWithBalanceBasis();

        // 오차 10%의 경계값(정확히 10개)만큼만 흔든다: abs(90-100) <= max(100*0.10, 0) → 허용
        shipFromA0101_2(10);

        ApprovalOutcome outcome = proposalGateway.approve(id, "user:choi.dw");

        assertThat(outcome).as("경계값(<=)은 여전히 실행된다").isInstanceOf(Executed.class);
        assertThat(proposalStatus(id)).isEqualTo("EXECUTED");
        assertReconciliationClean();
    }

    @Test
    void basisBalanceMissingFromLockedSet_marksStale() {
        receiveColdBrew("A-01-01-2", 100);
        receiveColdBrew("B-01-01-1", 30); // 근거 잔액 행은 실제로 존재해야 basis_snapshot에 관측값이 잡힌다

        // MOVE 페이로드는 A-01-01-2 ↔ A-01-02-1만 건드리는데 근거는 B-01-01-1을 짚는다 → 잠근 집합에 없다
        long id = createProposal("MOVE", MOVE_PAYLOAD,
                List.of(BasisRef.balance("ICN01", "B-01-01-1", "SKU-200002", "L20260910-B")));

        ApprovalOutcome outcome = proposalGateway.approve(id, "user:choi.dw");

        assertThat(outcome).as("fail-closed: 근거 잔액이 잠근 집합에 없으면 실행하지 않는다").isEqualTo(new Stale(id));
        assertThat(idemKeyTxnCount("proposal:" + id)).isZero();
        assertReconciliationClean();
    }

    @Test
    void basisSnapshotWithNoObservations_marksStaleAndMovesNoStock() {
        receiveColdBrew("A-01-01-2", 100);

        // 생성 단계의 BASIS_REQUIRED를 지나온 적이 없는 제안 — ai_proposer는 action_proposal에 테이블
        // 단위 INSERT 권한이 있어 MCP(create_proposal)를 거치지 않고 직접 넣을 수 있다(하네스 UC-E12).
        long id = insertPendingProposalWithoutBasis();

        ApprovalOutcome outcome = proposalGateway.approve(id, "user:choi.dw");

        assertThat(outcome).as("대조할 근거가 하나도 없으면 실행하지 않는다").isEqualTo(new Stale(id));
        assertThat(proposalStatus(id)).isEqualTo("STALE");
        // 핵심: BasisRecheck의 두 대조는 관측 목록을 순회하므로, 목록이 비면 한 바퀴도 돌지 않고 통과해
        // 근거를 하나도 보지 않은 채 재고가 움직인다.
        assertThat(onHandQty("ICN01", "A-01-01-2", "SKU-200002", "L20260910-B"))
                .as("출발 로케이션 재고가 그대로다").isEqualTo(100);
        assertThat(onHandQty("ICN01", "A-01-02-1", "SKU-200002", "L20260910-B"))
                .as("도착 로케이션으로 한 개도 옮겨지지 않았다").isZero();
        assertThat(idemKeyTxnCount("proposal:" + id)).as("원장·거래에도 아무것도 쓰이지 않는다").isZero();
        assertReconciliationClean();
    }

    @Test
    void basisSnapshotWithIdentifierlessObservations_marksStaleAndMovesNoStock() {
        receiveColdBrew("A-01-01-2", 100);

        // 관측 목록이 비어 있지 않지만 항목에 식별자가 없다. NULL을 그대로 읽으면 rs.getLong·getInt가
        // 0으로 접혀 "아무것도 가리키지 않는 관측"이 만들어지고, 그것이 현재값 0과 0 대 0으로 대조를
        // 통과한다 — "관측이 하나라도 있으면 진짜 근거다"라고 가정한 ③-2를 그대로 우회하는 길이다.
        // 대기 중인 같은 payload의 제안은 하나만 허용되므로(uq_proposal_pending) 수량만 다르게 둔다.
        long noIdWarehouseSku = insertPendingProposal(MOVE_PAYLOAD, """
                {"observations":[{"scope":"warehouse_sku"}]}""");
        long noIdBalance = insertPendingProposal(movePayload(15), """
                {"observations":[{"scope":"balance"}]}""");

        ApprovalOutcome warehouseSkuOutcome = proposalGateway.approve(noIdWarehouseSku, "user:choi.dw");
        ApprovalOutcome balanceOutcome = proposalGateway.approve(noIdBalance, "user:choi.dw");

        // 핵심: 식별자 없는 관측을 버리지 않으면 warehouse_sku 쪽은 ④를 0 대 0으로 통과해 MOVE가
        // 실제로 포스팅됐다 — 근거를 하나도 대조하지 않은 채 재고가 움직인 것이다.
        assertThat(onHandQty("ICN01", "A-01-01-2", "SKU-200002", "L20260910-B"))
                .as("출발 로케이션 재고가 그대로다").isEqualTo(100);
        assertThat(onHandQty("ICN01", "A-01-02-1", "SKU-200002", "L20260910-B"))
                .as("도착 로케이션으로 한 개도 옮겨지지 않았다").isZero();

        assertThat(warehouseSkuOutcome).as("식별자 없는 warehouse_sku 항목은 관측이 아니다")
                .isEqualTo(new Stale(noIdWarehouseSku));
        assertThat(balanceOutcome).as("balance_id 없는 항목도 마찬가지다").isEqualTo(new Stale(noIdBalance));
        assertThat(proposalStatus(noIdWarehouseSku)).isEqualTo("STALE");
        assertThat(proposalStatus(noIdBalance)).isEqualTo("STALE");
        assertThat(idemKeyTxnCount("proposal:" + noIdWarehouseSku)).isZero();
        assertThat(idemKeyTxnCount("proposal:" + noIdBalance)).isZero();
        // balance 쪽은 ⑥(잔액 행을 잠근 뒤)에서 걸려 STALE이 되기는 했지만 포스팅까지 갔다 —
        // 이제는 ③-2에서 걸러져 잠금 흔적조차 남지 않는다.
        assertThat(idempotencyRecordCount("proposal:" + noIdBalance))
                .as("포스팅 전에 걸러져 idempotency_record에 잠금 흔적조차 없다").isZero();
        assertReconciliationClean();
    }

    // ── 픽스처 헬퍼 ──────────────────────────────────────────────────────────────────

    /**
     * 생성 검증을 거치지 않고 들어온, 근거가 비어 있는 PENDING 제안. basis_snapshot의 "비어 있음"은
     * 하네스 UC-E12가 넣는 값과 같은 {@code {"observations": []}}다 — ProposalRepository의 파싱
     * SQL이 {@code jsonb_array_elements(basis_snapshot -> 'observations')}로 읽으므로 두 스코프 모두
     * 빈 목록이 된다.
     */
    private long insertPendingProposalWithoutBasis() {
        return insertPendingProposal(MOVE_PAYLOAD, "{\"observations\": []}");
    }

    /** 생성 검증(BASIS_REQUIRED)을 거치지 않고 command_payload·basis_snapshot을 있는 그대로 넣은 PENDING 제안. */
    private long insertPendingProposal(String payloadJson, String basisSnapshotJson) {
        return jdbcClient.sql("""
                INSERT INTO action_proposal (proposal_type, command_payload, basis_snapshot, rationale,
                                             proposed_by, expires_at)
                VALUES ('MOVE', CAST(:payload AS JSONB), CAST(:basis AS JSONB),
                        '근거 없이 들어온 제안', 'agent:probe', now() + INTERVAL '60 minutes')
                RETURNING id
                """)
                .param("payload", payloadJson)
                .param("basis", basisSnapshotJson)
                .query(Long.class)
                .single();
    }

    /** MOVE_PAYLOAD와 같은 이동, 수량만 다르다 — uq_proposal_pending에 걸리지 않게 하려는 것뿐이다. */
    private static String movePayload(int qty) {
        return """
                {"txnType":"MOVE","entries":[
                   {"wh":"ICN01","loc":"A-01-01-2","sku":"SKU-200002","lot":"L20260910-B","qty":-%d},
                   {"wh":"ICN01","loc":"A-01-02-1","sku":"SKU-200002","lot":"L20260910-B","qty":%d}]}
                """.formatted(qty, qty);
    }

    private void receiveColdBrew(String locationCode, int qty) {
        postAndExpectSuccess(request("receipt:STALE-" + locationCode + ":" + qty, "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-200002", "L20260910-B", -qty),
                line("ICN01", locationCode, "SKU-200002", "L20260910-B", qty)));
    }

    private void shipFromA0101_2(int qty) {
        postAndExpectSuccess(request("ship:STALE-DRIFT-" + qty, "SHIPMENT", null, null,
                line("ICN01", "A-01-01-2", "SKU-200002", "L20260910-B", -qty),
                line("ICN01", "V-CUSTOMER", "SKU-200002", "L20260910-B", qty)));
    }

    private long createMoveProposalWithBalanceBasis() {
        return createProposal("MOVE", MOVE_PAYLOAD,
                List.of(BasisRef.balance("ICN01", "A-01-01-2", "SKU-200002", "L20260910-B")));
    }

    private long createProposal(String proposalType, String payloadJson,
            List<BasisRef> basisRefs) {
        CreateProposalRequest request = new CreateProposalRequest(proposalType, payloadJson, "테스트 사유",
                "agent:test", null, basisRefs);
        CreateProposalOutcome outcome = proposalCreationService.create(request, "ICN01");
        return ((ProposalCreated) outcome).proposalId();
    }

    private String proposalStatus(long id) {
        return jdbcClient.sql("SELECT status FROM action_proposal WHERE id = :id")
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

    private int idempotencyRecordCount(String idemKey) {
        return jdbcClient.sql("SELECT count(*) FROM idempotency_record WHERE idem_key = :idemKey")
                .param("idemKey", idemKey)
                .query(Integer.class)
                .single();
    }
}
