package com.zerosum.inventory.web.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/**
 * {@code {id}}만 받는 엔드포인트(제안·이슈의 상세·승인·거부·인지·종결)가 함께 쓰는 인가 검사. 역할·창고
 * 둘 다 요청 값이 아니라 SecurityContext의 인증 주체만 본다 — {@link WarehouseScopeArgumentResolver}가
 * {@code ?warehouse=} 파라미터에 적용하는 것과 같은 원칙을, 타입으로 강제할 수 없는 {@code {id}} 경로에도
 * 그대로 적용한다.
 */
public final class AccessGuard {

    private AccessGuard() {
    }

    /** authentication이 roles 중 어느 것도 갖고 있지 않으면 403. */
    public static void requireAnyRole(Authentication authentication, String... roles) {
        boolean allowed = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority -> matchesAny(roles, authority));
        if (!allowed) {
            throw new AccessDeniedException("이 작업에 필요한 권한이 없다");
        }
    }

    /** authentication의 principal이 warehouseCode에 접근할 수 없으면 403. */
    public static void requireWarehouse(Authentication authentication, String warehouseCode) {
        if (!(authentication.getPrincipal() instanceof WarehouseUser user) || !user.canAccess(warehouseCode)) {
            // WarehouseScopeArgumentResolver와 같은 이유로 창고가 존재하는지는 알려주지 않는다.
            throw new AccessDeniedException("창고 접근 권한이 없다");
        }
    }

    private static boolean matchesAny(String[] roles, String authority) {
        for (String role : roles) {
            if (("ROLE_" + role).equals(authority)) {
                return true;
            }
        }
        return false;
    }
}
