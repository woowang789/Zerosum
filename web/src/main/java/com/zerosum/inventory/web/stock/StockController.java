package com.zerosum.inventory.web.stock;

import com.zerosum.inventory.web.security.WarehouseScope;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 재고 현황 조회. 전 역할 허용 — 창고 범위만 {@link WarehouseScope}가 강제한다(역할 검사 없음).
 */
@RestController
public class StockController {

    private static final int LIST_LIMIT = 500;

    private final StockRepository stockRepo;

    StockController(StockRepository stockRepo) {
        this.stockRepo = stockRepo;
    }

    @GetMapping("/api/stock")
    public List<StockRepository.AvailableStockRow> stock(WarehouseScope warehouse,
            @RequestParam(required = false) String sku) {
        return stockRepo.find(warehouse.code(), sku, LIST_LIMIT);
    }
}
