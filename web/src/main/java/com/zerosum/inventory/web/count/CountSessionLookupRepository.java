package com.zerosum.inventory.web.count;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code {id}}만 받는 실사 엔드포인트(제출·정정·중단)의 창고 범위 검사용. {@code count_session}은 자기
 * 창고 코드를 갖지 않으므로 로케이션을 거쳐 읽는다 ({@link com.zerosum.inventory.web.issue.IssueRepository}·
 * {@link com.zerosum.inventory.web.allocation.AllocationLookupRepository}와 같은 원칙 — app_rw(primary)).
 *
 * <p>이름이 코어의 {@link com.zerosum.inventory.repository.CountSessionRepository}(쓰기 경로 잠금 전용)와
 * 비슷하지만 패키지가 다른 별개 타입이다 — 이 리포지토리는 창고 코드 하나만 읽는 조회 전용이라 재사용하지
 * 않았다(그 리포지토리는 :core 패키지 전용이라 :web에서 직접 쓸 수도 없다).
 */
@Repository
class CountSessionLookupRepository {

    private final JdbcClient jdbc;

    CountSessionLookupRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 세션이 없으면 EmptyResultDataAccessException — ApiExceptionHandler가 이미 404로 매핑한다. */
    String warehouseCodeOf(long sessionId) {
        return jdbc.sql("""
                SELECT w.code
                FROM count_session cs
                JOIN location loc ON loc.id = cs.location_id
                JOIN warehouse w ON w.id = loc.warehouse_id
                WHERE cs.id = :id
                """)
                .param("id", sessionId)
                .query(String.class)
                .single();
    }
}
