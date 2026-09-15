package com.zerosum.inventory.web.security;

import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 주체 자기 정보. 프론트엔드가 창고 드롭다운을 채우고 역할에 따라 승인·거부·인지·종결 버튼을
 * 감추는 데 쓴다 — 어디까지나 화면을 정리하는 용도다. 여기서 내려준 roles·warehouses를 프론트가 믿고
 * 버튼을 숨겨도, 실제 인가는 {@link AccessGuard}와 {@link WarehouseScopeArgumentResolver}가 매 요청마다
 * SecurityContext에서 다시 검사한다 — 프론트가 버튼을 잘못 보여주거나 요청을 우회해도 서버가 막는다.
 */
@RestController
public class MeController {

    @GetMapping("/api/me")
    public MeResponse me(Authentication authentication) {
        WarehouseUser user = (WarehouseUser) authentication.getPrincipal();
        // HTTP Basic 인증은 역할 권한 말고도 FACTOR_PASSWORD 같은(ROLE_ 접두사 없는) 인증 요인 권한을
        // 함께 심는다 — SecurityConfig#toUserDetails가 "ROLE_" + role로만 만든 것과 구분해야 그게 섞여
        // 나가지 않는다. ROLE_ 접두사가 있는 것만 역할로 본다.
        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring("ROLE_".length()))
                .toList();
        List<String> warehouses = user.warehouses().stream().sorted().toList();
        return new MeResponse(user.getUsername(), roles, warehouses);
    }

    public record MeResponse(String username, List<String> roles, List<String> warehouses) {
    }
}
