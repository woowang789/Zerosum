package com.zerosum.inventory.web.proposal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zerosum.inventory.domain.Posted;
import com.zerosum.inventory.domain.PostingOutcome;
import com.zerosum.inventory.posting.PostingGateway;
import com.zerosum.inventory.posting.PostingLineInput;
import com.zerosum.inventory.posting.PostingRequest;
import com.zerosum.inventory.posting.Preconditions;
import com.zerosum.inventory.proposal.CreateProposalOutcome;
import com.zerosum.inventory.proposal.CreateProposalRequest;
import com.zerosum.inventory.proposal.ProposalCreated;
import com.zerosum.inventory.proposal.ProposalCreationService;
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

/**
 * 제안 검토 엔드포인트(상세·근거 대조·승인·거부). 창고 범위와 행위자 출처(SecurityContext)를 서버가
 * 강제하는지가 핵심이다 — WebSecurityTest의 경고(모든 요청이 403이면 거부 테스트만으로는 버그를
 * 못 잡는다)를 따라 긍정 경로도 함께 둔다.
 *
 * {@link com.zerosum.inventory.web.issue.IssueControllerTest}의 클래스 주석 참고(SecurityConfig의
 * UserDetailsService가 사용자당 인스턴스 하나를 재사용해, 같은 사용자의 두 번째 로그인이 항상 401이 된다).
 */
@AutoConfigureMockMvc
class ProposalControllerTest extends AbstractWebTest {

    private static final String PASSWORD = "zerosum";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ProposalCreationService proposalCreationService;

    @Autowired
    private PostingGateway postingGateway;

    @BeforeEach
    void seed() throws Exception {
        DbFixtures.resetDatabase(POSTGRES);
    }

    @Test
    void viewerCannotApprove() throws Exception {
        long id = createProposal(movePayload("YIT01", "A-01-01-2", "A-01-02-1", 5), "YIT01");

        mockMvc.perform(post("/api/proposals/{id}/approve", id).with(httpBasic("lee.sm", PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void otherWarehouseProposalDetailIsForbidden() throws Exception {
        long id = createProposal(movePayload("ICN01", "A-01-01-2", "A-01-02-1", 5), "ICN01");

        mockMvc.perform(get("/api/proposals/{id}", id).with(httpBasic("lee.sm", PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void coreExceptionBecomesStructuredError() throws Exception {
        mockMvc.perform(post("/api/proposals/{id}/approve", 999_999L).with(httpBasic("choi.dw", PASSWORD)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROPOSAL_NOT_FOUND"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void approverComesFromAuthenticationNotBody() throws Exception {
        receiveColdBrew("ICN01", "A-01-01-2", 100);
        long id = createProposal(movePayload("ICN01", "A-01-01-2", "A-01-02-1", 20), "ICN01");

        mockMvc.perform(post("/api/proposals/{id}/approve", id).with(httpBasic("choi.dw", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approver\":\"attacker\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposalId").value(id))
                .andExpect(jsonPath("$.txnId").exists());

        assertThat(decidedBy(id)).as("본문의 attacker가 아니라 인증 주체가 결정자다").isEqualTo("choi.dw");
        assertThat(proposalStatus(id)).isEqualTo("EXECUTED");
    }

    @Test
    void supervisorCanReject() throws Exception {
        long id = createProposal(movePayload("ICN01", "A-01-01-2", "A-01-02-1", 5), "ICN01");

        mockMvc.perform(post("/api/proposals/{id}/reject", id).with(httpBasic("choi.dw", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"수량이 실측과 다르다\"}"))
                .andExpect(status().isOk());

        assertThat(proposalStatus(id)).isEqualTo("REJECTED");
        assertThat(decidedBy(id)).isEqualTo("choi.dw");
        assertThat(decisionNote(id)).isEqualTo("수량이 실측과 다르다");
    }

    @Test
    void viewerCanViewProposalDetail() throws Exception {
        long id = createProposal(movePayload("YIT01", "A-01-01-2", "A-01-02-1", 5), "YIT01");

        mockMvc.perform(get("/api/proposals/{id}", id).with(httpBasic("lee.sm", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposal.id").value(id))
                .andExpect(jsonPath("$.proposal.proposalType").value("MOVE"))
                .andExpect(jsonPath("$.basisReview.balance").isArray())
                .andExpect(jsonPath("$.basisReview.warehouseSku").isArray());
    }

    // ── 픽스처 헬퍼 ──────────────────────────────────────────────────────────────

    private long createProposal(String payloadJson, String allowedWarehouseCode) {
        CreateProposalRequest request = new CreateProposalRequest("MOVE", payloadJson, "테스트 사유", "agent:test", null,
                List.of());
        CreateProposalOutcome outcome = proposalCreationService.create(request, allowedWarehouseCode);
        return ((ProposalCreated) outcome).proposalId();
    }

    private String movePayload(String warehouseCode, String fromLoc, String toLoc, int qty) {
        return """
                {"txnType":"MOVE","entries":[
                   {"wh":"%s","loc":"%s","sku":"SKU-200002","lot":"L20260910-B","qty":-%d},
                   {"wh":"%s","loc":"%s","sku":"SKU-200002","lot":"L20260910-B","qty":%d}]}
                """.formatted(warehouseCode, fromLoc, qty, warehouseCode, toLoc, qty);
    }

    private void receiveColdBrew(String warehouseCode, String locationCode, int qty) {
        String idemKey = "receipt:" + warehouseCode + ":" + locationCode + ":" + qty;
        PostingRequest request = new PostingRequest(idemKey, "RECEIPT", "USER", "user:test",
                List.of(new PostingLineInput(warehouseCode, "V-SUPPLIER", "SKU-200002", "L20260910-B", -qty),
                        new PostingLineInput(warehouseCode, locationCode, "SKU-200002", "L20260910-B", qty)),
                "TEST", idemKey, null, null, Instant.now());
        PostingOutcome outcome = postingGateway.post(request, Preconditions.none());
        assertThat(outcome).isInstanceOf(Posted.class);
    }

    private String proposalStatus(long id) {
        return jdbc.sql("SELECT status FROM action_proposal WHERE id = :id").param("id", id)
                .query(String.class).single();
    }

    private String decidedBy(long id) {
        return jdbc.sql("SELECT decided_by FROM action_proposal WHERE id = :id").param("id", id)
                .query(String.class).single();
    }

    private String decisionNote(long id) {
        return jdbc.sql("SELECT decision_note FROM action_proposal WHERE id = :id").param("id", id)
                .query(String.class).single();
    }
}
