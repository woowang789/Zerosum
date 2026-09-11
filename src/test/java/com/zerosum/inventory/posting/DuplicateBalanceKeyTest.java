package com.zerosum.inventory.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zerosum.inventory.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 검증용: 같은 잔액 키가 한 거래에 두 줄 이상 있을 때 잔액과 원장이 어긋나지 않는지. */
class DuplicateBalanceKeyTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("같은 로케이션·SKU·로트로 두 줄을 올린 입고에서도 잔액 = 원장 합계 (I4)")
    void 같은_잔액_키가_두_줄인_거래() {
        // 파렛트 두 개를 한 입고 거래로 같은 로케이션에 넣는다. (SKU, 로트) 합계는 0으로 유효하다.
        postAndExpectSuccess(request("receipt:DUP-0001", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -15),
                line("ICN01", "RCV-01", "SKU-100001", "DEFAULT", 10),
                line("ICN01", "RCV-01", "SKU-100001", "DEFAULT", 5)));

        int ledgerSum = jdbcClient.sql("""
                SELECT COALESCE(sum(e.qty_delta), 0) FROM inventory_ledger_entry e
                JOIN location l ON l.id = e.location_id
                WHERE l.code = 'RCV-01' AND NOT l.is_virtual
                """).query(Integer.class).single();

        assertThat(onHandQty("ICN01", "RCV-01", "SKU-100001", "DEFAULT"))
                .as("잔액은 10 + 5 = 15여야 한다")
                .isEqualTo(15);
        assertThat(ledgerSum).as("원장 합계도 15").isEqualTo(15);
    }

    @Test
    @DisplayName("같은 키에 증가와 차감이 섞여도 순서대로 누적 적용되고 on_hand_after가 각 줄 직후 값이다")
    void 같은_잔액_키에_증가와_차감이_섞인_거래() {
        // V-SUPPLIER(-5) -> RCV-01(+20) -> RCV-01(-15) : (SKU, 로트) 합계는 -5+20-15=0으로 유효하다.
        // 두 번째 RCV-01 줄은 첫 번째 줄이 만든 잔액(20)을 기준으로 가용을 평가해야 한다.
        long txnId = postAndExpectSuccess(request("receipt:DUP-0002", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -5),
                line("ICN01", "RCV-01", "SKU-100001", "DEFAULT", 20),
                line("ICN01", "RCV-01", "SKU-100001", "DEFAULT", -15)));

        assertThat(onHandQty("ICN01", "RCV-01", "SKU-100001", "DEFAULT"))
                .as("20 - 15 = 5")
                .isEqualTo(5);

        // 물리 로케이션(RCV-01) 원장 두 줄의 on_hand_after가 각각 그 줄 적용 직후 값(20, 5)이어야
        // 정합 검증 ③(원장 체인)이 끊긴 지점을 정확히 짚을 수 있다.
        var onHandAfterSequence = jdbcClient.sql("""
                SELECT on_hand_after FROM inventory_ledger_entry e
                JOIN location l ON l.id = e.location_id
                WHERE e.txn_id = :txnId AND l.code = 'RCV-01'
                ORDER BY e.id
                """)
                .param("txnId", txnId)
                .query(Integer.class)
                .list();
        assertThat(onHandAfterSequence).containsExactly(20, 5);
    }

    @Test
    @DisplayName("같은 키에 대한 차감이 누적으로 가용을 넘으면 거절되고 전체가 롤백된다")
    void 같은_잔액_키에_대한_누적_차감이_가용을_넘으면_거절() {
        postAndExpectSuccess(request("receipt:DUP-0003:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -10),
                line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", 10)));

        // 가용 10에서 -6, -6 두 줄 (합계 -12)은 두 번째 줄에서 누적 기준으로 거절되어야 한다.
        assertThatThrownBy(() -> postingGateway.post(
                request("adjust:DUP-0003:2", "ADJUSTMENT", "LOST", null,
                        line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", -6),
                        line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", -6),
                        line("ICN01", "V-ADJUST", "SKU-100001", "DEFAULT", 12)),
                Preconditions.none()))
                .isInstanceOf(PostingException.class)
                .extracting(ex -> ((PostingException) ex).code())
                .isEqualTo("INSUFFICIENT_STOCK");

        // 거절됐으니 원래 값(10)이 그대로 남아 있어야 한다 (부분 적용 없이 전체 롤백)
        assertThat(onHandQty("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT")).isEqualTo(10);
    }
}
