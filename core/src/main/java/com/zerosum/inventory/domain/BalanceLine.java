package com.zerosum.inventory.domain;

/** {@link BalanceSnapshot} 한 줄. */
public record BalanceLine(BalanceId id, LocationId locationId, SkuId skuId, LotId lotId, int onHandQty, int allocatedQty) {
}
