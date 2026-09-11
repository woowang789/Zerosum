package com.zerosum.inventory.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zerosum.inventory.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/** 멱등성. db/usecases/B-idempotency.sql의 UC-B01~B03을 재현한다. */
class IdempotencyTest extends AbstractIntegrationTest {

    @Test
    void sameIdemKeyReplayReturnsStoredResultWithoutCreatingSecondTxn() {
        PostingRequest putaway = request("move:WO-20260911-0007", "MOVE", null, null,
                line("ICN01", "RCV-01", "SKU-100001", "DEFAULT", -120),
                line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", 120));

        // 적치 대상 재고를 먼저 만든다
        postAndExpectSuccess(request("receipt:PO-20260908-0042-1:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -120),
                line("ICN01", "RCV-01", "SKU-100001", "DEFAULT", 120)));

        long firstTxnId = postAndExpectSuccess(putaway);
        long replayTxnId = postAndExpectSuccess(putaway);

        assertThat(replayTxnId).isEqualTo(firstTxnId);
        Integer txnCount = jdbcClient.sql("SELECT count(*) FROM inventory_txn WHERE idem_key = :key")
                .param("key", "move:WO-20260911-0007")
                .query(Integer.class)
                .single();
        assertThat(txnCount).isEqualTo(1);
        // 재적용되지 않아 재고는 한 번만 옮겨진 상태여야 한다
        assertThat(onHandQty("ICN01", "RCV-01", "SKU-100001", "DEFAULT")).isZero();
        assertThat(onHandQty("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT")).isEqualTo(120);
    }

    @Test
    void sameIdemKeyWithDifferentBodyIsRejectedAsConflict() {
        postAndExpectSuccess(request("receipt:PO-CONFLICT-0001:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-100001", "DEFAULT", -100),
                line("ICN01", "RCV-01", "SKU-100001", "DEFAULT", 100)));

        postAndExpectSuccess(request("move:WO-CONFLICT", "MOVE", null, null,
                line("ICN01", "RCV-01", "SKU-100001", "DEFAULT", -1),
                line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", 1)));

        assertThatThrownBy(() -> postingGateway.post(
                request("move:WO-CONFLICT", "MOVE", null, null,
                        line("ICN01", "RCV-01", "SKU-100001", "DEFAULT", -99),
                        line("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", 99)),
                Preconditions.none()))
                .isInstanceOf(IdempotencyConflictException.class)
                .extracting(ex -> ((PostingException) ex).code())
                .isEqualTo("IDEM_CONFLICT_409");
    }

    @Test
    void keyRolledBackByBusinessErrorIsReEvaluatedAgainstCurrentStockOnRetry() {
        String idemKey = "adjust:INC-RETRY-0001";
        PostingRequest adjustment = request(idemKey, "ADJUSTMENT", "LOST", null,
                line("ICN01", "A-02-01-1", "SKU-300001", "DEFAULT", -5),
                line("ICN01", "V-ADJUST", "SKU-300001", "DEFAULT", 5));

        // 1차: A-02-01-1에 아직 잔액 행이 없어 NO_STOCK으로 실패 → 전체 롤백
        assertThatThrownBy(() -> postingGateway.post(adjustment, Preconditions.none()))
                .isInstanceOf(PostingException.class)
                .extracting(ex -> ((PostingException) ex).code())
                .isEqualTo("NO_STOCK");

        Integer idemRowCount = jdbcClient.sql("SELECT count(*) FROM idempotency_record WHERE idem_key = :key")
                .param("key", idemKey)
                .query(Integer.class)
                .single();
        assertThat(idemRowCount).as("실패한 키는 멱등 기록도 함께 롤백되어 남지 않는다").isZero();

        // 입고로 재고를 채운 뒤
        postAndExpectSuccess(request("receipt:PO-RETRY-0001:1", "RECEIPT", null, null,
                line("ICN01", "V-SUPPLIER", "SKU-300001", "DEFAULT", -20),
                line("ICN01", "A-02-01-1", "SKU-300001", "DEFAULT", 20)));

        // 2차: 같은 멱등 키로 재시도하면 그 시점 재고로 재평가되어 성공한다
        postAndExpectSuccess(adjustment);
        assertThat(onHandQty("ICN01", "A-02-01-1", "SKU-300001", "DEFAULT")).isEqualTo(15);
    }
}
