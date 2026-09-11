package com.zerosum.inventory.master;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LotRepository extends JpaRepository<Lot, Long> {

    Optional<Lot> findBySku_IdAndLotNo(Long skuId, String lotNo);
}
