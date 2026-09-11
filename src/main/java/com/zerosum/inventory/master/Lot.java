package com.zerosum.inventory.master;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import org.hibernate.annotations.Immutable;

/** 로트 마스터 (읽기 전용). 로트 미관리 SKU도 {@code DEFAULT} 로트를 하나 갖는다 (I11). */
@Entity
@Immutable
@Table(name = "lot")
public class Lot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sku_id")
    private Sku sku;

    private String lotNo;

    private LocalDate expiryDate;

    protected Lot() {
        // JPA
    }

    public Long getId() {
        return id;
    }

    public Sku getSku() {
        return sku;
    }

    public String getLotNo() {
        return lotNo;
    }

    public LocalDate getExpiryDate() {
        return expiryDate;
    }
}
