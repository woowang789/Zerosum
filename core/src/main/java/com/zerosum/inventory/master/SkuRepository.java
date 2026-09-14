package com.zerosum.inventory.master;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkuRepository extends JpaRepository<Sku, Long> {

    Optional<Sku> findByCode(String code);
}
