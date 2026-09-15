package com.zerosum.inventory.web.issue;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zerosum.inventory.web.support.AbstractWebTest;
import com.zerosum.inventory.web.support.DbFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 종결한 이슈도 열어볼 수 있어야 한다.
 *
 * <p>{@code v_open_issue}는 OPEN·ACKED만 담는다. 그 뷰의 목적은 "봐야 할 이슈" 목록을 만드는 것이지
 * 사람이 열어볼 수 있는 범위를 정하는 것이 아니다. 상세 조회까지 그 뷰로 하면, 방금 종결한 사람이
 * 새로고침하는 순간 404가 나고 {@code resolved_by}·{@code resolution_note}·{@code resolved_txn_id} —
 * 누가 왜 닫았는지의 감사 기록 — 를 아무도 볼 수 없게 된다.
 *
 * <p>목록은 OPEN·ACKED만 담는 것이 맞다. 작업 목록이기 때문이다. 상세는 상태와 무관해야 한다.
 */
@AutoConfigureMockMvc
class ResolvedIssueVisibilityTest extends AbstractWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void seed() throws Exception {
        DbFixtures.resetDatabase(POSTGRES);
    }

    @Test
    void resolvedIssueIsStillViewable() throws Exception {
        long issueId = insertOpenIssue();

        mockMvc.perform(post("/api/issues/{id}/resolve", issueId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"원인을 코드로 고쳤다\"}")
                        .with(httpBasic("choi.dw", "zerosum")))
                .andExpect(status().is2xxSuccessful());

        mockMvc.perform(get("/api/issues/{id}", issueId).with(httpBasic("choi.dw", "zerosum")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issue.status").value("RESOLVED"));
    }

    @Test
    void resolvedIssueLeavesTheWorklist() throws Exception {
        long issueId = insertOpenIssue();

        mockMvc.perform(post("/api/issues/{id}/resolve", issueId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"종결\"}")
                        .with(httpBasic("choi.dw", "zerosum")))
                .andExpect(status().is2xxSuccessful());

        mockMvc.perform(get("/api/issues").param("warehouse", "ICN01")
                        .with(httpBasic("choi.dw", "zerosum")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.issueId == " + issueId + ")]").doesNotExist());
    }

    private long insertOpenIssue() {
        return jdbcClient.sql("""
                INSERT INTO inventory_issue (issue_type, severity, location_id, sku_id, lot_id, detail)
                SELECT 'CHAIN_BREAK', 'CRITICAL', l.id, s.id, lo.id, '{"ledgerEntryId": 1}'::JSONB
                FROM location l
                JOIN warehouse w ON w.id = l.warehouse_id AND w.code = 'ICN01'
                JOIN sku s ON s.code = 'SKU-200002'
                JOIN lot lo ON lo.sku_id = s.id AND lo.lot_no = 'L20260910-B'
                WHERE l.code = 'A-01-01-1'
                RETURNING id
                """).query(Long.class).single();
    }
}
