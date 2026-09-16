package com.zerosum.inventory.count;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zerosum.inventory.allocation.AllocateRequest;
import com.zerosum.inventory.allocation.AllocationGateway;
import com.zerosum.inventory.allocation.AllocationResult;
import com.zerosum.inventory.domain.AllocationId;
import com.zerosum.inventory.domain.PostingException;
import com.zerosum.inventory.support.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 정정 롤백 — 실재고가 할당량보다 적어지는 정정은 실패하고, 표시 삭제까지 함께 롤백되어 로케이션이 계속
 * 잠겨 있어야 한다 (docs/05-count-session.md 정정 절차, 2단계 완료 기준). 두 진입점(명시적 resolve(),
 * submit()의 자동 정정 경로) 모두 같은 resolveInternal을 타므로 둘 다 확인한다.
 */
class CountVarianceRollbackTest extends AbstractIntegrationTest {

    @Autowired
    private CountSessionGateway countSessionGateway;

    @Autowired
    private AllocationGateway allocationGateway;

    @Test
    void explicitResolveFailsAndKeepsLocationLockedWhenAdjustmentWouldUnderflowAllocatedQty() {
        putawayMice("A-02-01-1", 20);
        allocate("A-02-01-1", 15); // 가용 5개만 남는다 (on_hand 20, allocated 15)

        long sessionId = countSessionGateway.start(
                new StartCountRequest("ICN01", "A-02-01-1", "user:lee.sh"));
        // 차이 -18: 오차를 넘어 REVIEW로 남는다 — 정정하면 on_hand가 2가 되어 allocated_qty(15)보다 작아진다
        CountSubmitOutcome outcome = countSessionGateway.submit(new SubmitCountRequest("count:CC-RB-0001:submit",
                sessionId, List.of(new CountLineInput("SKU-300001", "DEFAULT", 2)), "user:lee.sh"));
        assertThat(outcome).isInstanceOf(ReviewRequired.class);

        assertThatThrownBy(() -> countSessionGateway.resolve(
                new ResolveCountRequest("count:CC-RB-0001:resolve", sessionId, "user:choi.dw")))
                .isInstanceOf(PostingException.class)
                .extracting(ex -> ((PostingException) ex).code())
                .isEqualTo("INSUFFICIENT_STOCK");

        // 표시를 지운 UPDATE까지 같은 트랜잭션이므로 실패하면 함께 롤백되어 로케이션은 계속 잠겨 있어야 한다
        assertThat(countSessionIdOf("ICN01", "A-02-01-1")).as("로케이션이 계속 잠겨 있다").isEqualTo(sessionId);
        assertThat(countSessionStatus(sessionId)).as("세션은 REVIEW로 남는다").isEqualTo("REVIEW");
        assertThat(onHandQty("ICN01", "A-02-01-1", "SKU-300001", "DEFAULT")).as("실재고 불변").isEqualTo(20);
        assertReconciliationClean();

        // 문서가 설명하는 복구 절차: 할당 해제 후 다시 정정하면 통과한다
        List<AllocationId> allocationIds = activeAllocationIds("A-02-01-1", "SKU-300001");
        allocationGateway.release("release:CC-RB-0001", allocationIds);

        Long txnId = countSessionGateway.resolve(
                new ResolveCountRequest("count:CC-RB-0001:resolve-retry", sessionId, "user:choi.dw"));

        assertThat(txnId).isNotNull();
        assertThat(onHandQty("ICN01", "A-02-01-1", "SKU-300001", "DEFAULT")).isEqualTo(2);
        assertThat(countSessionIdOf("ICN01", "A-02-01-1")).isNull();
        assertThat(countSessionStatus(sessionId)).isEqualTo("CONFIRMED");
        assertReconciliationClean();
    }

    @Test
    void submitAutoResolveFailsAndKeepsSessionOpenWhenAdjustmentWouldUnderflowAllocatedQty() {
        putawayMice("A-02-01-1", 100);
        allocate("A-02-01-1", 100); // 전량 할당 (가용 0)

        long sessionId = countSessionGateway.start(
                new StartCountRequest("ICN01", "A-02-01-1", "user:lee.sh"));

        // 차이 -1: 오차(1개 이하이면서 5% 이하) 이내라 제출 안에서 바로 정정을 시도하지만, 가용이 0이라 실패한다
        assertThatThrownBy(() -> countSessionGateway.submit(new SubmitCountRequest("count:CC-RB-0002:submit",
                sessionId, List.of(new CountLineInput("SKU-300001", "DEFAULT", 99)), "user:lee.sh")))
                .isInstanceOf(PostingException.class)
                .extracting(ex -> ((PostingException) ex).code())
                .isEqualTo("INSUFFICIENT_STOCK");

        // 제출 트랜잭션 전체가 롤백된다 — count_result도, submitted_at도, 로케이션 표시 해제도 없었던 일이 된다
        assertThat(countSessionIdOf("ICN01", "A-02-01-1")).as("로케이션이 계속 잠겨 있다").isEqualTo(sessionId);
        assertThat(countSessionStatus(sessionId)).as("세션은 OPEN으로 남는다 (제출 자체가 롤백)").isEqualTo("OPEN");
        assertThat(onHandQty("ICN01", "A-02-01-1", "SKU-300001", "DEFAULT")).as("실재고 불변").isEqualTo(100);
        assertReconciliationClean();
    }

    private void putawayMice(String locationCode, int qty) {
        postAndExpectSuccess(request("receipt:PO-CNT-RB-" + locationCode + ":1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-300001", "DEFAULT", -qty),
                line("ICN01", locationCode, "SKU-300001", "DEFAULT", qty)));
    }

    private void allocate(String locationCode, int qty) {
        AllocationResult result = allocationGateway.allocate(new AllocateRequest(
                "ORD-CC-RB-" + locationCode, "ICN01", "SKU-300001", qty, false));
        assertThat(result.allocationIds()).isNotEmpty();
    }

    private List<AllocationId> activeAllocationIds(String locationCode, String skuCode) {
        return jdbcClient.sql("""
                SELECT a.id FROM allocation a
                JOIN stock_balance b ON b.id = a.balance_id
                JOIN location l ON l.id = b.location_id JOIN warehouse w ON w.id = l.warehouse_id
                JOIN sku s ON s.id = b.sku_id
                WHERE w.code = 'ICN01' AND l.code = :loc AND s.code = :sku AND a.status = 'ACTIVE'
                """)
                .param("loc", locationCode)
                .param("sku", skuCode)
                .query((rs, rowNum) -> new AllocationId(rs.getLong("id")))
                .list();
    }
}
