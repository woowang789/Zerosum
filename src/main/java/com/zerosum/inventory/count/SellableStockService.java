package com.zerosum.inventory.count;

import com.zerosum.inventory.master.Sku;
import com.zerosum.inventory.master.SkuRepository;
import com.zerosum.inventory.master.Warehouse;
import com.zerosum.inventory.master.WarehouseRepository;
import com.zerosum.inventory.repository.SellableStockRepository;
import org.springframework.stereotype.Service;

/**
 * 판매 가능 수량 읽기 경로 (docs/05-count-session.md). {@code v_sellable_stock}을 읽어 {@code sellableQty}
 * ({@code count_session_id IS NULL}인 로케이션 합)와 {@code inCountQty}(실사 중인 로케이션 합)를 나눠 제공한다.
 * 코어는 정책을 강제하지 않는다 — 둘 중 무엇을 채널에 노출할지는 범위 밖(채널 연동)의 결정이다.
 */
@Service
public class SellableStockService {

    private final WarehouseRepository warehouseRepository;
    private final SkuRepository skuRepository;
    private final SellableStockRepository sellableStockRepository;

    SellableStockService(WarehouseRepository warehouseRepository, SkuRepository skuRepository,
            SellableStockRepository sellableStockRepository) {
        this.warehouseRepository = warehouseRepository;
        this.skuRepository = skuRepository;
        this.sellableStockRepository = sellableStockRepository;
    }

    public SellableStock find(String warehouseCode, String skuCode) {
        Warehouse warehouse = warehouseRepository.findByCode(warehouseCode)
                .orElseThrow(() -> unknownCode(warehouseCode, skuCode));
        Sku sku = skuRepository.findByCode(skuCode)
                .orElseThrow(() -> unknownCode(warehouseCode, skuCode));
        return sellableStockRepository.find(warehouse.getId(), sku.getId());
    }

    private CountSessionException unknownCode(String warehouseCode, String skuCode) {
        return new CountSessionException("UNKNOWN_CODE",
                "창고·SKU 코드 중 해석되지 않은 것이 있다: %s/%s".formatted(warehouseCode, skuCode));
    }
}
