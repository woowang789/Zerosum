package com.zerosum.inventory.posting;

import static org.assertj.core.api.Assertions.assertThat;

import com.zerosum.inventory.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * 반품과 센터 간 이동. db/usecases/A-inbound-outbound.sql UC-A07~A09(반품)·UC-A12~A13(센터 간 이동)을 재현한다.
 * 두 거래 유형 모두 RECEIPT/MOVE와 마찬가지로 PostingService에 거래 유형과 물리 줄만 주면 그대로 통과한다
 * (txn_type 화이트리스트는 DB CHECK가 갖고 있어 애플리케이션 쪽에 새로 막을 것이 없었다) — 새 프로덕션
 * 코드 없이 이 테스트만으로 확인한다.
 */
class ReturnAndTransferTest extends AbstractIntegrationTest {

    @Test
    void returnInboundThenInspectionSplitsIntoGoodAndDamaged() {
        postAndExpectSuccess(request("receipt:PO-RET-0001:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -120),
                line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", 120)));

        // 고객 가상 로케이션 → RETURN_HOLD
        postAndExpectSuccess(request("return:RMA-0001:1", "RETURN", null, null,
                line("ICN01", "V-CUSTOMER", "SKU-100001", "DEFAULT", -3),
                line("ICN01", "RTN-01", "SKU-100001", "DEFAULT", 3)));
        assertThat(onHandQty("ICN01", "RTN-01", "SKU-100001", "DEFAULT")).isEqualTo(3);

        // 검수 판정은 기존 MOVE로 처리한다 (새로 만들 것 없음)
        postAndExpectSuccess(request("move:RMA-0001:pass", "MOVE", null, null,
                line("ICN01", "RTN-01", "SKU-100001", "DEFAULT", -2),
                line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", 2)));
        postAndExpectSuccess(request("move:RMA-0001:fail", "MOVE", null, null,
                line("ICN01", "RTN-01", "SKU-100001", "DEFAULT", -1),
                line("ICN01", "DMG-01", "SKU-100001", "DEFAULT", 1)));

        assertThat(onHandQty("ICN01", "RTN-01", "SKU-100001", "DEFAULT")).isZero();
        assertThat(onHandQty("ICN01", "DMG-01", "SKU-100001", "DEFAULT")).isEqualTo(1);
        assertThat(onHandQty("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT")).isEqualTo(122);
    }

    @Test
    void transferOutMakesStockVisibleInTransit() {
        postAndExpectSuccess(request("receipt:PO-TR-0001:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -120),
                line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", 120)));

        postAndExpectSuccess(request("transfer_out:TR-0001", "TRANSFER_OUT", null, null,
                line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", -40),
                line("ICN01", "TRS-01", "SKU-100001", "DEFAULT", 40)));

        assertThat(onHandQty("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT")).isEqualTo(80);
        assertThat(onHandQty("ICN01", "TRS-01", "SKU-100001", "DEFAULT"))
                .as("운송 중에도 재고가 사라지지 않고 TRANSIT 잔액으로 보인다").isEqualTo(40);
    }

    @Test
    void transferInArrivesAtDestinationWarehouseAcrossTwoWarehouses() {
        postAndExpectSuccess(request("receipt:PO-TR-0002:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -40),
                line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", 40)));
        postAndExpectSuccess(request("transfer_out:TR-0002", "TRANSFER_OUT", null, null,
                line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", -40),
                line("ICN01", "TRS-01", "SKU-100001", "DEFAULT", 40)));

        // 도착 스캔 한 거래가 ICN01(출발 창고)과 YIT01(도착 창고) 두 곳에 걸친다
        postAndExpectSuccess(request("transfer_in:TR-0002", "TRANSFER_IN", null, null,
                line("ICN01", "TRS-01", "SKU-100001", "DEFAULT", -40),
                line("YIT01", "RCV-01", "SKU-100001", "DEFAULT", 40)));

        assertThat(onHandQty("ICN01", "TRS-01", "SKU-100001", "DEFAULT")).isZero();
        assertThat(onHandQty("YIT01", "RCV-01", "SKU-100001", "DEFAULT")).isEqualTo(40);
    }
}
