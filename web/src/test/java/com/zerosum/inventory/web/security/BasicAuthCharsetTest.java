package com.zerosum.inventory.web.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zerosum.inventory.web.support.AbstractWebTest;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 비ASCII 비밀번호가 HTTP Basic으로 통하는가 — 서버가 자격 증명 옥텟을 어느 문자 인코딩으로 읽는지 본다.
 *
 * <p>RFC 7617에서 {@code user-pass}는 문자열이 아니라 <b>옥텟 열</b>이고, 그 옥텟을 만드는 인코딩은
 * 규격이 정하지 않는다 — 서버가 정하고 401 응답의 {@code charset} 파라미터로 알릴 수 있으며, 그
 * 파라미터에 허용된 값은 UTF-8 하나뿐이다. 즉 "UTF-8로 보내면 된다"가 저절로 성립하는 것이 아니라,
 * <b>서버가 UTF-8로 읽을 때만</b> 성립한다.
 *
 * <p>프론트엔드(frontend/src/api/client.ts)는 자격 증명을 UTF-8 바이트로 만든 뒤 base64한다. 그 선택의
 * 전제가 바로 이 테스트다 — 서버가 UTF-8로 읽지 않게 되는 날(예: BasicAuthenticationConverter의
 * credentialsCharset이 바뀌는 날) 한글 비밀번호 사용자는 프론트에서 조용히 로그인 불가가 되고, 그
 * 실패는 401이라 "비밀번호가 틀렸다"로만 보인다. 전제가 깨지면 여기서 먼저 빨간불이 되어야 한다.
 */
@AutoConfigureMockMvc
class BasicAuthCharsetTest extends AbstractWebTest {

    private static final String USERNAME = "han.gm";

    /** 한글 + Latin-1 밖의 기호. Latin-1로는 표현할 수 없어 인코딩이 실제로 갈린다. */
    private static final String PASSWORD = "비밀번호-Ω";

    /**
     * 이 테스트용 사용자 한 명만 설정으로 심는다. 목록 프로퍼티는 여러 소스에 걸쳐 합쳐지지 않고
     * 우선순위가 높은 소스의 것으로 통째로 대체되므로, application.yml의 셋(choi.dw 등)은 이
     * 컨텍스트에서 사라진다 — 이 테스트는 그 셋을 쓰지 않는다.
     *
     * <p>bcrypt 해시는 여기서 만든다. 해시를 상수로 박아 두면 위의 PASSWORD와 따로 놀 수 있고,
     * 그렇게 어긋나면 이 테스트는 "인코딩이 틀려서"가 아니라 "비밀번호가 달라서" 빨간불이 된다.
     */
    @DynamicPropertySource
    static void nonAsciiUser(DynamicPropertyRegistry registry) {
        String hash = "{bcrypt}" + new BCryptPasswordEncoder().encode(PASSWORD);
        registry.add("zerosum.web.users[0].username", () -> USERNAME);
        registry.add("zerosum.web.users[0].password", () -> hash);
        registry.add("zerosum.web.users[0].roles[0]", () -> "OPERATOR");
        registry.add("zerosum.web.users[0].warehouses[0]", () -> "ICN01");
    }

    @Autowired
    private MockMvc mockMvc;

    private static String basicHeader(String username, String password, Charset charset) {
        byte[] octets = (username + ":" + password).getBytes(charset);
        return "Basic " + Base64.getEncoder().encodeToString(octets);
    }

    @Test
    void utf8EncodedCredentialsAreAccepted() throws Exception {
        mockMvc.perform(get("/api/me")
                        .header(HttpHeaders.AUTHORIZATION, basicHeader(USERNAME, PASSWORD, StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(USERNAME));
    }

    /**
     * 위의 200이 "아무 헤더나 통과하기 때문"이 아니라는 것을 확인한다 — 자격 증명이 실제로 대조되고
     * 있어야 그쪽 단언에 의미가 생긴다.
     *
     * <p>다만 이것은 <b>문자셋을 판별하지 못한다.</b> Latin-1로는 이 비밀번호를 표현할 수 없어 '?'로
     * 치환된 옥텟이 나가므로, 서버가 UTF-8로 읽든 Latin-1로 읽든 똑같이 다른 비밀번호가 되어 401이다.
     * 실제로 서버를 Latin-1로 읽게 바꿔 보면 이 테스트는 그대로 통과하고 위의 것만 빨간불이 된다.
     * 문자셋 전제를 지키는 것은 {@link #utf8EncodedCredentialsAreAccepted()} 하나다.
     */
    @Test
    void latin1EncodedCredentialsAreRejected() throws Exception {
        mockMvc.perform(get("/api/me")
                        .header(HttpHeaders.AUTHORIZATION, basicHeader(USERNAME, PASSWORD, StandardCharsets.ISO_8859_1)))
                .andExpect(status().isUnauthorized());
    }
}
