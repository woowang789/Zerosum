package com.zerosum.inventory.posting;

import static org.assertj.core.api.Assertions.assertThat;

import com.zerosum.inventory.allocation.AllocateRequest;
import com.zerosum.inventory.allocation.AllocationGateway;
import com.zerosum.inventory.allocation.AllocationResult;
import com.zerosum.inventory.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 검증용: 같은 잔액 키가 한 출고 거래에 두 줄 있을 때 할당 소진이 중복 적용되지 않는지. */
class DuplicateKeyShipmentTest extends AbstractIntegrationTest {

    @Autowired
    AllocationGateway allocationGateway;

    @Test
    @DisplayName("같은 잔액 키를 두 줄로 나눠 출고해도 할당 소진은 한 번만 적용된다")
    void 같은_잔액_키를_두_줄로_나눈_출고() {
        postAndExpectSuccess(request("receipt:PO-DUPSHIP:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -20),
                line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", 20)));

        AllocationResult allocated = allocationGateway.allocate(
                new AllocateRequest("ORD-DUPSHIP-1", "ICN01", "SKU-100001", 8, false));
        List<Long> allocIds = allocated.allocationIds().stream().map(id -> id.value()).toList();

        // 같은 빈에서 두 번에 나눠 집은 것을 두 줄로 기록한 출고. (SKU, 로트) 합계는 0으로 유효하다.
        postingGateway.post(new PostingRequest("ship:ORD-DUPSHIP-1:1", "SHIPMENT", "USER", "user:test",
                List.of(line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", -5),
                        line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", -3),
                        line("ICN01", "V-CUSTOMER", "SKU-100001", "DEFAULT", 8)),
                // sourceRef는 할당을 만든 주문 줄과 같아야 한다 — 전에는 "ORD-DUPSHIP"으로 한 글자 어긋나
                // 있었고 아무도 보지 않았다. 대조하는 것이 없었기 때문이다(ALLOC_ORDER_MISMATCH).
                "ORDER", "ORD-DUPSHIP-1", null, null, Instant.now(), allocIds), Preconditions.none());

        int allocatedQty = jdbcClient.sql("""
                SELECT b.allocated_qty FROM stock_balance b
                JOIN location l ON l.id = b.location_id
                JOIN sku s ON s.id = b.sku_id
                WHERE l.code = 'A-01-01-1' AND s.code = 'SKU-100001'
                """).query(Integer.class).single();

        assertThat(onHandQty("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT"))
                .as("실재고는 20 − 8 = 12").isEqualTo(12);
        assertThat(allocatedQty).as("할당량은 8이 한 번만 소진되어 0").isEqualTo(0);
    }
}
