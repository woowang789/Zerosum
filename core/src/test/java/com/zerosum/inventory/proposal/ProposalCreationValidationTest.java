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
 * 생성 단계 검증이 payload 직렬화 변형에 속지 않는지 본다.
 *
 * <p>생성 검증의 목적은 "승인 시점에 DB CHECK로 터져서 제안이 PENDING인 채 버튼이 먹지 않는 상태"를
 * 미리 막는 것이다. 그런데 검증이 원본 JSON 텍스트를 보고 판단하면, 정규화가 버리는 위치에 값이 있어도
 * 통과시켜 버린다 — 정규화는 최상위 reasonCode만 남기고 엔트리 안쪽 값은 버리기 때문이다.
 * 즉 재시도 정규화가 존재하는 바로 그 이유(같은 뜻을 여러 모양으로 직렬화할 수 있다)가
 * 검증을 무력화하는 경로가 된다.
 */
class ProposalCreationValidationTest extends AbstractIntegrationTest {

    @Autowired
    private ProposalCreationService proposalCreationService;

    /** reasonCode가 최상위가 아니라 엔트리 안쪽에 있다. 정규화는 이 값을 버린다. */
    private static final String ADJUSTMENT_REASON_INSIDE_ENTRY = """
            {"txnType":"ADJUSTMENT","entries":[
               {"wh":"ICN01","loc":"A-01-01-2","sku":"SKU-200002","lot":"L20260910-B","qty":-5,
                "reasonCode":"DAMAGE"},
               {"wh":"ICN01","loc":"V-ADJUST","sku":"SKU-200002","lot":"L20260910-B","qty":5}]}
            """;

    /** proposalType은 MOVE라고 해 놓고 payload의 txnType은 ADJUSTMENT다. */
    private static final String ADJUSTMENT_PAYLOAD_UNDER_MOVE_TYPE = """
            {"txnType":"ADJUSTMENT","entries":[
               {"wh":"ICN01","loc":"A-01-01-2","sku":"SKU-200002","lot":"L20260910-B","qty":-5},
               {"wh":"ICN01","loc":"V-ADJUST","sku":"SKU-200002","lot":"L20260910-B","qty":5}]}
            """;

    /** entries 전부가 ICN01이 아니라 YIT01(db/03-seed.sql)을 가리킨다. */
    private static final String MOVE_PAYLOAD_OTHER_WAREHOUSE = """
            {"txnType":"MOVE","entries":[
               {"wh":"YIT01","loc":"A-01-01-2","sku":"SKU-200002","lot":"L20260910-B","qty":-20},
               {"wh":"YIT01","loc":"A-01-02-1","sku":"SKU-200002","lot":"L20260910-B","qty":20}]}
            """;

    /** entries는 ICN01뿐인 정상 MOVE payload — basisRef만 다른 창고로 바꿔 보는 테스트용. */
    private static final String MOVE_PAYLOAD_ICN01 = """
            {"txnType":"MOVE","entries":[
               {"wh":"ICN01","loc":"A-01-01-2","sku":"SKU-200002","lot":"L20260910-B","qty":-20},
               {"wh":"ICN01","loc":"A-01-02-1","sku":"SKU-200002","lot":"L20260910-B","qty":20}]}
            """;

    @Test
    void adjustmentWithReasonCodeOnlyInsideEntryIsRejected() {
        receiveColdBrew(100);

        assertThatThrownBy(() -> proposalCreationService.create(
                requestOfType("ADJUSTMENT", ADJUSTMENT_REASON_INSIDE_ENTRY), "ICN01"))
                .as("정규화가 버리는 위치의 reasonCode는 없는 것으로 봐야 한다")
                .isInstanceOf(ProposalException.class);

        assertThat(proposalCount()).as("거부됐으므로 제안이 생기지 않는다").isZero();
        assertReconciliationClean();
    }

    @Test
    void proposalTypeMustAgreeWithPayloadTxnType() {
        receiveColdBrew(100);

        assertThatThrownBy(() -> proposalCreationService.create(
                requestOfType("MOVE", ADJUSTMENT_PAYLOAD_UNDER_MOVE_TYPE), "ICN01"))
                .as("proposal_type과 payload의 txnType이 다르면 어느 쪽 규칙을 적용할지 알 수 없다")
                .isInstanceOf(ProposalException.class);

        assertThat(proposalCount()).isZero();
        assertReconciliationClean();
    }

    @Test
    void proposalWithoutEntriesIsRejected() {
        receiveColdBrew(100);

        assertThatThrownBy(() -> proposalCreationService.create(
                requestOfType("MOVE", "{\"txnType\":\"MOVE\",\"entries\":[]}"), "ICN01"))
                .as("줄이 없는 제안은 승인해도 만들 거래가 없다")
                .isInstanceOf(ProposalException.class);

        assertThat(proposalCount()).isZero();
        assertReconciliationClean();
    }

    /**
     * basisRefs가 빈 제안은 entries가 빈 제안과 같은 이유로 생성 단계에서 거부해야 한다.
     *
     * <p>basisRefs가 비면 basis_snapshot의 observations가 비고, 승인 시점 재검증
     * (BasisRecheck의 warehouseSkuObservationsHold·balanceObservationsHold)은 둘 다 관측 목록을
     * 순회하는 모양이라 목록이 비면 <b>한 바퀴도 돌지 않고 통과한다</b>. 즉 아무것도 대조하지 않은 채
     * 재고가 정정된다 — 근거를 요구하는 제안 흐름에서 근거만 빼면 검증이 사라지는 셈이다.
     *
     * <p>뒤쪽 절반이 없으면 앞의 거부 단언은 공허하다 — payload가 어딘가 잘못돼 거부됐어도 똑같이
     * 통과한다. 같은 payload에 근거 하나만 붙인 제안이 만들어지는 것까지 봐야 "basisRefs 때문에
     * 거부했다"가 되고, 그 제안의 basis_snapshot에 실제로 대조할 관측이 들어 있는 것까지 봐야
     * "근거가 있다"가 승인 시점에 의미를 갖는다.
     */
    @Test
    void proposalWithoutBasisRefsIsRejected() {
        receiveColdBrew(100);

        CreateProposalRequest noBasis = new CreateProposalRequest("MOVE", MOVE_PAYLOAD_ICN01, "테스트 사유",
                "agent:test", null, List.of());

        assertThatThrownBy(() -> proposalCreationService.create(noBasis, "ICN01"))
                .as("basisRefs가 빈 제안은 승인 시점에 대조할 근거가 없다")
                .isInstanceOf(ProposalException.class)
                .extracting(ex -> ((ProposalException) ex).code())
                .isEqualTo("BASIS_REQUIRED");
        assertThat(proposalCount()).as("거부됐으므로 제안이 생기지 않는다").isZero();

        assertThat(proposalCreationService.create(requestOfType("MOVE", MOVE_PAYLOAD_ICN01), "ICN01"))
                .as("근거 하나만 붙인 같은 제안은 만들어진다 — 위 거부는 basisRefs 때문이다")
                .isInstanceOf(ProposalCreated.class);
        assertThat(proposalCount()).isOne();
        assertThat(observationCount())
                .as("만들어진 제안의 basis_snapshot에는 승인 시점에 대조할 관측이 실제로 들어 있다")
                .isOne();
        assertReconciliationClean();
    }

    /** ICN01용 MCP 서버 권한으로 YIT01 entries가 든 제안을 만들려 하면 거부해야 한다 — 쓰기 표면의 창고 누수. */
    @Test
    void proposalTouchingAnotherWarehouseIsRejected() {
        receiveColdBrew(100);

        assertThatThrownBy(() -> proposalCreationService.create(
                requestOfType("MOVE", MOVE_PAYLOAD_OTHER_WAREHOUSE), "ICN01"))
                .as("commandPayloadJson의 entries가 호출자 권한 밖 창고(YIT01)를 가리키면 거부해야 한다")
                .isInstanceOf(ProposalException.class);

        assertThat(proposalCount()).as("거부됐으므로 제안이 생기지 않는다").isZero();
        assertReconciliationClean();
    }

    /** entries는 ICN01뿐이라도 basisRef가 다른 창고를 가리키면 거부해야 한다 — LLM이 근거를 잘못 짚은 경우. */
    @Test
    void basisRefFromAnotherWarehouseIsRejected() {
        receiveColdBrew(100);

        CreateProposalRequest request = new CreateProposalRequest("MOVE", MOVE_PAYLOAD_ICN01, "테스트 사유",
                "agent:test", null,
                List.of(BasisRef.balance("YIT01", "A-01-01-2", "SKU-200002", "L20260910-B")));

        assertThatThrownBy(() -> proposalCreationService.create(request, "ICN01"))
                .as("basisRefs가 호출자 권한 밖 창고(YIT01)를 가리키면 거부해야 한다")
                .isInstanceOf(ProposalException.class);

        assertThat(proposalCount()).isZero();
        assertReconciliationClean();
    }

    /**
     * entries가 전부 자기 창고여도, payload 최상위 issueId가 남의 창고 이슈를 가리키면 거부해야 한다.
     *
     * <p>생성 검증은 issueId에 대해 <b>상태만</b> 봤다(OPEN·ACKED인가). 그래서 ICN01 전용 MCP 서버가
     * entries를 전부 ICN01로 두고 issueId만 YIT01 이슈로 적으면 그대로 생성됐고, 사람이 승인하는 순간
     * 그 YIT01 이슈가 RESOLVED가 됐다(승인 경로가 payload의 issueId로 이슈를 닫는다). YIT01의 실제
     * 불일치는 하나도 고쳐지지 않았는데 담당자의 "봐야 할 이슈"에서 사라진다.
     *
     * <p>배치가 여는 이슈(PROJECTION_MISMATCH 등)는 다음 회차에 다시 열리지만,
     * COUNT_VARIANCE는 실사 제출 때 한 번만 생성되므로(CountResultRepository) 잘못 닫히면 되살아날
     * 경로가 없다. 그래서 여기서 막지 못하면 복구되지 않는다.
     *
     * <p>뒤쪽 절반이 없으면 앞의 거부 단언은 공허하다 — issueId가 붙었다는 이유만으로 거부해도,
     * 이슈가 애초에 만들어지지 않았어도 똑같이 통과한다. 창고만 바꾼 같은 모양이 통과하는 것까지
     * 봐야 "창고 때문에 거부했다"가 된다.
     */
    @Test
    void proposalPointingAtAnotherWarehouseIssueIsRejected() {
        receiveColdBrew(100);
        long yitIssueId = insertOpenIssue("YIT01");
        long icnIssueId = insertOpenIssue("ICN01");

        assertThatThrownBy(() -> proposalCreationService.create(issueRequest(yitIssueId), "ICN01"))
                .as("issueId가 호출자 권한 밖 창고(YIT01)의 이슈를 가리키면 거부해야 한다")
                .isInstanceOf(ProposalException.class);
        assertThat(proposalCount()).as("거부됐으므로 제안이 생기지 않는다").isZero();

        assertThat(proposalCreationService.create(issueRequest(icnIssueId), "ICN01"))
                .as("창고만 바꾼 같은 모양의 제안은 만들어진다 — 위 거부는 창고 때문이다")
                .isInstanceOf(ProposalCreated.class);
        assertThat(proposalCount()).isOne();
        assertReconciliationClean();
    }

    /** entries는 전부 ICN01인 정상 MOVE인데 최상위 issueId만 인자로 받은 이슈를 가리킨다. */
    private static CreateProposalRequest issueRequest(long issueId) {
        String payload = """
                {"txnType":"MOVE","issueId":%d,"entries":[
                   {"wh":"ICN01","loc":"A-01-01-2","sku":"SKU-200002","lot":"L20260910-B","qty":-20},
                   {"wh":"ICN01","loc":"A-01-02-1","sku":"SKU-200002","lot":"L20260910-B","qty":20}]}
                """.formatted(issueId);
        return new CreateProposalRequest("MOVE", payload, "이슈 복구", "agent:test", null,
                List.of(BasisRef.balance("ICN01", "A-01-01-2", "SKU-200002", "L20260910-B")));
    }

    /** 해당 창고의 A-01-01-2에 OPEN 이슈 하나. 두 창고가 같은 로케이션 코드를 갖는다(db/03-seed.sql). */
    private long insertOpenIssue(String warehouseCode) {
        return jdbcClient.sql("""
                INSERT INTO inventory_issue (issue_type, severity, location_id, sku_id, lot_id, detail)
                SELECT 'PROJECTION_MISMATCH', 'CRITICAL', l.id, s.id, lo.id, '{}'::JSONB
                FROM location l, warehouse w, sku s, lot lo
                WHERE w.code = :wh AND l.warehouse_id = w.id AND l.code = 'A-01-01-2'
                  AND s.code = 'SKU-200002' AND lo.sku_id = s.id AND lo.lot_no = 'L20260910-B'
                RETURNING id
                """)
                .param("wh", warehouseCode)
                .query(Long.class)
                .single();
    }

    private void receiveColdBrew(int qty) {
        postAndExpectSuccess(request("receipt:VALIDATION:" + qty, "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-200002", "L20260910-B", -qty),
                line("ICN01", "A-01-01-2", "SKU-200002", "L20260910-B", qty)));
    }

    private static CreateProposalRequest requestOfType(String proposalType, String payloadJson) {
        return new CreateProposalRequest(proposalType, payloadJson, "테스트 사유", "agent:test", null,
                List.of(BasisRef.balance("ICN01", "A-01-01-2", "SKU-200002", "L20260910-B")));
    }

    private int proposalCount() {
        return jdbcClient.sql("SELECT count(*) FROM action_proposal").query(Integer.class).single();
    }

    /** 유일한 제안의 basis_snapshot에 든 관측 개수 — basisRefs를 받았다는 것과 대조할 값이 있다는 것은 다르다. */
    private int observationCount() {
        return jdbcClient.sql("SELECT jsonb_array_length(basis_snapshot -> 'observations') FROM action_proposal")
                .query(Integer.class)
                .single();
    }
}
