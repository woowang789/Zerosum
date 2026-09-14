package com.zerosum.inventory.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.zerosum.inventory.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 정합 검증 배치 ⑤(실사 표시) — db/usecases/F-reconciliation.sql UC-F06과 같은 시나리오를
 * Java 배치(ReconciliationService)로 재현한다.
 */
class ReconciliationCountFlagTest extends AbstractIntegrationTest {

    @Autowired
    private ReconciliationService reconciliationService;

    @Test
    void countFlagMismatchIsDetectedWithoutAutoCorrection() {
        long danglingSessionId = danglingCountFlagOn("A-01-02-1");

        assertThat(reconciliationService.runOnce()).isEqualTo(1);
        assertThat(countIssues("COUNT_FLAG_MISMATCH")).isEqualTo(1);

        // 자동 보정하지 않는다 — 잘못된 표시가 그대로 남는다
        assertThat(countSessionIdOf("ICN01", "A-01-02-1")).isEqualTo(danglingSessionId);

        clearFlag("A-01-02-1"); // 원복
    }

    @Test
    void repeatedRunsDoNotDuplicateTheSameOpenIssue() {
        danglingCountFlagOn("A-01-02-1");

        assertThat(reconciliationService.runOnce()).as("첫 실행에서 1건 기록").isEqualTo(1);
        assertThat(reconciliationService.runOnce()).as("두 번째 실행은 이미 열려 있어 0건").isZero();
        assertThat(countIssues("COUNT_FLAG_MISMATCH")).isEqualTo(1);

        clearFlag("A-01-02-1"); // 원복
    }

    /** UC-F06a와 동일: 세션 없이(ABANDONED) 로케이션 표시만 남은 상태를 만든다. */
    private long danglingCountFlagOn(String locationCode) {
        long sessionId = jdbcClient.sql("""
                INSERT INTO count_session (location_id, started_by)
                SELECT id, 'user:test' FROM location
                WHERE code = :loc AND warehouse_id = (SELECT id FROM warehouse WHERE code = 'ICN01')
                RETURNING id
                """)
                .param("loc", locationCode)
                .query(Long.class)
                .single();
        // 코드가 창고마다 하나씩 있어(db/03-seed.sql) warehouse_id로 범위를 좁혀야 한다 — 그렇지 않으면
        // 다른 창고의 같은 코드 로케이션까지 걸려 세션이 가리키지 않는 로케이션에 표시가 붙어
        // 복합 외래키(location_count_session_id_id_fkey)를 위반한다.
        jdbcClient.sql("""
                UPDATE location SET count_session_id = :sid
                WHERE code = :loc AND warehouse_id = (SELECT id FROM warehouse WHERE code = 'ICN01')
                """)
                .param("sid", sessionId)
                .param("loc", locationCode)
                .update();
        jdbcClient.sql("UPDATE count_session SET status = 'ABANDONED', closed_by = 'user:test', closed_at = now() WHERE id = :sid")
                .param("sid", sessionId)
                .update();
        return sessionId;
    }

    private void clearFlag(String locationCode) {
        jdbcClient.sql("""
                UPDATE location SET count_session_id = NULL
                WHERE code = :loc AND warehouse_id = (SELECT id FROM warehouse WHERE code = 'ICN01')
                """)
                .param("loc", locationCode)
                .update();
    }

    private int countIssues(String issueType) {
        return jdbcClient.sql("SELECT count(*) FROM inventory_issue WHERE issue_type = :type")
                .param("type", issueType)
                .query(Integer.class)
                .single();
    }
}
