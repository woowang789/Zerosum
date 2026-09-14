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

    @Test
    void adjustmentWithReasonCodeOnlyInsideEntryIsRejected() {
        receiveColdBrew(100);

        assertThatThrownBy(() -> proposalCreationService.create(
                requestOfType("ADJUSTMENT", ADJUSTMENT_REASON_INSIDE_ENTRY)))
                .as("정규화가 버리는 위치의 reasonCode는 없는 것으로 봐야 한다")
                .isInstanceOf(ProposalException.class);

        assertThat(proposalCount()).as("거부됐으므로 제안이 생기지 않는다").isZero();
        assertReconciliationClean();
    }

    @Test
    void proposalTypeMustAgreeWithPayloadTxnType() {
        receiveColdBrew(100);

        assertThatThrownBy(() -> proposalCreationService.create(
                requestOfType("MOVE", ADJUSTMENT_PAYLOAD_UNDER_MOVE_TYPE)))
                .as("proposal_type과 payload의 txnType이 다르면 어느 쪽 규칙을 적용할지 알 수 없다")
                .isInstanceOf(ProposalException.class);

        assertThat(proposalCount()).isZero();
        assertReconciliationClean();
    }

    @Test
    void proposalWithoutEntriesIsRejected() {
        receiveColdBrew(100);

        assertThatThrownBy(() -> proposalCreationService.create(
                requestOfType("MOVE", "{\"txnType\":\"MOVE\",\"entries\":[]}")))
                .as("줄이 없는 제안은 승인해도 만들 거래가 없다")
                .isInstanceOf(ProposalException.class);

        assertThat(proposalCount()).isZero();
        assertReconciliationClean();
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
}
