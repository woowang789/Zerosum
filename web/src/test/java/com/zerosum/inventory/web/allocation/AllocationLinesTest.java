package com.zerosum.inventory.web.allocation;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
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
import org.springframework.test.web.servlet.MockMvc;

/**
 * 할당 응답은 어느 잔액 행을 잡았는지 알려줘야 한다.
 *
 * <p>로트는 사람이 고르지 않는다 — FEFO가 서버에서 고른다. 그런데 응답이 할당 id만 주면, 출고 화면은
 * 그 할당이 어느 로케이션·로트를 예약했는지 알 수 없어 물리 줄을 추측해야 한다. 추측이 틀리면
 * 2단계에서 막아 둔 ORPHAN_CONSUME(할당은 소진되는데 그 잔액 행이 출고 줄에 없는 상태)으로 거절된다.
 *
 * <p>화면이 FEFO를 다시 구현하는 것은 답이 아니다 — 서버와 갈라지는 순간 조용히 틀린다. 서버가 고른
 * 결과를 그대로 돌려주면 된다.
 */
@AutoConfigureMockMvc
class AllocationLinesTest extends AbstractWebTest {

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void seed() throws Exception {
        DbFixtures.resetDatabase(POSTGRES);
    }

    @Test
    void allocationResponseNamesTheReservedBalanceRows() throws Exception {
        mockMvc.perform(post("/api/receipts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"poLineRef":"PO-ALLOC-1","receiptSeq":1,"warehouseCode":"ICN01",
                                 "locationCode":"A-01-01-1","skuCode":"SKU-200002",
                                 "lotNo":"L20260910-B","qty":40}
                                """)
                        .with(httpBasic("park.jh", "zerosum")))
                .andExpect(status().is2xxSuccessful());

        mockMvc.perform(post("/api/allocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderLineRef":"ORD-ALLOC-1","warehouseCode":"ICN01",
                                 "skuCode":"SKU-200002","qty":10,"allowInCount":false}
                                """)
                        .with(httpBasic("park.jh", "zerosum")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines").isArray())
                .andExpect(jsonPath("$.lines[0].locationCode").value("A-01-01-1"))
                .andExpect(jsonPath("$.lines[0].skuCode").value("SKU-200002"))
                .andExpect(jsonPath("$.lines[0].lotNo").value("L20260910-B"))
                .andExpect(jsonPath("$.lines[0].qty").value(10))
                .andExpect(jsonPath("$.lines[0].allocationId").isNumber());
    }
}
