package com.zerosum.inventory.proposal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zerosum.inventory.domain.BasisRef;
import com.zerosum.inventory.domain.PostingException;
import com.zerosum.inventory.posting.PostingLineInput;
import com.zerosum.inventory.repository.ProposalRepository;
import com.zerosum.inventory.support.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 승인 실패의 롤백 의미론. STALE·AlreadyDecided·ProposalExpired는 결과 값이지만, PostingException
 * (재고 부족 등 진짜 예외)은 여전히 던져져 승인 트랜잭션 전체를 롤백시킨다 — 그리고 그 설계를 예외를
 * 잡아 우회하려 하면 왜 안 되는지를 고정한다 (ProposalApprovalService 클래스 주석: "STALE은 예외가 아니라
 * 결과 값이다").
 */
class ProposalRollbackSemanticsTest extends AbstractIntegrationTest {

    @Autowired
    private ProposalCreationService proposalCreationService;

    @Autowired
    private ProposalGateway proposalGateway;

    @Autowired
    private ProposalCommandExecutor executor;

    @Autowired
    private ProposalRepository proposalRepo;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private static final String MOVE_PAYLOAD = """
            {"txnType":"MOVE","entries":[
               {"wh":"ICN01","loc":"A-01-01-2","sku":"SKU-200002","lot":"L20260910-B","qty":-20},
               {"wh":"ICN01","loc":"A-01-02-1","sku":"SKU-200002","lot":"L20260910-B","qty":20}]}
            """;

    @Test
    void insufficientStockRollsBackApproval_proposalStaysPending() {
        receiveColdBrew("A-01-01-2", 50);
        String payload = """
                {"txnType":"MOVE","entries":[
                   {"wh":"ICN01","loc":"A-01-01-2","sku":"SKU-200002","lot":"L20260910-B","qty":-1000},
                   {"wh":"ICN01","loc":"A-01-02-1","sku":"SKU-200002","lot":"L20260910-B","qty":1000}]}
                """;
        long id = createMoveProposal(payload);

        assertThatThrownBy(() -> proposalGateway.approve(id, "user:choi.dw"))
                .isInstanceOf(PostingException.class)
                .extracting(ex -> ((PostingException) ex).code())
                .isEqualTo("INSUFFICIENT_STOCK");

        assertThat(proposalStatus(id)).as("실패로 전체 롤백되어 제안은 PENDING 유지").isEqualTo("PENDING");
        assertThat(decidedByOrNull(id)).as("결정 기록도 함께 롤백된다").isNull();
        assertThat(idemKeyTxnCount("proposal:" + id)).isZero();
        assertReconciliationClean();
    }

    /**
     * 프로덕션 코드(ProposalApprovalService)는 PostingException을 잡지 않는다. 여기서는 "잡으면 왜 안
     * 되는지"를 재현한다 — PostingService.post()도 그 자체로 @Transactional(REQUIRED)인 별도 빈 메서드라,
     * 그 안에서 던져진 예외는 (호출부가 밖에서 잡아도) 이미 물리 트랜잭션을 rollback-only로 표시해 버린다.
     * 그 뒤 커밋을 시도하면 UnexpectedRollbackException이 난다 — TransactionTemplate으로 그 상황만
     * 재현하고, 프로덕션 코드는 전혀 건드리지 않는다.
     */
    @Test
    void catchingPostingExceptionCannotRecordDecision() {
        receiveColdBrew("A-01-01-2", 50);
        long id = createMoveProposal(MOVE_PAYLOAD);

        List<PostingLineInput> tooMuch = List.of(
                line("ICN01", "A-01-01-2", "SKU-200002", "L20260910-B", -1000),
                line("ICN01", "A-01-02-1", "SKU-200002", "L20260910-B", 1000));

        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            proposalRepo.lockForUpdate(id);
            try {
                executor.execute(id, "MOVE", null, tooMuch, "user:choi.dw", balances -> true);
            } catch (PostingException e) {
                // 프로덕션이라면 절대 하지 않을 일 — 예외를 잡고 상태를 기록하려 한다.
                proposalRepo.markStale(id, "user:choi.dw");
            }
        })).isInstanceOf(UnexpectedRollbackException.class);

        // 커밋 자체가 거부됐으므로 markStale 시도도 함께 사라지고 제안은 원래 상태(PENDING)로 남는다
        assertThat(proposalStatus(id)).as("커밋이 거부되어 예외 경로의 상태 변경은 남지 않는다").isEqualTo("PENDING");
        assertReconciliationClean();
    }

    // ── 픽스처 헬퍼 ──────────────────────────────────────────────────────────────────

    private void receiveColdBrew(String locationCode, int qty) {
        postAndExpectSuccess(request("receipt:ROLLBACK-" + locationCode + ":" + qty, "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-200002", "L20260910-B", -qty),
                line("ICN01", locationCode, "SKU-200002", "L20260910-B", qty)));
    }

    private long createMoveProposal(String payloadJson) {
        CreateProposalRequest request = new CreateProposalRequest("MOVE", payloadJson, "테스트 사유", "agent:test", null,
                List.of(BasisRef.balance("ICN01", "A-01-01-2", "SKU-200002", "L20260910-B")));
        CreateProposalOutcome outcome = proposalCreationService.create(request, "ICN01");
        return ((ProposalCreated) outcome).proposalId();
    }

    private String proposalStatus(long id) {
        return jdbcClient.sql("SELECT status FROM action_proposal WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    private String decidedByOrNull(long id) {
        return jdbcClient.sql("SELECT decided_by FROM action_proposal WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private int idemKeyTxnCount(String idemKey) {
        return jdbcClient.sql("SELECT count(*) FROM inventory_txn WHERE idem_key = :idemKey")
                .param("idemKey", idemKey)
                .query(Integer.class)
                .single();
    }
}
