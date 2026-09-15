package com.zerosum.inventory.web.count;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.zerosum.inventory.domain.Posted;
import com.zerosum.inventory.domain.PostingOutcome;
import com.zerosum.inventory.posting.PostingGateway;
import com.zerosum.inventory.posting.PostingLineInput;
import com.zerosum.inventory.posting.PostingRequest;
import com.zerosum.inventory.posting.Preconditions;
import com.zerosum.inventory.web.support.AbstractWebTest;
import com.zerosum.inventory.web.support.DbFixtures;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 실사 세션 엔드포인트(시작·제출·정정 승인·중단). 정정 승인만 SUPERVISOR 전용이라는 점과 세션의 창고를
 * 서버가 읽어 대조한다는 점이 핵심이다.
 *
 * {@link com.zerosum.inventory.web.issue.IssueControllerTest}의 클래스 주석 참고(UserDetailsService가
 * 사용자당 인스턴스 하나를 재사용해, 같은 사용자의 두 번째 로그인이 항상 401이 된다).
 */
@AutoConfigureMockMvc
class CountControllerTest extends AbstractWebTest {

    private static final String PASSWORD = "zerosum";
    private static final String LOCATION = "A-01-01-1";
    private static final String SKU = "SKU-100001";
    private static final String LOT = "DEFAULT";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PostingGateway postingGateway;

    @BeforeEach
    void seed() throws Exception {
        DbFixtures.resetDatabase(POSTGRES);
    }

    @Test
    void countSubmitBeyondToleranceNeedsReview() throws Exception {
        receive("ICN01", LOCATION, SKU, LOT, 100);
        long sessionId = startCount("ICN01", LOCATION, "park.jh");

        mockMvc.perform(post("/api/counts/{id}/submit", sessionId).with(httpBasic("park.jh", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitJson(SKU, LOT, 50)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolutionTxnId").doesNotExist());

        assertThat(sessionStatus(sessionId)).isEqualTo("REVIEW");
    }

    @Test
    void operatorCannotResolveCount() throws Exception {
        receive("ICN01", LOCATION, SKU, LOT, 100);
        long sessionId = startCount("ICN01", LOCATION, "park.jh");
        submit(sessionId, SKU, LOT, 50, "park.jh"); // 오차 초과 → REVIEW

        mockMvc.perform(post("/api/counts/{id}/resolve", sessionId).with(httpBasic("park.jh", PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void supervisorCanResolveCount() throws Exception {
        receive("ICN01", LOCATION, SKU, LOT, 100);
        long sessionId = startCount("ICN01", LOCATION, "park.jh");
        submit(sessionId, SKU, LOT, 50, "park.jh"); // 오차 초과 → REVIEW

        mockMvc.perform(post("/api/counts/{id}/resolve", sessionId).with(httpBasic("choi.dw", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolutionTxnId").exists());

        assertThat(sessionStatus(sessionId)).isEqualTo("CONFIRMED");
    }

    @Test
    void countWithinToleranceConfirmsImmediately() throws Exception {
        receive("ICN01", LOCATION, SKU, LOT, 40);
        long sessionId = startCount("ICN01", LOCATION, "park.jh");

        mockMvc.perform(post("/api/counts/{id}/submit", sessionId).with(httpBasic("park.jh", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitJson(SKU, LOT, 40)))
                .andExpect(status().isOk());

        assertThat(sessionStatus(sessionId)).isEqualTo("CONFIRMED");
    }

    @Test
    void operatorCanAbandonCount() throws Exception {
        long sessionId = startCount("ICN01", LOCATION, "park.jh");

        mockMvc.perform(post("/api/counts/{id}/abandon", sessionId).with(httpBasic("park.jh", PASSWORD)))
                .andExpect(status().isOk());

        assertThat(sessionStatus(sessionId)).isEqualTo("ABANDONED");
    }

    // ── 픽스처 헬퍼 ──────────────────────────────────────────────────────────────

    private long startCount(String warehouseCode, String locationCode, String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/counts").with(httpBasic(username, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warehouseCode":"%s","locationCode":"%s"}
                                """.formatted(warehouseCode, locationCode)))
                .andExpect(status().isOk())
                .andReturn();
        Number sessionId = JsonPath.read(result.getResponse().getContentAsString(), "$.sessionId");
        return sessionId.longValue();
    }

    private void submit(long sessionId, String skuCode, String lotNo, int countedQty, String username)
            throws Exception {
        mockMvc.perform(post("/api/counts/{id}/submit", sessionId).with(httpBasic(username, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitJson(skuCode, lotNo, countedQty)))
                .andExpect(status().isOk());
    }

    private String submitJson(String skuCode, String lotNo, int countedQty) {
        return """
                {"lines":[{"skuCode":"%s","lotNo":"%s","countedQty":%d}]}
                """.formatted(skuCode, lotNo, countedQty);
    }

    private void receive(String warehouseCode, String locationCode, String skuCode, String lotNo, int qty) {
        String idemKey = "receipt:" + warehouseCode + ":" + locationCode + ":" + skuCode + ":" + qty;
        PostingRequest request = new PostingRequest(idemKey, "RECEIPT", "USER", "user:test",
                List.of(new PostingLineInput(warehouseCode, locationCode, skuCode, lotNo, qty),
                        new PostingLineInput(warehouseCode, "V-SUPPLIER", skuCode, lotNo, -qty)),
                "TEST", idemKey, null, null, Instant.now());
        PostingOutcome outcome = postingGateway.post(request, Preconditions.none());
        assertThat(outcome).isInstanceOf(Posted.class);
    }

    private String sessionStatus(long sessionId) {
        return jdbc.sql("SELECT status FROM count_session WHERE id = :id").param("id", sessionId)
                .query(String.class).single();
    }
}
