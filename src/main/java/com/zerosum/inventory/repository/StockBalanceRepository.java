package com.zerosum.inventory.repository;

import com.zerosum.inventory.posting.BalanceId;
import com.zerosum.inventory.posting.BalanceKey;
import com.zerosum.inventory.posting.LocationId;
import com.zerosum.inventory.posting.LockedBalance;
import com.zerosum.inventory.posting.LockedBalances;
import com.zerosum.inventory.posting.LotId;
import com.zerosum.inventory.posting.PostingException;
import com.zerosum.inventory.posting.ResolvedLine;
import com.zerosum.inventory.posting.SkuId;
import com.zerosum.inventory.posting.WarehouseId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * stock_balance 잠금과 갱신. db/04-harness.sql의 tst_post ③·⑤에 대응한다.
 * 없는 잔액 행은 잠금 전에 키 정렬 순서로 0 수량 먼저 생성하고(docs/04-write-path.md 잠금 규칙),
 * 잠금은 잔액 행의 id 오름차순으로 한 행씩 잡는다.
 */
@Repository
public class StockBalanceRepository {

    private final JdbcClient jdbc;

    StockBalanceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insertZeroRowsIfAbsent(List<ResolvedLine> positiveDeltaPhysicalLines) {
        for (ResolvedLine line : positiveDeltaPhysicalLines) {
            jdbc.sql("""
                    INSERT INTO stock_balance (warehouse_id, location_id, sku_id, lot_id)
                    VALUES (:warehouseId, :locationId, :skuId, :lotId)
                    ON CONFLICT (location_id, sku_id, lot_id) DO NOTHING
                    """)
                    .param("warehouseId", line.warehouseId().value())
                    .param("locationId", line.locationId().value())
                    .param("skuId", line.skuId().value())
                    .param("lotId", line.lotId().value())
                    .update();
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public LockedBalances lockForUpdateOrderById(List<BalanceKey> keys) {
        List<Long> ids = new ArrayList<>();
        for (BalanceKey key : keys) {
            jdbc.sql("""
                    SELECT id FROM stock_balance WHERE location_id = :loc AND sku_id = :sku AND lot_id = :lot
                    """)
                    .param("loc", key.locationId().value())
                    .param("sku", key.skuId().value())
                    .param("lot", key.lotId().value())
                    .query(Long.class)
                    .optional()
                    .ifPresent(ids::add);
        }
        ids.sort(Comparator.naturalOrder());

        List<LockedBalance> locked = new ArrayList<>();
        for (Long id : ids) {
            LockedBalance row = jdbc.sql("""
                    SELECT id, warehouse_id, location_id, sku_id, lot_id, on_hand_qty, allocated_qty
                    FROM stock_balance WHERE id = :id FOR UPDATE
                    """)
                    .param("id", id)
                    .query((rs, rowNum) -> new LockedBalance(
                            new BalanceId(rs.getLong("id")),
                            new WarehouseId(rs.getLong("warehouse_id")),
                            new BalanceKey(new LocationId(rs.getLong("location_id")),
                                    new SkuId(rs.getLong("sku_id")), new LotId(rs.getLong("lot_id"))),
                            rs.getInt("on_hand_qty"),
                            rs.getInt("allocated_qty")))
                    .single();
            locked.add(row);
        }
        return new LockedBalances(locked);
    }

    /**
     * 잔액에 상대 갱신(on_hand_qty = on_hand_qty + delta)을 적용하고 적용 직후 값을 돌려준다.
     * 한 거래에 같은 잔액 키(로케이션·SKU·로트)를 가리키는 줄이 여러 개일 수 있으므로, 잠금 시점의
     * 스냅샷(LockedBalance)에서 계산해 절대값으로 덮어쓰면 먼저 적용된 줄의 결과가 지워진다
     * (검증에서 발견된 결함). db/04-harness.sql의 tst_post가 매 줄마다 SELECT로 다시 읽고
     * on_hand_qty + qty로 상대 갱신하는 것과 같은 이유로, 여기서도 DB에 상대 갱신을 맡겨
     * 같은 트랜잭션 안의 앞선 갱신이 자동으로 반영되게 한다.
     * <p>WHERE 절의 (on_hand_qty - allocated_qty + delta) &gt;= 0 조건이 가용 검사를 겸한다 — 증가(delta &gt; 0)면
     * 항상 참이고, 차감이면 "가용 이상 차감 금지"와 정확히 같다. 이 조건도 매번 그 시점의 현재값을 기준으로
     * 평가되므로, 같은 키에 대한 차감이 여러 줄에 걸쳐 있어도 누적 기준으로 걸린다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int applyDelta(BalanceId id, int qtyDelta) {
        Optional<Integer> newOnHandQty = jdbc.sql("""
                UPDATE stock_balance
                SET on_hand_qty = on_hand_qty + :delta, updated_at = now()
                WHERE id = :id AND (on_hand_qty - allocated_qty + :delta) >= 0
                RETURNING on_hand_qty
                """)
                .param("delta", qtyDelta)
                .param("id", id.value())
                .query(Integer.class)
                .optional();

        return newOnHandQty.orElseThrow(() -> insufficientStock(id, qtyDelta));
    }

    private PostingException insufficientStock(BalanceId id, int qtyDelta) {
        record CurrentBalance(int onHandQty, int allocatedQty) {
        }
        CurrentBalance current = jdbc.sql("SELECT on_hand_qty, allocated_qty FROM stock_balance WHERE id = :id")
                .param("id", id.value())
                .query((rs, rowNum) -> new CurrentBalance(rs.getInt("on_hand_qty"), rs.getInt("allocated_qty")))
                .single();
        int available = current.onHandQty() - current.allocatedQty();
        return new PostingException("INSUFFICIENT_STOCK", "가용 %d, 요청 %d".formatted(available, -qtyDelta));
    }
}
