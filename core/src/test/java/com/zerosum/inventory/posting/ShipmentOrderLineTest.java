package com.zerosum.inventory.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zerosum.inventory.allocation.AllocateRequest;
import com.zerosum.inventory.allocation.AllocationGateway;
import com.zerosum.inventory.allocation.AllocationResult;
import com.zerosum.inventory.domain.AllocationId;
import com.zerosum.inventory.domain.PostingException;
import com.zerosum.inventory.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 출고는 자기가 내건 주문 줄의 예약만 소진할 수 있다.
 *
 * <p>출고의 멱등 키는 {@code ship:{주문라인}:{출고차수}}다(docs/04-write-path.md). 즉 한 출고는 주문 줄
 * 하나에 속한다는 것이 설계의 전제인데, 그 주문 줄과 실제로 소진하는 예약이 어디에서도 대조되지 않았다.
 * 출고 화면의 주문번호는 자유 입력이라 사람이 SO-1001의 예약을 고른 채 SO-9999를 적으면 그대로 통과했다.
 *
 * <p>어긋나면 둘이 망가진다.
 * <ul>
 *   <li>원장에는 SO-9999로 나갔다고 남는데 {@code allocation}은 SO-1001의 것이다 — 감사 기록이 갈라진다.
 *   <li>{@code shipment:SO-9999:1}을 선점해 버려, 나중에 SO-9999를 진짜 출고하면 새 거래가 생기지 않고
 *       이 거래가 조용히 재생된다.
 * </ul>
 *
 * <p>두 번째가 특히 나쁘다 — 업무 식별자로 만든 키가 그 업무를 가리키지 않게 되는 것이라, 실사 시작이
 * (창고, 로케이션)을 키로 삼아 재실사를 막던 결함과 같은 부류다. 재고 수치가 아니라 <b>추적 가능성</b>이
 * 깨지는 종류라 DB CHECK로는 잡히지 않고, 여기서 막는다.
 */
class ShipmentOrderLineTest extends AbstractIntegrationTest {

    @Autowired
    private AllocationGateway allocationGateway;

    private void receive(String lot, String location, int qty) {
        postAndExpectSuccess(request("receipt:PO-OL-" + lot + "-" + location, "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-200002", lot, -qty),
                line("ICN01", location, "SKU-200002", lot, qty)));
    }

    private List<Long> allocate(String orderLineRef, int qty) {
        AllocationResult result = allocationGateway.allocate(
                new AllocateRequest("alloc:" + orderLineRef, orderLineRef, "ICN01", "SKU-200002", qty, false));
        return result.allocationIds().stream().map(AllocationId::value).toList();
    }

    private PostingRequest shipment(String idemKey, String sourceRef, List<Long> allocIds,
            PostingLineInput... lines) {
        return new PostingRequest(idemKey, "SHIPMENT", "USER", "user:test", List.of(lines),
                "ORDER", sourceRef, null, null, Instant.now(), allocIds);
    }

    @Test
    void shipmentLabelledWithAnotherOrderLineIsRejected() {
        receive("L20260910-B", "A-01-01-2", 40);
        List<Long> allocIds = allocate("SO-1001", 10);

        assertThatThrownBy(() -> postingGateway.post(
                shipment("ship:SO-9999:1", "SO-9999", allocIds,
                        line("ICN01", "A-01-01-2", "SKU-200002", "L20260910-B", -10),
                        line("ICN01", "V-CUSTOMER", "SKU-200002", "L20260910-B", 10)),
                Preconditions.none()))
                .as("SO-1001의 예약을 SO-9999의 출고로 소진할 수 없다")
                .isInstanceOf(PostingException.class)
                .hasMessageContaining("SO-1001");

        assertThat(onHandQty("ICN01", "A-01-01-2", "SKU-200002", "L20260910-B"))
                .as("거절됐으니 재고도 그대로다").isEqualTo(40);
        assertReconciliationClean();
    }

    @Test
    void shipmentMixingTwoOrderLinesIsRejected() {
        receive("L20260910-B", "A-01-01-2", 40);
        List<Long> first = allocate("SO-2001", 10);
        List<Long> second = allocate("SO-2002", 10);

        assertThatThrownBy(() -> postingGateway.post(
                shipment("ship:SO-2001:1", "SO-2001", Stream.concat(first.stream(), second.stream()).toList(),
                        line("ICN01", "A-01-01-2", "SKU-200002", "L20260910-B", -20),
                        line("ICN01", "V-CUSTOMER", "SKU-200002", "L20260910-B", 20)),
                Preconditions.none()))
                .as("한 출고가 두 주문의 예약을 섞어 소진할 수 없다")
                .isInstanceOf(PostingException.class)
                .hasMessageContaining("SO-2002");

        assertThat(onHandQty("ICN01", "A-01-01-2", "SKU-200002", "L20260910-B")).isEqualTo(40);
        assertReconciliationClean();
    }

    /** 대조군 — 주문 줄이 맞으면 그대로 나간다. 위 둘이 "출고 자체가 막힌다"가 아님을 보인다. */
    @Test
    void shipmentWithMatchingOrderLineSucceeds() {
        receive("L20260910-B", "A-01-01-2", 40);
        List<Long> allocIds = allocate("SO-3001", 10);

        postAndExpectSuccess(shipment("ship:SO-3001:1", "SO-3001", allocIds,
                line("ICN01", "A-01-01-2", "SKU-200002", "L20260910-B", -10),
                line("ICN01", "V-CUSTOMER", "SKU-200002", "L20260910-B", 10)));

        assertThat(onHandQty("ICN01", "A-01-01-2", "SKU-200002", "L20260910-B")).isEqualTo(30);
        assertReconciliationClean();
    }
}
