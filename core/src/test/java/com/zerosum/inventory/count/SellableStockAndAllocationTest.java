package com.zerosum.inventory.count;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zerosum.inventory.allocation.AllocateRequest;
import com.zerosum.inventory.allocation.AllocationGateway;
import com.zerosum.inventory.allocation.AllocationResult;
import com.zerosum.inventory.allocation.InsufficientStockException;
import com.zerosum.inventory.domain.SellableStock;
import com.zerosum.inventory.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 판매 가능 수량 분리와 allowInCount 연동. db/usecases/C-count-session.sql UC-C10~C12를 재현한다.
 * allowInCount를 할당 후보 쿼리에 연결하는 배선은 2a에서 이미 끝났으므로(AllocationRepository
 * #findCandidateBalanceIds), 여기서는 실사 표시만 만들고 그 배선이 그대로 동작하는지 확인한다.
 */
class SellableStockAndAllocationTest extends AbstractIntegrationTest {

    @Autowired
    private CountSessionGateway countSessionGateway;

    @Autowired
    private AllocationGateway allocationGateway;

    @Autowired
    private SellableStockService sellableStockService;

    @Test
    void inCountLocationIsSplitOutOfSellableQtyAndAffectsAllocationCandidates() {
        putawayTshirts("B-01-01-1", 24); // 실사 밖 로케이션
        putawayTshirts("A-01-01-1", 52); // 실사 대상 로케이션

        long sessionId = countSessionGateway.start(
                new StartCountRequest("ICN01", "A-01-01-1", "user:lee.sh"));

        SellableStock stock = sellableStockService.find("ICN01", "SKU-100001");
        assertThat(stock.sellableQty()).as("실사 중이 아닌 B-01-01-1의 24개만 판매 가능").isEqualTo(24);
        assertThat(stock.inCountQty()).as("실사 중인 A-01-01-1의 52개는 분리 표기").isEqualTo(52);

        assertThatThrownBy(() -> allocationGateway.allocate(
                new AllocateRequest("alloc:CC-SELL-0001", "ORD-CC-SELL-0001", "ICN01", "SKU-100001", 30, false)))
                .as("allowInCount=false면 실사 중 로케이션은 후보에서 빠져 24개뿐이라 부족하다")
                .isInstanceOf(InsufficientStockException.class);

        AllocationResult result = allocationGateway.allocate(
                new AllocateRequest("alloc:CC-SELL-0002", "ORD-CC-SELL-0002", "ICN01", "SKU-100001", 30, true));
        assertThat(result.allocationIds()).as("allowInCount=true면 실사 중 로케이션도 후보에 포함된다").isNotEmpty();

        // 정리: 할당 해제 후 실사 중단 (해제 없이 중단하면 I5가 깨진 채 남는 게 아니라, 실사 중단 자체는
        // 할당과 무관하게 표시만 지우므로 문제없이 끝나지만 정합 검증까지 깔끔하게 마무리하기 위해 순서대로 정리한다)
        allocationGateway.release("release:CC-SELL-0002", result.allocationIds());
        countSessionGateway.abandon(new AbandonCountRequest("count:CC-SELL-0001:abandon", sessionId, "user:lee.sh"));

        assertReconciliationClean();
    }

    @Test
    void warehouseSkuWithNoStorageBalanceIsZeroNotError() {
        SellableStock stock = sellableStockService.find("ICN01", "SKU-200001");

        assertThat(stock.sellableQty()).isZero();
        assertThat(stock.inCountQty()).isZero();

        assertReconciliationClean();
    }

    private void putawayTshirts(String locationCode, int qty) {
        postAndExpectSuccess(request("receipt:PO-CNT-SELL-" + locationCode + ":1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -qty),
                line("ICN01", locationCode, "SKU-100001", "DEFAULT", qty)));
    }
}
