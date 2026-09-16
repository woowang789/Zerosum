package com.zerosum.inventory.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zerosum.inventory.domain.PostingException;
import com.zerosum.inventory.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * A-1: 줄이 하나도 없는 커맨드. 영합 검사가 {@code allMatch}라 공집합에 대해 참이 되어 통과했고,
 * I3의 지연 제약 트리거는 {@code AFTER INSERT ... FOR EACH ROW}라 삽입된 줄이 없으면 발화조차 하지 않았다.
 *
 * <p>피해는 둘이다 — 원장 줄 없는 유령 거래가 남고(정합 검증 ①~⑤가 원장·잔액 기준이라 존재를 모른다),
 * 업무 식별자로 만든 멱등 키를 영구히 태운다. 아래 두 테스트가 각각을 지킨다.
 */
class EmptyEntriesTest extends AbstractIntegrationTest {

    @Test
    void commandWithNoEntriesIsRejected() {
        assertThatThrownBy(() -> postingGateway.post(
                request("shipment:ORD-EMPTY-0001:1", "SHIPMENT", null, null),
                Preconditions.none()))
                .isInstanceOf(PostingException.class)
                .extracting(ex -> ((PostingException) ex).code())
                .isEqualTo("ENTRIES_REQUIRED");

        assertThat(txnCount()).as("유령 거래가 남지 않는다").isZero();
        assertThat(ledgerCount()).isZero();
    }

    @Test
    void rejectedEmptyCommandDoesNotBurnTheIdempotencyKey() {
        // 빈 커맨드가 shipment:{주문줄}:{차수} 키를 선점해 버리면, 나중의 진짜 출고는 본문 해시가 달라
        // 409로 영영 거절된다 — 업무 식별자로 만든 키라 다시 만들 수도 없다.
        assertThatThrownBy(() -> postingGateway.post(
                request("shipment:ORD-EMPTY-0002:1", "SHIPMENT", null, null),
                Preconditions.none()))
                .isInstanceOf(PostingException.class);

        postAndExpectSuccess(request("receipt:PO-EMPTY-0002:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -10),
                line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", 10)));

        long txnId = postAndExpectSuccess(request("shipment:ORD-EMPTY-0002:1", "SHIPMENT", null, null,
                line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", -10),
                line("ICN01", "V-CUSTOMER", "SKU-100001", "DEFAULT", 10)));

        assertThat(txnType(txnId)).isEqualTo("SHIPMENT");
        assertThat(onHandQty("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT")).isZero();
    }

    private int txnCount() {
        return jdbcClient.sql("SELECT count(*) FROM inventory_txn").query(Integer.class).single();
    }

    private int ledgerCount() {
        return jdbcClient.sql("SELECT count(*) FROM inventory_ledger_entry").query(Integer.class).single();
    }
}
