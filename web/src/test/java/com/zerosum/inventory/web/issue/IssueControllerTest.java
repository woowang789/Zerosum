package com.zerosum.inventory.web.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.zerosum.inventory.web.support.AbstractWebTest;
import com.zerosum.inventory.web.support.DbFixtures;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 이슈 처리 엔드포인트(목록·상세·인지·종결). 역할(인지는 OPERATOR 이상, 종결은 SUPERVISOR)과 창고 범위,
 * 인지자·종결자의 출처(SecurityContext)를 서버가 강제하는지가 핵심이다.
 *
 * {@code UserDetailsService}가 사용자별 {@code WarehouseUser} 인스턴스를 하나만 만들어 재사용하는데,
 * Spring Security의 {@code ProviderManager}는 인증에 성공할 때마다 그 principal의 자격 증명을 지운다
 * ({@code eraseCredentialsAfterAuthentication}, 기본값 true) — 같은 사용자로 두 번째 로그인하면 항상
 * 401이 된다(SecurityConfig는 수정 대상이 아니므로 여기서 컨텍스트를 새로 해 피해 간다).
 */
@AutoConfigureMockMvc
class IssueControllerTest extends AbstractWebTest {

    private static final String PASSWORD = "zerosum";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void seed() throws Exception {
        DbFixtures.resetDatabase(POSTGRES);
    }

    @Test
    void operatorCannotResolveIssue() throws Exception {
        long id = insertOpenIssue("ICN01", "A-01-01-2");

        mockMvc.perform(post("/api/issues/{id}/resolve", id).with(httpBasic("park.jh", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"정리\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void viewerCannotAcknowledge() throws Exception {
        long id = insertOpenIssue("YIT01", "A-01-01-2");

        mockMvc.perform(post("/api/issues/{id}/ack", id).with(httpBasic("lee.sm", PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void otherWarehouseIssueDetailIsForbidden() throws Exception {
        long id = insertOpenIssue("ICN01", "A-01-01-2");

        mockMvc.perform(get("/api/issues/{id}", id).with(httpBasic("lee.sm", PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void operatorCanAcknowledge() throws Exception {
        long id = insertOpenIssue("ICN01", "A-01-01-2");

        // 본문에 다른 행위자를 넣어도 무시된다 — 인지자는 인증 주체(park.jh)여야 한다.
        mockMvc.perform(post("/api/issues/{id}/ack", id).with(httpBasic("park.jh", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"who\":\"attacker\"}"))
                .andExpect(status().isOk());

        assertThat(issueStatus(id)).isEqualTo("ACKED");
        assertThat(ackedBy(id)).isEqualTo("park.jh");
    }

    @Test
    void supervisorCanResolveIssue() throws Exception {
        long id = insertOpenIssue("ICN01", "A-01-01-2");

        mockMvc.perform(post("/api/issues/{id}/resolve", id).with(httpBasic("choi.dw", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"코드 수정으로 종결\"}"))
                .andExpect(status().isOk());

        assertThat(issueStatus(id)).isEqualTo("RESOLVED");
        assertThat(resolvedBy(id)).isEqualTo("choi.dw");
    }

    @Test
    void issueDetailIncludesContext() throws Exception {
        long id = insertOpenIssue("ICN01", "A-01-01-2");

        mockMvc.perform(get("/api/issues/{id}", id).with(httpBasic("choi.dw", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issue.issueId").value(id))
                .andExpect(jsonPath("$.issue.warehouseCode").value("ICN01"))
                .andExpect(jsonPath("$.ledger").isArray())
                .andExpect(jsonPath("$.countHistory").isArray());
    }

    @Test
    void listOpenIssuesScopedToWarehouse() throws Exception {
        insertOpenIssue("ICN01", "A-01-01-2");
        insertOpenIssue("YIT01", "A-01-01-2");

        mockMvc.perform(get("/api/issues").param("warehouse", "ICN01").with(httpBasic("choi.dw", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].warehouseCode").value("ICN01"));
    }

    @Test
    void issueNotFoundBecomesStructuredError() throws Exception {
        mockMvc.perform(post("/api/issues/{id}/ack", 999_999L).with(httpBasic("park.jh", PASSWORD)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ISSUE_NOT_FOUND"));
    }

    /**
     * 종결 근거 거래는 이 이슈의 창고를 건드린 거래여야 한다. 이슈 자체는 AccessGuard.requireWarehouse가
     * 대조하지만 {@code resolvedTxnId}는 요청 본문에서 오는 검증되지 않은 값이라, 막지 않으면 남의 창고
     * 거래가 이 이슈를 "고친 거래"로 감사 기록에 남는다 — 담당자는 창고 경계 때문에 그 거래를 열어볼
     * 수조차 없다. choi.dw는 두 창고를 다 보므로 YIT01 거래를 만들 수 있지만, 이슈는 ICN01의 것이다.
     */
    @Test
    void resolveWithOtherWarehouseTxnIsRejected() throws Exception {
        long issueId = insertOpenIssue("ICN01", "A-01-01-2");
        long otherWarehouseTxnId = receive("PO-YIT-1", "YIT01", "A-01-01-1", 10);

        mockMvc.perform(post("/api/issues/{id}/resolve", issueId).with(httpBasic("choi.dw", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resolveJson("남의 창고 거래로 종결", otherWarehouseTxnId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESOLVED_TXN_NOT_IN_WAREHOUSE"));

        assertThat(issueStatus(issueId)).isEqualTo("OPEN");
        assertThat(resolvedTxnId(issueId)).isEmpty();
    }

    /**
     * 존재하지 않는 거래 id도 같은 400이다. 통과시키면 종결 UPDATE가 resolved_txn_id 외래키(V1:216)를
     * 위반해 DataIntegrityViolationException이 되고, 두 예외 어드바이스 어디에도 그 핸들러가 없어 500이
     * 나갔다 — 클라이언트의 잘못된 입력이 서버 오류로 보고되던 자리다.
     */
    @Test
    void resolveWithUnknownTxnIsBadRequestNotServerError() throws Exception {
        long issueId = insertOpenIssue("ICN01", "A-01-01-2");

        mockMvc.perform(post("/api/issues/{id}/resolve", issueId).with(httpBasic("choi.dw", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resolveJson("없는 거래로 종결", 999_999L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESOLVED_TXN_NOT_IN_WAREHOUSE"));

        assertThat(issueStatus(issueId)).isEqualTo("OPEN");
        assertThat(resolvedTxnId(issueId)).isEmpty();
    }

    /** 같은 창고를 건드린 거래는 그대로 종결 근거가 된다 — 위 두 검사가 정상 경로까지 막지 않는지 본다. */
    @Test
    void resolveWithSameWarehouseTxnRecordsIt() throws Exception {
        long issueId = insertOpenIssue("ICN01", "A-01-01-2");
        long txnId = receive("PO-ICN-1", "ICN01", "A-01-01-2", 10);

        mockMvc.perform(post("/api/issues/{id}/resolve", issueId).with(httpBasic("choi.dw", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resolveJson("조정 거래로 바로잡음", txnId)))
                .andExpect(status().isOk());

        assertThat(issueStatus(issueId)).isEqualTo("RESOLVED");
        assertThat(resolvedTxnId(issueId)).contains(txnId);
    }

    // ── 픽스처 헬퍼 ──────────────────────────────────────────────────────────────

    /** 거래 하나를 실제 쓰기 경로로 만든다 — 원장 줄이 있어야 txnTouchesWarehouse가 볼 것이 생긴다. */
    private long receive(String poLineRef, String warehouseCode, String locationCode, int qty) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/receipts").with(httpBasic("choi.dw", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"poLineRef":"%s","receiptSeq":1,"warehouseCode":"%s","locationCode":"%s",
                                 "skuCode":"SKU-100001","lotNo":"DEFAULT","qty":%d}
                                """.formatted(poLineRef, warehouseCode, locationCode, qty)))
                .andExpect(status().isOk())
                .andReturn();
        Number txnId = JsonPath.read(result.getResponse().getContentAsString(), "$.txnId");
        return txnId.longValue();
    }

    private String resolveJson(String note, long resolvedTxnId) {
        return "{\"note\":\"%s\",\"resolvedTxnId\":%d}".formatted(note, resolvedTxnId);
    }

    private long insertOpenIssue(String warehouseCode, String locationCode) {
        return jdbc.sql("""
                INSERT INTO inventory_issue (issue_type, severity, location_id, detail)
                SELECT 'TEST_ISSUE', 'LOW', l.id, '{}'::JSONB
                FROM location l JOIN warehouse w ON w.id = l.warehouse_id
                WHERE w.code = :wh AND l.code = :loc
                RETURNING id
                """)
                .param("wh", warehouseCode)
                .param("loc", locationCode)
                .query(Long.class)
                .single();
    }

    private String issueStatus(long id) {
        return jdbc.sql("SELECT status FROM inventory_issue WHERE id = :id").param("id", id)
                .query(String.class).single();
    }

    private String ackedBy(long id) {
        return jdbc.sql("SELECT acked_by FROM inventory_issue WHERE id = :id").param("id", id)
                .query(String.class).single();
    }

    private String resolvedBy(long id) {
        return jdbc.sql("SELECT resolved_by FROM inventory_issue WHERE id = :id").param("id", id)
                .query(String.class).single();
    }

    /** 종결 근거 거래. 아직 종결 전이거나 거래 없이 종결했으면 컬럼이 널이므로 빈 Optional이다. */
    private Optional<Long> resolvedTxnId(long id) {
        return jdbc.sql("SELECT resolved_txn_id FROM inventory_issue WHERE id = :id").param("id", id)
                .query(Long.class).optional();
    }
}
