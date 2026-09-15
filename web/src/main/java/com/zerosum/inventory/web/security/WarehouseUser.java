package com.zerosum.inventory.web.security;

import java.util.Collection;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

/**
 * 인증된 사용자 + 그 사용자가 볼 수 있는 창고. 창고 권한을 요청이 아니라 인증 주체가 들고 있어야
 * 서버가 강제할 수 있다 — 요청이 창고 코드를 보내더라도 여기 없으면 거절된다.
 */
public class WarehouseUser extends User {

    private final Set<String> warehouses;

    public WarehouseUser(String username, String password, Collection<? extends GrantedAuthority> authorities,
            Set<String> warehouses) {
        super(username, password, authorities);
        this.warehouses = Set.copyOf(warehouses);
    }

    public boolean canAccess(String warehouseCode) {
        return warehouses.contains(warehouseCode);
    }

    public Set<String> warehouses() {
        return warehouses;
    }
}
