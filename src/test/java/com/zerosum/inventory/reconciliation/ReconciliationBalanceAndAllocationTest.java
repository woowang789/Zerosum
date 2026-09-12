package com.zerosum.inventory.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.zerosum.inventory.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 정합 검증 배치 ①(잔액 투영)·②(할당) — db/usecases/F-reconciliation.sql UC-F02·UC-F03과 같은 시나리오를
 * Java 배치(ReconciliationService)로 재현한다.
 */
class ReconciliationBalanceAndAllocationTest extends AbstractIntegrationTest {

    @Autowired
    private ReconciliationService reconciliationService;

    @Test
    void cleanStateProducesNoIssues() {
        postAndExpectSuccess(request("receipt:RECON-BASE-0001:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -30),
                line("ICN01", "B-01-01-1", "SKU-100001", "DEFAULT", 30)));

        assertThat(reconciliationService.runOnce()).as("정상 상태에서는 이슈가 없다").isZero();
        assertThat(openIssueCount()).isZero();
        assertReconciliationClean();
    }

    @Test
    void projectionMismatchIsDetectedAndRecordedWithoutAutoCorrection() {
        postAndExpectSuccess(request("receipt:RECON-F02-0001:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -30),
                line("ICN01", "B-01-01-1", "SKU-100001", "DEFAULT", 30)));

        // UC-F02a와 동일: 잔액 투영을 몰래 3개 늘린다 (버그 시뮬레이션)
        corruptOnHandQty("B-01-01-1", "SKU-100001", 3);

        assertThat(reconciliationService.runOnce()).isEqualTo(1);

        String status = jdbcClient.sql("SELECT status FROM inventory_issue WHERE issue_type = 'PROJECTION_MISMATCH'")
                .query(String.class)
                .single();
        assertThat(status).isEqualTo("OPEN");

        // 자동 보정하지 않는다 — 잔액은 조작한 값 그대로다
        assertThat(onHandQty("ICN01", "B-01-01-1", "SKU-100001", "DEFAULT")).as("배치는 보정하지 않는다").isEqualTo(33);

        corruptOnHandQty("B-01-01-1", "SKU-100001", -3); // 원복
    }

    @Test
    void repeatedRunsDoNotDuplicateTheSameOpenIssue() {
        postAndExpectSuccess(request("receipt:RECON-F02-0002:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -10),
                line("ICN01", "B-01-01-1", "SKU-100001", "DEFAULT", 10)));
        corruptOnHandQty("B-01-01-1", "SKU-100001", 5);

        assertThat(reconciliationService.runOnce()).as("첫 실행에서 1건 기록").isEqualTo(1);
        assertThat(reconciliationService.runOnce()).as("두 번째 실행은 이미 열려 있어 0건").isZero();
        assertThat(countIssues("PROJECTION_MISMATCH")).isEqualTo(1);

        corruptOnHandQty("B-01-01-1", "SKU-100001", -5); // 원복
    }

    @Test
    void allocationMismatchIsDetectedWithoutAutoCorrection() {
        // DMG-01에는 시드 데이터에 재고가 없으니 먼저 넣는다 (UC-F03 전제: on_hand_qty > 0)
        postAndExpectSuccess(request("receipt:RECON-F03-0001:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -8),
                line("ICN01", "DMG-01", "SKU-100001", "DEFAULT", 8)));

        // UC-F03a와 동일: ACTIVE 할당 없이 할당량만 올린다
        jdbcClient.sql("""
                UPDATE stock_balance SET allocated_qty = 1
                WHERE location_id = (SELECT id FROM location WHERE code = 'DMG-01'
                                      AND warehouse_id = (SELECT id FROM warehouse WHERE code = 'ICN01'))
                  AND on_hand_qty > 0
                """)
                .update();

        assertThat(reconciliationService.runOnce()).isEqualTo(1);
        assertThat(countIssues("ALLOCATION_MISMATCH")).isEqualTo(1);

        // 자동 보정하지 않는다
        int allocatedQty = jdbcClient.sql("""
                SELECT allocated_qty FROM stock_balance
                WHERE location_id = (SELECT id FROM location WHERE code = 'DMG-01'
                                      AND warehouse_id = (SELECT id FROM warehouse WHERE code = 'ICN01'))
                """)
                .query(Integer.class)
                .single();
        assertThat(allocatedQty).as("배치는 보정하지 않는다").isEqualTo(1);

        jdbcClient.sql("""
                UPDATE stock_balance SET allocated_qty = 0
                WHERE location_id = (SELECT id FROM location WHERE code = 'DMG-01'
                                      AND warehouse_id = (SELECT id FROM warehouse WHERE code = 'ICN01'))
                """)
                .update(); // 원복
    }

    private void corruptOnHandQty(String locationCode, String skuCode, int delta) {
        jdbcClient.sql("""
                UPDATE stock_balance SET on_hand_qty = on_hand_qty + :delta
                WHERE location_id = (SELECT id FROM location WHERE code = :loc
                                      AND warehouse_id = (SELECT id FROM warehouse WHERE code = 'ICN01'))
                  AND sku_id = (SELECT id FROM sku WHERE code = :sku)
                """)
                .param("delta", delta)
                .param("loc", locationCode)
                .param("sku", skuCode)
                .update();
    }

    private int countIssues(String issueType) {
        return jdbcClient.sql("SELECT count(*) FROM inventory_issue WHERE issue_type = :type")
                .param("type", issueType)
                .query(Integer.class)
                .single();
    }

    private int openIssueCount() {
        return jdbcClient.sql("SELECT count(*) FROM inventory_issue WHERE status = 'OPEN'")
                .query(Integer.class)
                .single();
    }
}
