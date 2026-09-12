package com.zerosum.inventory.repository;

import com.zerosum.inventory.domain.BalanceId;
import com.zerosum.inventory.domain.BalanceKey;
import com.zerosum.inventory.domain.LocationId;
import com.zerosum.inventory.domain.LockedBalance;
import com.zerosum.inventory.domain.LockedBalances;
import com.zerosum.inventory.domain.LotId;
import com.zerosum.inventory.domain.PostingException;
import com.zerosum.inventory.domain.ResolvedLine;
import com.zerosum.inventory.domain.SkuId;
import com.zerosum.inventory.domain.WarehouseId;
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
        return applyDelta(id, qtyDelta, 0);
    }

    /**
     * 출고(SHIPMENT)처럼 할당을 소진하면서 같은 줄에서 allocated_qty도 함께 줄여야 하는 경우의 일반형.
     * consumedQty가 0이면 위 {@link #applyDelta(BalanceId, int)}와 완전히 같다 (그 메서드가 이걸 호출한다).
     * db/04-harness.sql tst_post ⑤의 UPDATE(on_hand_qty += qty, allocated_qty -= v_used)와 대응한다.
     *
     * <p><b>두 CHECK가 여전히 도달 불가능한 이유.</b> WHERE 절 조건 G: {@code on_hand_qty - allocated_qty +
     * consumedQty + delta >= 0}는 갱신 후 값으로 다시 쓰면 {@code on_hand_qty' - allocated_qty' >= 0}
     * (on_hand_qty' = on_hand_qty + delta, allocated_qty' = allocated_qty - consumedQty)이므로, G를 만족하는
     * 갱신은 항상 on_hand_qty' &gt;= allocated_qty'를 만든다 — 이것만으로 이미
     * {@code CHECK (allocated_qty <= on_hand_qty)}는 도달 불가능하다(대수적으로 자명, 외부 가정 불필요).
     * 남은 것은 allocated_qty' &gt;= 0(그러면 on_hand_qty' &gt;= 0도 따라 나온다)인데, 이는
     * consumedQty &lt;= allocated_qty(갱신 전)일 때 성립한다. consumedQty는 이 잔액 행을 가리키는 ACTIVE
     * 할당(각 qty &gt; 0, allocation.qty CHECK) 중 이번에 소진할 것들만 골라 합한 값이므로, "이 잔액 행의
     * ACTIVE 할당 qty 합 = allocated_qty" (I5, docs/01-principles.md)가 성립하는 한 그 부분합은 전체 이하다.
     * I5는 DB CHECK가 아니라 할당·해제·소진 코드가 allocated_qty와 할당 행 상태를 항상 같은 트랜잭션에서
     * 짝지어 바꾸는 방식으로 유지하는 애플리케이션 불변식이다 — 원래 on_hand_qty &gt;= 0 논증이 CHECK 하나
     * (allocated_qty &gt;= 0)에만 기댔던 것보다 한 단계 더 나아간 전제지만, db/04-harness.sql의 tst_post도
     * 같은 전제로 v_used를 계산한다(재검증 없이). consumedQty가 가리키는 할당이 ACTIVE가 아니게 됐거나
     * 이미 다른 거래에 소진됐다면 별도로 호출되는 할당 소진 UPDATE의 영향 행 수 확인이 이를 잡아
     * ALLOC_NOT_ACTIVE로 롤백시킨다 (AllocationRepository#markConsumed).
     *
     * <p><b>위 argument는 호출 한 번에 대한 것 — 같은 잔액 행에 여러 번 호출되면 별개로 지켜야 한다.</b>
     * consumedQty &lt;= allocated_qty(갱신 전)는 "그 호출 시점의" allocated_qty를 기준으로 하므로, 같은 거래에서
     * 같은 잔액 id에 이 메서드를 두 번 부르면서 둘 다 같은(0이 아닌) consumedQty를 넘기면 — 두 번째 호출은
     * 첫 번째 호출이 이미 줄인 allocated_qty를 기준으로 다시 그만큼을 요구하는 셈이라 이 argument가 깨진다
     * (검증에서 발견된 결함: on_hand_qty는 줄마다 delta가 달라 상대 갱신으로 누적해도 맞지만, consumedQty는
     * "그 잔액 행에서 이번 거래에 소진할 총량"으로 줄과 무관한 고정값이라 줄마다 반복 적용하면 안 된다).
     * 이 메서드는 한 번의 호출만 보고 있어 그 사실을 알 수 없다 — 호출부(PostingService)가 거래당 잔액 id별로
     * consumedQty를 정확히 한 번만(첫 줄에서) 건네고 같은 키의 나머지 줄에는 0을 건네는 방식으로 이 전제를 지킨다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int applyDelta(BalanceId id, int qtyDelta, int consumedQty) {
        Optional<Integer> newOnHandQty = jdbc.sql("""
                UPDATE stock_balance
                SET on_hand_qty = on_hand_qty + :delta,
                    allocated_qty = allocated_qty - :consumedQty,
                    updated_at = now()
                WHERE id = :id AND (on_hand_qty - allocated_qty + :consumedQty + :delta) >= 0
                RETURNING on_hand_qty
                """)
                .param("delta", qtyDelta)
                .param("consumedQty", consumedQty)
                .param("id", id.value())
                .query(Integer.class)
                .optional();

        return newOnHandQty.orElseThrow(() -> insufficientStock(id, qtyDelta, consumedQty));
    }

    private PostingException insufficientStock(BalanceId id, int qtyDelta, int consumedQty) {
        record CurrentBalance(int onHandQty, int allocatedQty) {
        }
        CurrentBalance current = jdbc.sql("SELECT on_hand_qty, allocated_qty FROM stock_balance WHERE id = :id")
                .param("id", id.value())
                .query((rs, rowNum) -> new CurrentBalance(rs.getInt("on_hand_qty"), rs.getInt("allocated_qty")))
                .single();
        int available = current.onHandQty() - current.allocatedQty() + consumedQty;
        return new PostingException("INSUFFICIENT_STOCK", "가용 %d, 요청 %d".formatted(available, -qtyDelta));
    }
}
