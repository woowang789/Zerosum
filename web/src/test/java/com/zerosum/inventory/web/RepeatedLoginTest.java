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
 * 같은 사용자가 두 번 로그인할 수 있어야 한다.
 *
 * <p>Spring Security의 ProviderManager는 인증에 성공하면 principal이 CredentialsContainer면
 * eraseCredentials()를 불러 비밀번호를 지운다. UserDetailsService가 사용자당 인스턴스를 하나만
 * 만들어 재사용하면 그 인스턴스의 비밀번호가 지워져, 두 번째 로그인부터 영영 401이 된다.
 */
@AutoConfigureMockMvc
class RepeatedLoginTest extends AbstractWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void sameUserCanAuthenticateRepeatedly() throws Exception {
        for (int attempt = 1; attempt <= 3; attempt++) {
            mockMvc.perform(get("/api/proposals").param("warehouse", "ICN01")
                            .with(httpBasic("choi.dw", "zerosum")))
                    .andExpect(status().isOk());
        }
    }
}
