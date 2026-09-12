package com.zerosum.inventory.domain;

/** (로케이션, SKU, 로트) — {@code stock_balance}의 UNIQUE (location_id, sku_id, lot_id)에 대응하는 논리 키. */
public record BalanceKey(LocationId locationId, SkuId skuId, LotId lotId) {
}
