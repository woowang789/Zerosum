package com.zerosum.inventory.web.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zerosum.inventory.web.support.AbstractWebTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * /api/me — 인증 주체의 역할·창고를 내려주는지 본다. WebSecurityTest의 경고를 따라 인증 없이 401이
 * 되는지도 함께 확인한다.
 */
@AutoConfigureMockMvc
class MeControllerTest extends AbstractWebTest {

    private static final String PASSWORD = "zerosum";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void supervisorSeesBothWarehousesAndRole() throws Exception {
        mockMvc.perform(get("/api/me").with(httpBasic("choi.dw", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("choi.dw"))
                .andExpect(jsonPath("$.roles").value(org.hamcrest.Matchers.contains("SUPERVISOR")))
                .andExpect(jsonPath("$.warehouses")
                        .value(org.hamcrest.Matchers.contains("ICN01", "YIT01")));
    }

    @Test
    void operatorSeesOwnWarehouseOnly() throws Exception {
        mockMvc.perform(get("/api/me").with(httpBasic("park.jh", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles").value(org.hamcrest.Matchers.contains("OPERATOR")))
                .andExpect(jsonPath("$.warehouses").value(org.hamcrest.Matchers.contains("ICN01")));
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }
}
