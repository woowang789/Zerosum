package com.zerosum.inventory.domain;

/** basis_snapshot의 warehouse_sku 스코프 관측값 — 창고·SKU 조합의 판매 가능 수량 재검증 기준. */
public record WarehouseSkuObservation(long warehouseId, long skuId, int sellableQty) {
}
