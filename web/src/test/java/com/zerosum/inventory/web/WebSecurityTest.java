package com.zerosum.inventory.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zerosum.inventory.web.support.AbstractWebTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 인증과 창고 범위가 서버에서 강제되는지 본다. 창고 권한을 요청이 정하게 두면 4단계에서 고친
 * 권한 누수와 같은 구멍이 다시 생긴다 — location은 UNIQUE(warehouse_id, code)라 로케이션 코드만으로는
 * 창고를 가릴 수 없고, 시드 데이터도 같은 코드를 두 창고에 갖고 있다.
 */
@AutoConfigureMockMvc
class WebSecurityTest extends AbstractWebTest {

    private static final String PASSWORD = "zerosum";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/proposals").param("warehouse", "ICN01"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void badCredentialsRejected() throws Exception {
        mockMvc.perform(get("/api/proposals").param("warehouse", "ICN01")
                        .with(httpBasic("choi.dw", "wrong")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedUserSeesOwnWarehouse() throws Exception {
        mockMvc.perform(get("/api/proposals").param("warehouse", "ICN01")
                        .with(httpBasic("choi.dw", PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    void otherWarehouseIsForbidden() throws Exception {
        mockMvc.perform(get("/api/proposals").param("warehouse", "ICN01")
                        .with(httpBasic("lee.sm", PASSWORD)))
                .andExpect(status().isForbidden());
    }
}
