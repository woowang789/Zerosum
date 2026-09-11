package com.zerosum.inventory.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zerosum.inventory.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 포스팅 흐름의 정상 동작과 애플리케이션 검증. db/usecases/A-inbound-outbound.sql의 UC-A01·A02·A10·A11을
 * Testcontainers 위에서 재현한다. 1단계 범위는 RECEIPT/MOVE/ADJUSTMENT/REVERSAL뿐이라
 * SHIPMENT가 쓰이는 원본 시나리오(UC-D14)는 ADJUSTMENT로 대체했다.
 */
class PostingFlowTest extends AbstractIntegrationTest {

    @Test
    void receiptThenPutawayKeepsBalanceAndLedgerConsistent() {
        postAndExpectSuccess(request("receipt:PO-20260908-0042-1:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -120),
                line("ICN01", "RCV-01", "SKU-100001", "DEFAULT", 120)));
        assertThat(onHandQty("ICN01", "RCV-01", "SKU-100001", "DEFAULT")).isEqualTo(120);

        postAndExpectSuccess(request("move:WO-20260911-0007", "MOVE", null, null,
                line("ICN01", "RCV-01", "SKU-100001", "DEFAULT", -120),
                line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", 120)));
        assertThat(onHandQty("ICN01", "RCV-01", "SKU-100001", "DEFAULT")).isZero();
        assertThat(onHandQty("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT")).isEqualTo(120);

        // 잔액 = 원장 합계 (I4)를 직접 확인한다
        Integer ledgerSumAtStorage = jdbcClient.sql("""
                SELECT COALESCE(SUM(e.qty_delta), 0) FROM inventory_ledger_entry e
                JOIN location l ON l.id = e.location_id WHERE l.code = 'A-01-01-1'
                """).query(Integer.class).single();
        assertThat(ledgerSumAtStorage).isEqualTo(120);
    }

    @Test
    void virtualLocationNeverGetsBalanceRow() {
        postAndExpectSuccess(request("receipt:PO-20260908-0043-1:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -50),
                line("ICN01", "RCV-01", "SKU-100001", "DEFAULT", 50)));

        assertThat(balanceRowCount("ICN01", "V-SUPPLIER")).isZero();
    }

    @Test
    void outboxEventCarriesEntriesAndIsPartitionedBySku() {
        long txnId = postAndExpectSuccess(request("receipt:PO-OUTBOX-0001:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -30),
                line("ICN01", "RCV-01", "SKU-100001", "DEFAULT", 30)));

        record OutboxRow(String partitionKey, int entryCount, String firstLocationCode, int firstQtyDelta) {
        }

        OutboxRow row = jdbcClient.sql("""
                SELECT partition_key,
                       jsonb_array_length(payload -> 'entries') AS entry_count,
                       payload -> 'entries' -> 0 ->> 'locationCode' AS first_loc,
                       (payload -> 'entries' -> 0 ->> 'qtyDelta')::int AS first_qty
                FROM outbox_event
                WHERE event_type = 'StockPosted' AND (payload ->> 'txnId')::bigint = :txnId
                """)
                .param("txnId", txnId)
                .query((rs, rowNum) -> new OutboxRow(rs.getString("partition_key"), rs.getInt("entry_count"),
                        rs.getString("first_loc"), rs.getInt("first_qty")))
                .single();

        long skuId = jdbcClient.sql("SELECT id FROM sku WHERE code = 'SKU-100001'").query(Long.class).single();
        assertThat(row.partitionKey()).as("파티션 키는 이 SKU의 id다").isEqualTo(String.valueOf(skuId));
        assertThat(row.entryCount()).as("두 줄(공급사 출발 + RCV-01 도착)이 그대로 담긴다").isEqualTo(2);
        assertThat(row.firstLocationCode()).isEqualTo("V-SUPPLIER");
        assertThat(row.firstQtyDelta()).isEqualTo(-30);
    }

    @Test
    void nonZeroSumCommandIsRejectedByApplicationValidation() {
        assertThatThrownBy(() -> postingGateway.post(
                request("move:BAD-0001", "MOVE", null, null,
                        line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", -5),
                        line("ICN01", "A-01-01-2", "SKU-100001", "DEFAULT", 3)),
                Preconditions.none()))
                .isInstanceOf(PostingException.class)
                .extracting(ex -> ((PostingException) ex).code())
                .isEqualTo("NOT_ZERO_SUM");
    }

    @Test
    void adjustmentWithoutReasonCodeIsRejected() {
        assertThatThrownBy(() -> postingGateway.post(
                request("adjust:NO-REASON", "ADJUSTMENT", null, null,
                        line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", -1),
                        line("ICN01", "V-ADJUST", "SKU-100001", "DEFAULT", 1)),
                Preconditions.none()))
                .isInstanceOf(PostingException.class)
                .extracting(ex -> ((PostingException) ex).code())
                .isEqualTo("REASON_REQUIRED");
    }

    @Test
    void reversalRestoresBalanceAndDoubleReversalViolatesUniqueConstraint() {
        putawayTshirts120();

        long adjustmentTxnId = postAndExpectSuccess(request("adjust:INC-20260911-0012", "ADJUSTMENT",
                "DAMAGED_IN_STORAGE", null,
                line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", -2),
                line("ICN01", "V-ADJUST", "SKU-100001", "DEFAULT", 2)));
        assertThat(onHandQty("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT")).isEqualTo(118);

        postAndExpectSuccess(request("reverse:INC-20260911-0012", "REVERSAL", null, adjustmentTxnId,
                line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", 2),
                line("ICN01", "V-ADJUST", "SKU-100001", "DEFAULT", -2)));
        assertThat(onHandQty("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT")).isEqualTo(120);

        assertThatThrownBy(() -> postingGateway.post(
                request("reverse:INC-20260911-0012-again", "REVERSAL", null, adjustmentTxnId,
                        line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", 2),
                        line("ICN01", "V-ADJUST", "SKU-100001", "DEFAULT", -2)),
                Preconditions.none()))
                .isInstanceOf(DataIntegrityViolationException.class); // inventory_txn.reverses_txn_id UNIQUE
    }

    @Test
    void reversingAlreadyConsumedReceiptFailsViaNegativePrevention() {
        long receiptTxnId = postAndExpectSuccess(request("receipt:PO-20260911-0101-1:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-300001", "DEFAULT", -20),
                line("ICN01", "A-02-01-1", "SKU-300001", "DEFAULT", 20)));

        // SHIPMENT는 1단계 범위 밖이라, "이미 소진된 재고"를 ADJUSTMENT로 재현한다 (db/usecases/D-invariants.sql UC-D14 대응)
        postAndExpectSuccess(request("adjust:INC-20260911-0030", "ADJUSTMENT", "LOST", null,
                line("ICN01", "A-02-01-1", "SKU-300001", "DEFAULT", -15),
                line("ICN01", "V-ADJUST", "SKU-300001", "DEFAULT", 15)));
        assertThat(onHandQty("ICN01", "A-02-01-1", "SKU-300001", "DEFAULT")).isEqualTo(5);

        assertThatThrownBy(() -> postingGateway.post(
                request("reverse:PO-20260911-0101", "REVERSAL", null, receiptTxnId,
                        line("ICN01", "V-SUPPLIER", "SKU-300001", "DEFAULT", 20),
                        line("ICN01", "A-02-01-1", "SKU-300001", "DEFAULT", -20)),
                Preconditions.none()))
                .isInstanceOf(PostingException.class)
                .extracting(ex -> ((PostingException) ex).code())
                .isEqualTo("INSUFFICIENT_STOCK");
    }

    private void putawayTshirts120() {
        postAndExpectSuccess(request("receipt:PO-SEED-0001:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -120),
                line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", 120)));
    }
}
