package com.zerosum.inventory.web.allocation;

import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 할당 해제({@code DELETE /api/allocations})의 창고 범위 검사와, 할당 응답에 실을 잔액 행 조회용.
 * {@code allocation}은 자기 창고·로케이션·로트 코드를 갖지 않으므로 {@code stock_balance}를 거쳐 읽는다
 * ({@link com.zerosum.inventory.web.issue.IssueRepository}와 같은 원칙 — app_rw(primary)로 직접 읽고
 * ai_ro는 쓰지 않는다).
 */
@Repository
class AllocationLookupRepository {

    private final JdbcClient jdbc;

    AllocationLookupRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 주어진 할당 id들이 걸쳐 있는 창고 코드 전부(중복 제거). 존재하지 않는 id는 조용히 빠진다. */
    Set<String> warehouseCodesOf(List<Long> allocationIds) {
        if (allocationIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbc.sql("""
                SELECT DISTINCT w.code
                FROM allocation a
                JOIN stock_balance b ON b.id = a.balance_id
                JOIN warehouse w ON w.id = b.warehouse_id
                WHERE a.id IN (:ids)
                """)
                .param("ids", allocationIds)
                .query(String.class)
                .list());
    }

    record AllocationLine(long allocationId, String locationCode, String skuCode, String lotNo, int qty) {
    }

    /**
     * 주어진 할당 id가 예약한 잔액 행(로케이션·SKU·로트)을 유통기한 오름차순(FEFO 순서)으로 돌려준다.
     * 출고 화면이 FEFO를 재구현하지 않고 서버가 고른 결과를 그대로 받아 줄을 채울 수 있게 한다.
     */
    List<AllocationLine> linesOf(List<Long> allocationIds) {
        if (allocationIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT a.id AS allocation_id, l.code AS location_code, s.code AS sku_code,
                       lo.lot_no AS lot_no, a.qty AS qty
                FROM allocation a
                JOIN stock_balance b ON b.id = a.balance_id
                JOIN location l ON l.id = b.location_id
                JOIN sku s ON s.id = b.sku_id
                JOIN lot lo ON lo.id = b.lot_id
                WHERE a.id IN (:ids)
                ORDER BY lo.expiry_date ASC NULLS LAST, a.id
                """)
                .param("ids", allocationIds)
                .query((rs, rowNum) -> new AllocationLine(rs.getLong("allocation_id"), rs.getString("location_code"),
                        rs.getString("sku_code"), rs.getString("lot_no"), rs.getInt("qty")))
                .list();
    }
}
