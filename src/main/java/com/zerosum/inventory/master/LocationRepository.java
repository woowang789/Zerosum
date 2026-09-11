package com.zerosum.inventory.master;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LocationRepository extends JpaRepository<Location, Long> {

    Optional<Location> findByWarehouse_CodeAndCode(String warehouseCode, String code);
}
