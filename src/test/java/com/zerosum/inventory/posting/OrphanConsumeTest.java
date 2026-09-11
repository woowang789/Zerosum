package com.zerosum.inventory.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zerosum.inventory.allocation.AllocateRequest;
import com.zerosum.inventory.allocation.AllocationGateway;
import com.zerosum.inventory.allocation.AllocationResult;
import com.zerosum.inventory.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 검증용: 소진 대상 할당이 출고 줄의 잔액과 무관할 때 I5(할당량 = ACTIVE 할당 합계)가 지켜지는지. */
class OrphanConsumeTest extends AbstractIntegrationTest {

    @Autowired
    AllocationGateway allocationGateway;

    @Test
    @DisplayName("출고 줄에 없는 잔액의 할당을 소진 대상으로 넘기면 거절되고, I5도 깨지지 않아야 한다")
    void 출고_줄과_무관한_할당을_소진_대상으로_넘김() {
        postAndExpectSuccess(request("receipt:PO-ORPHAN-A:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -20),
                line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", 20)));
        postAndExpectSuccess(request("receipt:PO-ORPHAN-B:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -20),
                line("ICN01", "B-01-01-1", "SKU-100001", "DEFAULT", 20)));

        // FEFO는 만료가 같으면 잔액 행 id 순 → A-01-01-1에 할당이 붙는다
        AllocationResult allocated = allocationGateway.allocate(
                new AllocateRequest("alloc:ORD-ORPHAN-1", "ORD-ORPHAN-1", "ICN01", "SKU-100001", 8, false));
        List<Long> allocIds = allocated.allocationIds().stream().map(id -> id.value()).toList();

        // B-01-01-1에서 출고하면서 A-01-01-1에 붙은 할당을 소진 대상으로 넘긴다 (호출자 버그) → 거절돼야 한다
        assertThatThrownBy(() -> postingGateway.post(new PostingRequest("ship:ORD-ORPHAN-1:1", "SHIPMENT", "USER",
                "user:test",
                List.of(line("ICN01", "B-01-01-1", "SKU-100001", "DEFAULT", -8),
                        line("ICN01", "V-CUSTOMER", "SKU-100001", "DEFAULT", 8)),
                "ORDER", "ORD-ORPHAN", null, null, Instant.now(), allocIds), Preconditions.none()))
                .isInstanceOf(PostingException.class)
                .extracting(ex -> ((PostingException) ex).code())
                .isEqualTo("ORPHAN_CONSUME");

        // 거절돼 전체 롤백됐으니 할당은 그대로 ACTIVE, B-01-01-1도 그대로 20개여야 한다
        assertThat(onHandQty("ICN01", "B-01-01-1", "SKU-100001", "DEFAULT")).isEqualTo(20);

        // 정합 검증 ② (docs/06-events-reconciliation.md): 할당량 = ACTIVE 할당 합계
        int mismatches = jdbcClient.sql("""
                SELECT count(*) FROM (
                  SELECT b.id FROM stock_balance b
                  LEFT JOIN allocation a ON a.balance_id = b.id AND a.status = 'ACTIVE'
                  GROUP BY b.id, b.allocated_qty
                  HAVING b.allocated_qty <> COALESCE(SUM(a.qty), 0)) x
                """).query(Integer.class).single();

        assertThat(mismatches).as("② 할당 검증 불일치 건수").isEqualTo(0);
    }
}
