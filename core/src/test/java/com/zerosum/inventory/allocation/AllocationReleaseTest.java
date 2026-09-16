package com.zerosum.inventory.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zerosum.inventory.domain.AllocationException;
import com.zerosum.inventory.domain.IdempotencyConflictException;
import com.zerosum.inventory.domain.PostingException;
import com.zerosum.inventory.posting.Preconditions;
import com.zerosum.inventory.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 할당 해제. db/usecases/A-inbound-outbound.sql UC-A06과 db/usecases/D-invariants.sql UC-D03을 재현한다.
 */
class AllocationReleaseTest extends AbstractIntegrationTest {

    @Autowired
    private AllocationGateway allocationGateway;

    @Test
    void releaseRestoresAvailabilityAndMarksReleased() {
        postAndExpectSuccess(request("receipt:PO-REL-0001:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -120),
                line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", 120)));

        AllocationResult result = allocationGateway.allocate(
                new AllocateRequest("ORD-REL-0001-1", "ICN01", "SKU-100001", 30, false));
        assertThat(availableQty("ICN01", "A-01-01-1", "SKU-100001")).isEqualTo(90);

        int released = allocationGateway.release("release:ORD-REL-0001-1", result.allocationIds());

        assertThat(released).isEqualTo(1);
        assertThat(availableQty("ICN01", "A-01-01-1", "SKU-100001")).isEqualTo(120);
        assertThat(allocationStatus(result.allocationIds().get(0).value())).isEqualTo("RELEASED");
    }

    @Test
    void reReleasingAlreadyClosedAllocationFails() {
        postAndExpectSuccess(request("receipt:PO-REL-0002:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -50),
                line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", 50)));

        AllocationResult result = allocationGateway.allocate(
                new AllocateRequest("ORD-REL-0002-1", "ICN01", "SKU-100001", 20, false));
        allocationGateway.release("release:ORD-REL-0002-1", result.allocationIds());

        // 같은 할당 id를 다른 멱등 키로 다시 해제하면 이번엔 실제로 시도하다가 ALLOC_NOT_ACTIVE로 실패한다.
        assertThatThrownBy(() -> allocationGateway.release("release:ORD-REL-0002-1-again", result.allocationIds()))
                .isInstanceOf(AllocationException.class)
                .extracting(ex -> ((AllocationException) ex).code())
                .isEqualTo("ALLOC_NOT_ACTIVE");
    }

    @Test
    void releaseWithSameIdemKeyButDifferentAllocationIdsIsRejectedAsConflict() {
        postAndExpectSuccess(request("receipt:PO-REL-0003:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -50),
                line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", 50)));

        AllocationResult first = allocationGateway.allocate(
                new AllocateRequest("ORD-REL-0003-1", "ICN01", "SKU-100001", 10, false));
        AllocationResult second = allocationGateway.allocate(
                new AllocateRequest("ORD-REL-0003-2", "ICN01", "SKU-100001", 10, false));

        allocationGateway.release("release:ORD-REL-0003", first.allocationIds());

        // 같은 멱등 키로 전혀 다른 할당 id 목록을 보내면 조용히 0을 받는 대신 409로 거절해야 한다.
        assertThatThrownBy(() -> allocationGateway.release("release:ORD-REL-0003", second.allocationIds()))
                .isInstanceOf(IdempotencyConflictException.class)
                .extracting(ex -> ((PostingException) ex).code())
                .isEqualTo("IDEM_CONFLICT_409");

        // 첫 해제만 반영되고 두 번째 할당은 그대로 ACTIVE로 남아 있어야 한다.
        assertThat(allocationStatus(second.allocationIds().get(0).value())).isEqualTo("ACTIVE");
    }

    @Test
    void allocatedStockBlocksAdjustmentUntilReleased() {
        // 마우스는 A-02-01-1 한 곳에만 있어 할당이 어느 잔액 행에 붙을지 분명하다 (UC-D03 설정과 같다)
        postAndExpectSuccess(request("receipt:PO-D03-0001:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-300001", "DEFAULT", -15),
                line("ICN01", "A-02-01-1", "SKU-300001", "DEFAULT", 15)));

        AllocationResult result = allocationGateway.allocate(
                new AllocateRequest("ORD-D03-0001-1", "ICN01", "SKU-300001", 12, false));
        assertThat(availableQty("ICN01", "A-02-01-1", "SKU-300001")).isEqualTo(3);

        // 할당분까지 조정으로 차감 시도 → 거절 (UC-D03b)
        assertThatThrownBy(() -> postingGateway.post(
                request("adjust:INC-D03-0001", "ADJUSTMENT", "LOST", null,
                        line("ICN01", "A-02-01-1", "SKU-300001", "DEFAULT", -15),
                        line("ICN01", "V-ADJUST", "SKU-300001", "DEFAULT", 15)),
                Preconditions.none()))
                .isInstanceOf(PostingException.class)
                .extracting(ex -> ((PostingException) ex).code())
                .isEqualTo("INSUFFICIENT_STOCK");

        // 할당 해제 후 다시 시도하면 통과한다 (UC-D03c)
        allocationGateway.release("release:ORD-D03-0001-1", result.allocationIds());
        postAndExpectSuccess(request("adjust:INC-D03-0001", "ADJUSTMENT", "LOST", null,
                line("ICN01", "A-02-01-1", "SKU-300001", "DEFAULT", -15),
                line("ICN01", "V-ADJUST", "SKU-300001", "DEFAULT", 15)));
        assertThat(onHandQty("ICN01", "A-02-01-1", "SKU-300001", "DEFAULT")).isZero();
    }

    private int availableQty(String warehouseCode, String locationCode, String skuCode) {
        return jdbcClient.sql("""
                SELECT b.on_hand_qty - b.allocated_qty FROM stock_balance b
                JOIN location l ON l.id = b.location_id JOIN warehouse w ON w.id = l.warehouse_id
                JOIN sku s ON s.id = b.sku_id
                WHERE w.code = :wh AND l.code = :loc AND s.code = :sku
                """)
                .param("wh", warehouseCode)
                .param("loc", locationCode)
                .param("sku", skuCode)
                .query(Integer.class)
                .single();
    }

    private String allocationStatus(long allocationId) {
        return jdbcClient.sql("SELECT status FROM allocation WHERE id = :id")
                .param("id", allocationId)
                .query(String.class)
                .single();
    }
}
