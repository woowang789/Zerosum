package com.zerosum.inventory.repository;

import com.zerosum.inventory.domain.ResolvedLine;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 원장 기록. on_hand_after는 물리 로케이션만 값을 갖고, 가상 로케이션은 null이다 (I3, I4). */
@Repository
public class LedgerRepository {

    private final JdbcClient jdbc;

    LedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(long txnId, ResolvedLine entry, Integer onHandAfter) {
        jdbc.sql("""
                INSERT INTO inventory_ledger_entry
                    (txn_id, warehouse_id, location_id, sku_id, lot_id, qty_delta, on_hand_after)
                VALUES (:txnId, :warehouseId, :locationId, :skuId, :lotId, :qtyDelta, :onHandAfter)
                """)
                .param("txnId", txnId)
                .param("warehouseId", entry.warehouseId().value())
                .param("locationId", entry.locationId().value())
                .param("skuId", entry.skuId().value())
                .param("lotId", entry.lotId().value())
                .param("qtyDelta", entry.qtyDelta())
                .param("onHandAfter", onHandAfter)
                .update();
    }
}
