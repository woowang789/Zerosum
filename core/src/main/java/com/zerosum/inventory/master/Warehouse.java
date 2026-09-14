package com.zerosum.inventory.master;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/**
 * 창고 마스터 (읽기 전용). 1단계에서 마스터 CRUD는 범위 밖이라 조회에만 쓴다.
 * {@code @Immutable}로 Hibernate가 변경 감지·flush를 시도하지 않게 한다.
 */
@Entity
@Immutable
@Table(name = "warehouse")
public class Warehouse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String code;

    private String name;

    protected Warehouse() {
        // JPA
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }
}
