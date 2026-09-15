package com.zerosum.inventory.web.stock;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 재고·원장 조회. 전 역할 허용(역할 검사 없음)과 창고 범위만이 핵심이다.
 *
 * {@link com.zerosum.inventory.web.issue.IssueControllerTest}의 클래스 주석 참고(UserDetailsService가
 * 사용자당 인스턴스 하나를 재사용해, 같은 사용자의 두 번째 로그인이 항상 401이 된다).
 */
@AutoConfigureMockMvc
class StockControllerTest extends AbstractWebTest {

    private static final String PASSWORD = "zerosum";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PostingGateway postingGateway;

    @BeforeEach
    void seed() throws Exception {
        DbFixtures.resetDatabase(POSTGRES);
    }

    @Test
    void stockScopedToWarehouseAndVisibleToAnyRole() throws Exception {
        receive("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", 15);

        // lee.sm은 VIEWER지만 조회는 전 역할 허용이다 — 다만 접근 가능한 창고는 YIT01뿐이므로 그쪽으로 확인한다.
        receive("YIT01", "A-01-01-1", "SKU-100001", "DEFAULT", 7);
        mockMvc.perform(get("/api/stock").param("warehouse", "YIT01").param("sku", "SKU-100001")
                        .with(httpBasic("lee.sm", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].warehouseCode").value("YIT01"))
                .andExpect(jsonPath("$[0].onHandQty").value(7));

        // ICN01은 park.jh(OPERATOR)로 확인 — 다른 창고(YIT01) 수량이 섞여 나오지 않는다.
        mockMvc.perform(get("/api/stock").param("warehouse", "ICN01").param("sku", "SKU-100001")
                        .with(httpBasic("park.jh", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].onHandQty").value(15));
    }

    @Test
    void ledgerListsBothLinesOfAReceipt() throws Exception {
        receive("ICN01", "A-01-01-1", "SKU-100001", "DEFAULT", 15);

        mockMvc.perform(get("/api/ledger").param("warehouse", "ICN01").param("sku", "SKU-100001")
                        .with(httpBasic("choi.dw", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    // ── 픽스처 헬퍼 ──────────────────────────────────────────────────────────────

    private void receive(String warehouseCode, String locationCode, String skuCode, String lotNo, int qty) {
        String idemKey = "receipt:" + warehouseCode + ":" + locationCode + ":" + skuCode + ":" + qty;
        PostingRequest request = new PostingRequest(idemKey, "RECEIPT", "USER", "user:test",
                List.of(new PostingLineInput(warehouseCode, locationCode, skuCode, lotNo, qty),
                        new PostingLineInput(warehouseCode, "V-SUPPLIER", skuCode, lotNo, -qty)),
                "TEST", idemKey, null, null, Instant.now());
        PostingOutcome outcome = postingGateway.post(request, Preconditions.none());
        Assertions.assertThat(outcome).isInstanceOf(Posted.class);
    }
}
