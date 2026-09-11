package com.zerosum.inventory.posting;

import com.zerosum.inventory.master.Location;
import com.zerosum.inventory.master.LocationRepository;
import com.zerosum.inventory.master.Lot;
import com.zerosum.inventory.master.LotRepository;
import com.zerosum.inventory.master.Sku;
import com.zerosum.inventory.master.SkuRepository;
import com.zerosum.inventory.master.Warehouse;
import com.zerosum.inventory.master.WarehouseRepository;
import org.springframework.stereotype.Component;

/**
 * 커맨드의 코드 문자열(창고·로케이션·SKU·로트)을 id로 해석한다. db/04-harness.sql의 tst_resolve에 대응하며,
 * 마스터 데이터는 읽기 전용이므로 JPA 리포지토리로 조회한다 (쓰기 경로의 잠금은 JdbcClient가 담당).
 */
@Component
class PostingLineResolver {

    private final WarehouseRepository warehouseRepository;
    private final LocationRepository locationRepository;
    private final SkuRepository skuRepository;
    private final LotRepository lotRepository;

    PostingLineResolver(WarehouseRepository warehouseRepository, LocationRepository locationRepository,
            SkuRepository skuRepository, LotRepository lotRepository) {
        this.warehouseRepository = warehouseRepository;
        this.locationRepository = locationRepository;
        this.skuRepository = skuRepository;
        this.lotRepository = lotRepository;
    }

    ResolvedLine resolve(PostingLineInput line) {
        Warehouse warehouse = warehouseRepository.findByCode(line.warehouseCode())
                .orElseThrow(() -> unknownCode(line));
        Location location = locationRepository.findByWarehouse_CodeAndCode(line.warehouseCode(), line.locationCode())
                .orElseThrow(() -> unknownCode(line));
        Sku sku = skuRepository.findByCode(line.skuCode())
                .orElseThrow(() -> unknownCode(line));
        Lot lot = lotRepository.findBySku_IdAndLotNo(sku.getId(), line.lotNo())
                .orElseThrow(() -> unknownCode(line));

        return new ResolvedLine(
                new WarehouseId(warehouse.getId()),
                new LocationId(location.getId()),
                location.isVirtual(),
                new SkuId(sku.getId()),
                new LotId(lot.getId()),
                line.qty(),
                line.locationCode(),
                line.skuCode(),
                line.lotNo());
    }

    private PostingException unknownCode(PostingLineInput line) {
        return new PostingException("UNKNOWN_CODE",
                "창고·로케이션·SKU·로트 코드 중 해석되지 않은 것이 있다: %s".formatted(line));
    }
}
