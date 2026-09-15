package com.zerosum.inventory.web.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 설정에서 오는 사용자 목록. 사용자 디렉터리(테이블·LDAP)를 두지 않는다 — 이 프로젝트가 증명하려는 것은
 * 재고 정합성이지 신원 관리가 아니므로, 신원은 운영 설정이 책임지고 그 한계를 문서에 적는다.
 *
 * <p>password는 {@code {bcrypt}$2a$...} 처럼 인코더 접두사를 포함한다. {@code {noop}}도 동작하지만
 * 설정 파일에 평문을 두는 것이므로 운영에서는 쓰지 않는다.
 *
 * @param users 사용자 목록. 비어 있으면 아무도 로그인할 수 없다(기본값을 만들어 주지 않는다 —
 *              기본 계정은 잊히고 남는다)
 */
@ConfigurationProperties(prefix = "zerosum.web")
public record WebUsersProperties(List<WebUser> users) {

    /**
     * @param warehouses 이 사용자가 접근할 수 있는 창고 코드. 여기 없는 창고를 요청하면 403이다
     *                   ({@link WarehouseScopeArgumentResolver}).
     */
    public record WebUser(String username, String password, List<String> roles, List<String> warehouses) {
    }
}
