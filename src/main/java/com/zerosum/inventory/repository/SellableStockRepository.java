package com.zerosum.inventory.repository;

import com.zerosum.inventory.domain.SellableStock;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code v_sellable_stock} 조회 (docs/05-count-session.md 판매 가능 수량). 읽기 전용 집계 뷰라 쓰기 경로의
 * 잠금 규칙이 적용되지 않으므로, 다른 리포지토리와 달리 {@code @Transactional(MANDATORY)}를 요구하지 않는다 —
 * 단일 SELECT라 호출자가 트랜잭션을 열어둘 필요가 없다.
 */
@Repository
public class SellableStockRepository {

    private final JdbcClient jdbc;

    SellableStockRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 해당 창고·SKU 조합의 물리 STORAGE 재고가 전혀 없으면(뷰의 GROUP BY 행 자체가 없으면) 둘 다 0으로 본다. */
    public SellableStock find(long warehouseId, long skuId) {
        return jdbc.sql("""
                SELECT sellable_qty, in_count_qty FROM v_sellable_stock WHERE warehouse_id = :wh AND sku_id = :sku
                """)
                .param("wh", warehouseId)
                .param("sku", skuId)
                .query((rs, rowNum) -> new SellableStock(rs.getInt("sellable_qty"), rs.getInt("in_count_qty")))
                .optional()
                .orElse(new SellableStock(0, 0));
    }
}
