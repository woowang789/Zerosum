package com.zerosum.inventory.web.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 할당·할당 해제 엔드포인트. 역할(OPERATOR 이상)과 멱등 키 서버 파생(주문라인 → allocate:, 대상 id
 * 집합 → release:)이 핵심이다.
 *
 * {@link com.zerosum.inventory.web.issue.IssueControllerTest}의 클래스 주석 참고(UserDetailsService가
 * 사용자당 인스턴스 하나를 재사용해, 같은 사용자의 두 번째 로그인이 항상 401이 된다).
 */
@AutoConfigureMockMvc
class AllocationControllerTest extends AbstractWebTest {

    private static final String PASSWORD = "zerosum";

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
    void operatorCanAllocateAndRelease() throws Exception {
        receive("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", 50);

        MvcResult allocateResult = mockMvc.perform(post("/api/allocations").with(httpBasic("park.jh", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderLineRef":"ORDER-1","warehouseCode":"ICN01","skuCode":"SKU-100001",
                                 "qty":10,"allowInCount":false}
                                """))
                .andExpect(status().isOk())
                .andReturn();

        List<Number> raw = JsonPath.read(allocateResult.getResponse().getContentAsString(), "$.allocationIds");
        List<Long> allocationIds = raw.stream().map(Number::longValue).toList();
        assertThat(allocationIds).isNotEmpty();
        assertThat(allocatedStatus(allocationIds.get(0))).isEqualTo("ACTIVE");

        String releaseBody = "{\"allocationIds\":[" + allocationIds.stream().map(String::valueOf)
                .collect(Collectors.joining(",")) + "]}";
        mockMvc.perform(delete("/api/allocations").with(httpBasic("park.jh", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(releaseBody))
                .andExpect(status().isOk());

        assertThat(allocatedStatus(allocationIds.get(0))).isEqualTo("RELEASED");
    }

    @Test
    void viewerCannotAllocate() throws Exception {
        mockMvc.perform(post("/api/allocations").with(httpBasic("lee.sm", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderLineRef":"ORDER-2","warehouseCode":"YIT01","skuCode":"SKU-100001",
                                 "qty":1,"allowInCount":false}
                                """))
                .andExpect(status().isForbidden());
    }

    // ── 픽스처 헬퍼 ──────────────────────────────────────────────────────────────

    private void receive(String warehouseCode, String locationCode, String skuCode, String lotNo, int qty) {
        String idemKey = "receipt:" + warehouseCode + ":" + locationCode + ":" + skuCode + ":" + qty;
        PostingRequest request = new PostingRequest(idemKey, "RECEIPT", "USER", "user:test",
                List.of(new PostingLineInput(warehouseCode, locationCode, skuCode, lotNo, qty),
                        new PostingLineInput(warehouseCode, "V-SUPPLIER", skuCode, lotNo, -qty)),
                "TEST", idemKey, null, null, Instant.now());
        PostingOutcome outcome = postingGateway.post(request, Preconditions.none());
        assertThat(outcome).isInstanceOf(Posted.class);
    }

    private String allocatedStatus(long allocationId) {
        return jdbc.sql("SELECT status FROM allocation WHERE id = :id").param("id", allocationId)
                .query(String.class).single();
    }
}
