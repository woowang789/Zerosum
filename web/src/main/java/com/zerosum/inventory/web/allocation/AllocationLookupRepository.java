package com.zerosum.inventory.web.allocation;

import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 할당 해제({@code DELETE /api/allocations})의 창고 범위 검사용. {@code allocation}은 자기 창고 코드를
 * 갖지 않으므로 {@code stock_balance}를 거쳐 창고를 읽는다 ({@link com.zerosum.inventory.web.issue.IssueRepository}와
 * 같은 원칙 — app_rw(primary)로 직접 읽고 ai_ro는 쓰지 않는다).
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
}
