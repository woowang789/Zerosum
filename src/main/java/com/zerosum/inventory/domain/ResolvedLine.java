package com.zerosum.inventory.domain;

/**
 * 코드가 id로 해석된 커맨드 한 줄. {@code virtual}이 참이면 가상 로케이션이라 잔액 행이 없다.
 * 코드 문자열(locationCode/skuCode/lotNo)은 NO_STOCK 등 오류 메시지에만 쓴다.
 */
public record ResolvedLine(
        WarehouseId warehouseId,
        LocationId locationId,
        boolean virtual,
        SkuId skuId,
        LotId lotId,
        int qtyDelta,
        String locationCode,
        String skuCode,
        String lotNo) {

    public BalanceKey key() {
        return new BalanceKey(locationId, skuId, lotId);
    }
}
