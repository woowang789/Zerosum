package com.zerosum.inventory.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zerosum.inventory.domain.AllocationException;
import com.zerosum.inventory.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 할당의 FEFO 정렬과 가용 부족 거절. db/usecases/A-inbound-outbound.sql의 UC-A03~A04를 재현한다.
 */
class AllocationFefoTest extends AbstractIntegrationTest {

    @Autowired
    private AllocationGateway allocationGateway;

    @Test
    void allocatesEarlierExpiryLotFirstRegardlessOfBalanceRowId() {
        // 만료가 늦은 로트(L20260910-B)를 먼저 입고해, 잔액 행 id 순서가 유통기한 순서와 반대가 되게 한다.
        postAndExpectSuccess(request("receipt:PO-FEFO-0001:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-200002", "L20260910-B", -80),
                line("ICN01", "A-01-01-2", "SKU-200002", "L20260910-B", 80)));
        postAndExpectSuccess(request("receipt:PO-FEFO-0002:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-200002", "L20260901-A", -50),
                line("ICN01", "A-01-02-1", "SKU-200002", "L20260901-A", 50)));

        AllocationResult result = allocationGateway.allocate(
                new AllocateRequest("ORD-FEFO-0001-1", "ICN01", "SKU-200002", 60, false));

        assertThat(result.allocationIds()).hasSize(2);
        assertThat(allocatedQty("ICN01", "A-01-02-1", "SKU-200002", "L20260901-A"))
                .as("만료 임박 로트(L20260901-A, 잔액 행 id는 더 큼)가 50개 전량 먼저 할당된다")
                .isEqualTo(50);
        assertThat(allocatedQty("ICN01", "A-01-01-2", "SKU-200002", "L20260910-B"))
                .as("나머지 10개는 만료가 늦은 로트에서 (잔액 행 id는 더 작지만 뒤로 밀린다)")
                .isEqualTo(10);
    }

    @Test
    void insufficientStockAllocationIsRejectedWithoutPartialEffect() {
        postAndExpectSuccess(request("receipt:PO-FEFO-0003:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -10),
                line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", 10)));

        assertThatThrownBy(() -> allocationGateway.allocate(
                new AllocateRequest("ORD-FEFO-0002-1", "ICN01", "SKU-100001", 11, false)))
                .isInstanceOf(InsufficientStockException.class)
                .extracting(ex -> ((AllocationException) ex).code())
                .isEqualTo("INSUFFICIENT_STOCK");

        assertThat(allocatedQty("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT"))
                .as("부분 할당 없이 전체 롤백되어야 한다").isZero();
    }

    private int allocatedQty(String warehouseCode, String locationCode, String skuCode, String lotNo) {
        return jdbcClient.sql("""
                SELECT b.allocated_qty FROM stock_balance b
                JOIN location l ON l.id = b.location_id JOIN warehouse w ON w.id = l.warehouse_id
                JOIN sku s ON s.id = b.sku_id JOIN lot lo ON lo.id = b.lot_id
                WHERE w.code = :wh AND l.code = :loc AND s.code = :sku AND lo.lot_no = :lot
                """)
                .param("wh", warehouseCode)
                .param("loc", locationCode)
                .param("sku", skuCode)
                .param("lot", lotNo)
                .query(Integer.class)
                .single();
    }
}
