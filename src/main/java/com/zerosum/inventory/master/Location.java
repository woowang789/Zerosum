package com.zerosum.inventory.master;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/**
 * 로케이션 마스터 (읽기 전용). 코드 해석(포스팅 커맨드의 로케이션 코드 → id)에만 쓴다.
 * FOR SHARE 잠금과 실사 표시(count_session_id) 확인은 쓰기 경로이므로 JdbcClient
 * ({@link com.zerosum.inventory.repository.LocationLockRepository})가 담당하고, 여기서는 다루지 않는다.
 */
@Entity
@Immutable
@Table(name = "location")
public class Location {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id")
    private Warehouse warehouse;

    private String code;

    private String locationType;

    private boolean isVirtual;

    protected Location() {
        // JPA
    }

    public Long getId() {
        return id;
    }

    public Warehouse getWarehouse() {
        return warehouse;
    }

    public String getCode() {
        return code;
    }

    public String getLocationType() {
        return locationType;
    }

    public boolean isVirtual() {
        return isVirtual;
    }
}
