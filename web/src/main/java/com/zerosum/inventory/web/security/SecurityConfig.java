package com.zerosum.inventory.web.security;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.password.CompromisedPasswordChecker;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * HTTP Basic + 설정 사용자. 세션을 만들지 않고 요청마다 인증한다 — 브라우저 기본 로그인 창이 뜨지 않도록
 * 401에 {@code WWW-Authenticate}를 붙이지 않고 상태 코드만 돌려준다(프론트엔드가 직접 자격 증명을 붙인다).
 *
 * <p>세션이 없으므로 CSRF 토큰도 필요 없다 — 쿠키로 인증하지 않으면 다른 사이트가 사용자의 자격 증명을
 * 실어 보낼 수 없다. 나중에 세션·쿠키 인증으로 바꾼다면 CSRF를 반드시 다시 켜야 한다.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(WebUsersProperties.class)
public class SecurityConfig implements WebMvcConfigurer {

    private final WarehouseScopeArgumentResolver warehouseScopeArgumentResolver;

    SecurityConfig(WarehouseScopeArgumentResolver warehouseScopeArgumentResolver) {
        this.warehouseScopeArgumentResolver = warehouseScopeArgumentResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(warehouseScopeArgumentResolver);
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 인증이 필요한 것은 /api/** 뿐이다. 정적 자산(로그인 폼을 담은 index.html, JS, CSS)까지
                // 막으면 로그인하려면 먼저 로그인해야 하는 닭-달걀이 된다 — 서버가 WWW-Authenticate를
                // 보내지 않으므로(브라우저 기본 창 대신 프론트엔드가 자기 폼을 그린다) 사용자는 빈 401만 받는다.
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .httpBasic(basic -> basic.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        // {bcrypt}·{noop} 같은 접두사로 인코더를 고른다. 설정 파일에는 {bcrypt} 해시를 두는 것을 전제로 한다.
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /** 유출 비밀번호 검사를 끈다 — 외부 서비스를 호출하는 기본 구현이라 폐쇄망에서 인증이 통째로 막힌다. */
    @Bean
    CompromisedPasswordChecker compromisedPasswordChecker() {
        return password -> new org.springframework.security.authentication.password
                .CompromisedPasswordDecision(false);
    }

    /**
     * 사용자 조회. 조회할 때마다 {@link WarehouseUser}를 **새로 만든다.**
     *
     * <p>인스턴스를 만들어 두고 재사용하면 안 된다 — Spring Security의 {@code ProviderManager}는 인증에
     * 성공하면 principal이 {@code CredentialsContainer}일 때 {@code eraseCredentials()}를 불러 비밀번호를
     * 지운다. {@code User}가 그 인터페이스를 구현하므로, 캐싱한 인스턴스는 첫 로그인 직후 비밀번호가
     * null이 되고 그 사용자는 두 번째 로그인부터 영영 401이 된다(실측 확인, RepeatedLoginTest).
     *
     * <p>같은 이유로 {@code InMemoryUserDetailsManager}도 쓰지 않는다. 그 구현은 넘긴 UserDetails를
     * MutableUser로 감싸 보관해서 {@link WarehouseUser} 타입이 사라지고, 창고 권한을 principal에서
     * 꺼낼 수 없게 된다(실측: 인증된 모든 요청이 403이 됐다).
     */
    @Bean
    UserDetailsService userDetailsService(WebUsersProperties properties) {
        Map<String, WebUsersProperties.WebUser> byName = properties.users().stream()
                .collect(Collectors.toMap(WebUsersProperties.WebUser::username, u -> u));
        return username -> {
            WebUsersProperties.WebUser found = byName.get(username);
            if (found == null) {
                throw new UsernameNotFoundException(username);
            }
            return toUserDetails(found);
        };
    }

    private static UserDetails toUserDetails(WebUsersProperties.WebUser user) {
        List<GrantedAuthority> authorities = user.roles().stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        return new WarehouseUser(user.username(), user.password(), authorities, Set.copyOf(user.warehouses()));
    }
}
