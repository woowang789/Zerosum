package com.zerosum.inventory.repository;

import com.zerosum.inventory.domain.AllocationCandidate;
import com.zerosum.inventory.domain.AllocationException;
import com.zerosum.inventory.domain.AllocationId;
import com.zerosum.inventory.domain.BalanceId;
import com.zerosum.inventory.domain.PostingException;
import com.zerosum.inventory.domain.SkuId;
import com.zerosum.inventory.domain.WarehouseId;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code allocation} 테이블 접근. db/04-harness.sql의 tst_allocate·tst_release_alloc과, tst_post의
 * 할당 소진 부분(⑤~⑥)에 대응한다. 할당은 실재고를 바꾸지 않으므로 location은 잠그지 않고 stock_balance만
 * id 오름차순으로 FOR UPDATE 잠근다 (docs/04-write-path.md 잠금 규칙).
 */
@Repository
public class AllocationRepository {

    private final JdbcClient jdbc;

    AllocationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ── 멱등 키. allocate/release가 재현 정책을 달리 하므로(AllocationService 참고)
    // 선점(tryClaimIdempotencyKey)만 공통이고 나머지는 각자 조합해 쓴다. ────────────────

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean tryClaimIdempotencyKey(String idemKey, String commandType, String requestHash) {
        int inserted = jdbc.sql("""
                INSERT INTO idempotency_record (idem_key, command_type, request_hash)
                VALUES (:idemKey, :commandType, :requestHash)
                ON CONFLICT (idem_key) DO NOTHING
                """)
                .param("idemKey", idemKey)
                .param("commandType", commandType)
                .param("requestHash", requestHash)
                .update();
        return inserted > 0;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String storedRequestHash(String idemKey) {
        return jdbc.sql("SELECT request_hash FROM idempotency_record WHERE idem_key = :idemKey")
                .param("idemKey", idemKey)
                .query(String.class)
                .single();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<AllocationId> replayAllocationIds(String idemKey) {
        return jdbc.sql("""
                SELECT jsonb_array_elements_text(result -> 'allocationIds')::BIGINT AS id
                FROM idempotency_record WHERE idem_key = :idemKey
                """)
                .param("idemKey", idemKey)
                .query((rs, rowNum) -> new AllocationId(rs.getLong("id")))
                .list();
    }

    // JSON 라이브러리 없이 SQL에서 직접 배열을 짓는다 (OutboxRepository와 같은 이유 — 그 클래스의 주석 참고).
    @Transactional(propagation = Propagation.MANDATORY)
    public void completeAllocated(String idemKey, List<AllocationId> ids) {
        StringBuilder idsExpr = new StringBuilder("jsonb_build_array(");
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                idsExpr.append(", ");
            }
            idsExpr.append(":id").append(i);
            params.put("id" + i, ids.get(i).value());
        }
        idsExpr.append(')');
        params.put("idemKey", idemKey);

        jdbc.sql("""
                UPDATE idempotency_record SET result = jsonb_build_object('allocationIds', %s)
                WHERE idem_key = :idemKey
                """.formatted(idsExpr))
                .params(params)
                .update();
    }

    // ── FEFO 후보 조회·잠금 (docs/04-write-path.md 할당 흐름) ───────────────────────────

    /**
     * 판매 가능(STORAGE) 로케이션의 잔액 후보 id를 잠금 없이 조회해 id 오름차순으로 돌려준다.
     * allowInCount가 거짓이면 실사 중인 로케이션은 제외한다. 가용 수량(on_hand − allocated) 조건은 아직
     * 걸지 않는다 — 후보는 정체성(id)만으로 모으고, 실제 가용은 잠근 뒤 {@link #loadForFefo}에서 다시 본다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Long> findCandidateBalanceIds(WarehouseId warehouseId, SkuId skuId, boolean allowInCount) {
        return jdbc.sql("""
                SELECT b.id FROM stock_balance b
                JOIN location l ON l.id = b.location_id AND l.is_sellable
                WHERE b.warehouse_id = :wh AND b.sku_id = :sku
                  AND (:allowInCount OR l.count_session_id IS NULL)
                ORDER BY b.id
                """)
                .param("wh", warehouseId.value())
                .param("sku", skuId.value())
                .param("allowInCount", allowInCount)
                .query(Long.class)
                .list();
    }

    /** 후보를 주어진 순서(id 오름차순)로 한 행씩 FOR UPDATE 잠근다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForUpdate(List<Long> balanceIds) {
        for (Long id : balanceIds) {
            jdbc.sql("SELECT 1 FROM stock_balance WHERE id = :id FOR UPDATE")
                    .param("id", id)
                    .query(Integer.class)
                    .optional();
        }
    }

    /**
     * 잠근 뒤 FEFO 정렬에 필요한 값을 다시 읽는다. 이미 이 트랜잭션이 FOR UPDATE로 잠근 뒤라 다시 잠그지
     * 않는다 (db/04-harness.sql tst_allocate의 두 번째 조회와 같은 이유). FEFO 정렬 자체는 서비스가 메모리에서 한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<AllocationCandidate> loadForFefo(List<Long> balanceIds) {
        if (balanceIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT b.id, b.on_hand_qty, b.allocated_qty, lo.expiry_date
                FROM stock_balance b JOIN lot lo ON lo.id = b.lot_id
                WHERE b.id IN (:ids)
                """)
                .param("ids", balanceIds)
                .query((rs, rowNum) -> new AllocationCandidate(new BalanceId(rs.getLong("id")),
                        rs.getInt("on_hand_qty"), rs.getInt("allocated_qty"),
                        rs.getObject("expiry_date", LocalDate.class)))
                .list();
    }

    // ── 할당 적용 ────────────────────────────────────────────────────────────────────

    @Transactional(propagation = Propagation.MANDATORY)
    public void incrementAllocated(BalanceId balanceId, int qty) {
        jdbc.sql("UPDATE stock_balance SET allocated_qty = allocated_qty + :qty, updated_at = now() WHERE id = :id")
                .param("qty", qty)
                .param("id", balanceId.value())
                .update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AllocationId insertAllocation(String idemKey, String orderLineRef, BalanceId balanceId, int qty) {
        long id = jdbc.sql("""
                INSERT INTO allocation (idem_key, order_line_ref, balance_id, qty)
                VALUES (:idemKey, :orderLineRef, :balanceId, :qty)
                RETURNING id
                """)
                .param("idemKey", idemKey)
                .param("orderLineRef", orderLineRef)
                .param("balanceId", balanceId.value())
                .param("qty", qty)
                .query(Long.class)
                .single();
        return new AllocationId(id);
    }

    // ── 할당 해제 (docs/04-write-path.md 할당 해제 규칙) ────────────────────────────────

    /** 해제 대상 할당이 가리키는 잔액 행 id, 중복 제거 후 오름차순. FOR UPDATE 잠금 순서. */
    @Transactional(propagation = Propagation.MANDATORY)
    /** 소진 대상 할당들이 걸린 주문 줄. 둘 이상이면 한 출고가 여러 주문의 예약을 섞어 소진하는 것이다. */
    public List<String> distinctOrderLineRefs(List<Long> allocationIds) {
        if (allocationIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT DISTINCT order_line_ref FROM allocation WHERE id IN (:ids) ORDER BY 1")
                .param("ids", allocationIds)
                .query(String.class)
                .list();
    }

    public List<Long> distinctBalanceIds(List<AllocationId> allocationIds) {
        if (allocationIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT DISTINCT balance_id FROM allocation WHERE id IN (:ids) ORDER BY 1")
                .param("ids", allocationIds.stream().map(AllocationId::value).toList())
                .query(Long.class)
                .list();
    }

    public record AllocationRow(AllocationId id, BalanceId balanceId, int qty) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<AllocationRow> findByIdsOrderById(List<AllocationId> allocationIds) {
        if (allocationIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT id, balance_id, qty FROM allocation WHERE id IN (:ids) ORDER BY id")
                .param("ids", allocationIds.stream().map(AllocationId::value).toList())
                .query((rs, rowNum) -> new AllocationRow(new AllocationId(rs.getLong("id")),
                        new BalanceId(rs.getLong("balance_id")), rs.getInt("qty")))
                .list();
    }

    /** {@code WHERE id = :id AND status = 'ACTIVE'}의 영향 행 수가 1인지 확인한다. 이미 닫혔으면 예외. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void closeAsReleased(AllocationId id) {
        int updated = jdbc.sql("""
                UPDATE allocation SET status = 'RELEASED', closed_at = now() WHERE id = :id AND status = 'ACTIVE'
                """)
                .param("id", id.value())
                .update();
        if (updated != 1) {
            throw new AllocationException("ALLOC_NOT_ACTIVE", "할당 %d는 이미 닫혔다".formatted(id.value()));
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void decrementAllocated(BalanceId balanceId, int qty) {
        jdbc.sql("UPDATE stock_balance SET allocated_qty = allocated_qty - :qty, updated_at = now() WHERE id = :id")
                .param("qty", qty)
                .param("id", balanceId.value())
                .update();
    }

    // ── 출고 소진. PostingService가 SHIPMENT 처리 중(잔액 잠금 이후) 호출한다 ────────────

    /**
     * 소진 대상 할당 id를 balance_id별로 묶어 수량 합을 돌려준다 (tst_post의 v_used와 같다).
     * stock_balance를 FOR UPDATE로 잠근 뒤 호출해야 안전하다 — 이 잔액 행을 건드리는 다른 트랜잭션
     * (해제·다른 출고)은 모두 같은 잔액 행을 먼저 잠가야 하므로(docs/04-write-path.md 잠금 규칙),
     * 우리가 잠근 동안은 이 SELECT가 그 시점의 안정된 값을 본다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<Long, Integer> consumedQtyByBalance(List<Long> allocationIds) {
        if (allocationIds.isEmpty()) {
            // PostingService가 이 잔액 id를 remove()로 소비하며 순회하므로 변경 가능한 맵이어야 한다
            // (Map.of()는 불변이라 remove() 호출 자체가 UnsupportedOperationException이 된다 — 검증에서 발견).
            return new LinkedHashMap<>();
        }
        record Row(long balanceId, int used) {
        }
        List<Row> rows = jdbc.sql("""
                SELECT balance_id, SUM(qty)::INT AS used FROM allocation
                WHERE id IN (:ids) AND status = 'ACTIVE'
                GROUP BY balance_id
                """)
                .param("ids", allocationIds)
                .query((rs, rowNum) -> new Row(rs.getLong("balance_id"), rs.getInt("used")))
                .list();

        Map<Long, Integer> result = new LinkedHashMap<>();
        for (Row row : rows) {
            result.put(row.balanceId(), row.used());
        }
        return result;
    }

    /** {@code WHERE id = ANY(ids) AND status = 'ACTIVE'}의 영향 행 수가 ids.size()와 같은지 확인한다 (tst_post ⑥). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markConsumed(List<Long> allocationIds, long txnId) {
        if (allocationIds.isEmpty()) {
            return;
        }
        int updated = jdbc.sql("""
                UPDATE allocation SET status = 'CONSUMED', consumed_txn_id = :txnId, closed_at = now()
                WHERE id IN (:ids) AND status = 'ACTIVE'
                """)
                .param("txnId", txnId)
                .param("ids", allocationIds)
                .update();
        if (updated != allocationIds.size()) {
            throw new PostingException("ALLOC_NOT_ACTIVE",
                    "소진 대상 %d건 중 %d건만 ACTIVE였다".formatted(allocationIds.size(), updated));
        }
    }
}
