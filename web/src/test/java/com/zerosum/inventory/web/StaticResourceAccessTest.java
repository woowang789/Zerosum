package com.zerosum.inventory.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zerosum.inventory.web.support.AbstractWebTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 익명 브라우저가 로그인 화면을 받아올 수 있어야 한다.
 *
 * <p>서버는 {@code WWW-Authenticate}를 보내지 않는다 — 브라우저 기본 로그인 창 대신 프론트엔드가
 * 자기 로그인 폼을 그리게 하려는 것이다. 그런데 그 폼을 담은 정적 리소스에도 인증을 걸면, 로그인하려면
 * 먼저 로그인해야 하는 닭-달걀이 된다. 사용자는 빈 401만 받는다.
 *
 * <p>인증이 필요한 것은 {@code /api/**} 뿐이다. 정적 자산은 비밀이 아니다.
 */
@AutoConfigureMockMvc
class StaticResourceAccessTest extends AbstractWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void anonymousCanLoadTheLoginPage() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().isOk());
    }

    @Test
    void anonymousCanLoadStaticAssets() throws Exception {
        mockMvc.perform(get("/assets/app.js")).andExpect(status().isNotFound());
    }

    @Test
    void apiStillRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/stock").param("warehouse", "ICN01"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void clientSideRouteFallsBackToTheApp() throws Exception {
        mockMvc.perform(get("/stock")).andExpect(status().isOk());
    }
}
