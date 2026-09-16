package com.zerosum.inventory.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zerosum.inventory.domain.PostingException;
import com.zerosum.inventory.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * A-2: 거래 유형별 가상 로케이션 규칙 (docs/03-transactions.md의 표, docs/04-write-path.md ② 커맨드 검증).
 *
 * <p>규칙이 없을 때 뚫린 곳은 부호였다. 클라이언트가 보낸 qty가 음수면 컨트롤러의 {@code -qty}/{@code +qty}가
 * 통째로 뒤집혀 <b>txn_type=SHIPMENT·reason_code=NULL인 거래가 실재고를 늘린다.</b> 원장과 잔액은 완벽히
 * 일치하므로 정합 검증 ①~⑤가 전부 0건이고, 배치는 영원히 이것을 보지 못한다 — 조정만 SUPERVISOR 전용으로
 * 묶어 둔 통제가 OPERATOR에게 그대로 열리는 셈이다.
 *
 * <p>REVERSAL은 정의상 원거래의 반대 방향이라 이 규칙에 걸린다. 면제가 제대로 걸려 있는지는
 * {@link #reversalIsExemptFromTheRule}·{@link #reversalOfShipmentIsExemptToo}가 대조군으로 지킨다.
 */
class VirtualLocationRuleTest extends AbstractIntegrationTest {

    // ── RECEIPT: V-SUPPLIER가 음수, 물리 줄이 양수 ──────────────────────────────────

    @Test
    void negativeReceiptCannotDrainStockThroughSupplierLine() {
        putaway100();

        // 컨트롤러가 qty=-50으로 만든 줄과 같은 모양이다 (물리 -50 / V-SUPPLIER +50).
        assertThatThrownBy(() -> postingGateway.post(
                request("receipt:PO-NEG-0001:1", "RECEIPT", null, null,
                        line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", -50),
                        line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", 50)),
                Preconditions.none()))
                .isInstanceOf(PostingException.class)
                .extracting(ex -> ((PostingException) ex).code())
                .isEqualTo("LINE_DIRECTION_MISMATCH");

        assertThat(onHandQty("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT")).isEqualTo(100);
    }

    @Test
    void receiptCannotUseAnotherVirtualLocation() {
        assertThatThrownBy(() -> postingGateway.post(
                request("receipt:PO-NEG-0002:1", "RECEIPT", null, null,
                        line("ICN01", "V-ADJUST", "SKU-100001", "DEFAULT", -10),
                        line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", 10)),
                Preconditions.none()))
                .isInstanceOf(PostingException.class)
                .extracting(ex -> ((PostingException) ex).code())
                .isEqualTo("VIRTUAL_LOCATION_NOT_ALLOWED");
    }

    // ── SHIPMENT: V-CUSTOMER가 양수, 물리 줄이 음수 ─────────────────────────────────

    @Test
    void negativeShipmentCannotCreateStockOutOfNothing() {
        assertThat(onHandQty("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT")).isZero();

        // 실측 재현(PROBE1): 출고인데 물리 +50 / V-CUSTOMER −50 → Posted, 실재고 0 → 50, 정합 검증 전부 0건.
        assertThatThrownBy(() -> postingGateway.post(
                request("shipment:ORD-NEG-0001:1", "SHIPMENT", null, null,
                        line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", 50),
                        line("ICN01", "V-CUSTOMER", "SKU-100001", "DEFAULT", -50)),
                Preconditions.none()))
                .isInstanceOf(PostingException.class)
                .extracting(ex -> ((PostingException) ex).code())
                .isEqualTo("LINE_DIRECTION_MISMATCH");

        assertThat(onHandQty("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT")).isZero();
        assertReconciliationClean();
    }

    // ── MOVE·TRANSFER_OUT·TRANSFER_IN: 가상 로케이션 금지 ───────────────────────────

    @Test
    void moveCannotMixInVirtualLocation() {
        putaway100();

        // 이것이 통과하면 MOVE가 사유 코드도 SUPERVISOR도 없는 조정이 된다 (V-ADJUST로 10개를 버린다).
        assertThatThrownBy(() -> postingGateway.post(
                request("move:MV-NEG-0001", "MOVE", null, null,
                        line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", -10),
                        line("ICN01", "V-ADJUST", "SKU-100001", "DEFAULT", 10)),
                Preconditions.none()))
                .isInstanceOf(PostingException.class)
                .extracting(ex -> ((PostingException) ex).code())
                .isEqualTo("VIRTUAL_LOCATION_NOT_ALLOWED");

        assertThat(onHandQty("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT")).isEqualTo(100);
    }

    @Test
    void transferOutCannotMixInVirtualLocation() {
        putaway100();

        assertThatThrownBy(() -> postingGateway.post(
                request("transfer_out:TR-NEG-0001", "TRANSFER_OUT", null, null,
                        line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", -10),
                        line("ICN01", "V-CUSTOMER", "SKU-100001", "DEFAULT", 10)),
                Preconditions.none()))
                .isInstanceOf(PostingException.class)
                .extracting(ex -> ((PostingException) ex).code())
                .isEqualTo("VIRTUAL_LOCATION_NOT_ALLOWED");
    }

    @Test
    void transferInCannotMixInVirtualLocation() {
        assertThatThrownBy(() -> postingGateway.post(
                request("transfer_in:TR-NEG-0002", "TRANSFER_IN", null, null,
                        line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -10),
                        line("YIT01", "RCV-01", "SKU-100001", "DEFAULT", 10)),
                Preconditions.none()))
                .isInstanceOf(PostingException.class)
                .extracting(ex -> ((PostingException) ex).code())
                .isEqualTo("VIRTUAL_LOCATION_NOT_ALLOWED");
    }

    // ── RETURN: V-CUSTOMER가 음수, 물리 줄이 양수 ───────────────────────────────────

    @Test
    void returnCannotRunBackwards() {
        postAndExpectSuccess(request("return:RMA-NEG-0001:1", "RETURN", null, null,
                line("ICN01", "V-CUSTOMER", "SKU-100001", "DEFAULT", -3),
                line("ICN01", "RTN-01", "SKU-100001", "DEFAULT", 3)));

        // 반품은 고객에게서 들어오는 거래다 — 반대 방향은 출고이지 반품이 아니다.
        assertThatThrownBy(() -> postingGateway.post(
                request("return:RMA-NEG-0002:1", "RETURN", null, null,
                        line("ICN01", "RTN-01", "SKU-100001", "DEFAULT", -3),
                        line("ICN01", "V-CUSTOMER", "SKU-100001", "DEFAULT", 3)),
                Preconditions.none()))
                .isInstanceOf(PostingException.class)
                .extracting(ex -> ((PostingException) ex).code())
                .isEqualTo("LINE_DIRECTION_MISMATCH");

        assertThat(onHandQty("ICN01", "RTN-01", "SKU-100001", "DEFAULT")).isEqualTo(3);
    }

    // ── ADJUSTMENT: V-ADJUST만 허용, 부호는 자유 ────────────────────────────────────

    @Test
    void adjustmentMustUseTheAdjustVirtualLocation() {
        putaway100();

        assertThatThrownBy(() -> postingGateway.post(
                request("adjust:ADJ-NEG-0001", "ADJUSTMENT", "LOST", null,
                        line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", -5),
                        line("ICN01", "V-CUSTOMER", "SKU-100001", "DEFAULT", 5)),
                Preconditions.none()))
                .isInstanceOf(PostingException.class)
                .extracting(ex -> ((PostingException) ex).code())
                .isEqualTo("VIRTUAL_LOCATION_NOT_ALLOWED");
    }

    @Test
    void adjustmentAllowsBothDirections() {
        putaway100();

        // 분실도 발견도 조정이다 — 부호를 묶으면 둘 중 하나를 표현할 수 없다.
        postAndExpectSuccess(request("adjust:ADJ-LOST-0001", "ADJUSTMENT", "LOST", null,
                line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", -5),
                line("ICN01", "V-ADJUST", "SKU-100001", "DEFAULT", 5)));
        postAndExpectSuccess(request("adjust:ADJ-FOUND-0001", "ADJUSTMENT", "FOUND", null,
                line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", 8),
                line("ICN01", "V-ADJUST", "SKU-100001", "DEFAULT", -8)));

        assertThat(onHandQty("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT")).isEqualTo(103);
    }

    // ── REVERSAL: 면제 (대조군) ─────────────────────────────────────────────────────

    @Test
    void reversalIsExemptFromTheRule() {
        long receiptTxnId = postAndExpectSuccess(request("receipt:PO-REV-0001:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -40),
                line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", 40)));

        // 입고의 역분개는 RECEIPT 규칙이 금지하는 바로 그 모양(V-SUPPLIER 양수 / 물리 음수)이다.
        long reversalTxnId = postAndExpectSuccess(request("reverse:PO-REV-0001", "REVERSAL", null, receiptTxnId,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", 40),
                line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", -40)));

        assertThat(txnType(reversalTxnId)).isEqualTo("REVERSAL");
        assertThat(onHandQty("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT")).isZero();
        assertReconciliationClean();
    }

    @Test
    void reversalOfShipmentIsExemptToo() {
        putaway100();
        long shipmentTxnId = postAndExpectSuccess(request("shipment:ORD-REV-0002:1", "SHIPMENT", null, null,
                line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", -30),
                line("ICN01", "V-CUSTOMER", "SKU-100001", "DEFAULT", 30)));

        long reversalTxnId = postAndExpectSuccess(request("reverse:ORD-REV-0002", "REVERSAL", null, shipmentTxnId,
                line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", 30),
                line("ICN01", "V-CUSTOMER", "SKU-100001", "DEFAULT", -30)));

        assertThat(txnType(reversalTxnId)).isEqualTo("REVERSAL");
        assertThat(onHandQty("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT")).isEqualTo(100);
        assertReconciliationClean();
    }

    private void putaway100() {
        postAndExpectSuccess(request("receipt:PO-VLOC-SEED:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -100),
                line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", 100)));
    }
}
