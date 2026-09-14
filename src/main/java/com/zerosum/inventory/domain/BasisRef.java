package com.zerosum.inventory.domain;

/**
 * basis_snapshot 관측 대상 하나. scope는 {@code "balance"} 또는 {@code "warehouse_sku"}
 * (docs/07-ai-integration.md, po_line은 발주 테이블이 없어 범위 밖).
 *
 * <p>balance 스코프는 warehouseCode·locationCode·skuCode·lotNo 네 코드로 v_balance_basis의 잔액 행
 * 하나를 짚는다. warehouse_sku 스코프는 locationCode·lotNo가 의미 없다(널) — warehouseCode·skuCode
 * 두 코드로 v_warehouse_sku_basis(V4)의 창고·SKU 조합 하나를 짚는다.
 */
public record BasisRef(String scope, String warehouseCode, String locationCode, String skuCode, String lotNo) {

    public static BasisRef balance(String warehouseCode, String locationCode, String skuCode, String lotNo) {
        return new BasisRef("balance", warehouseCode, locationCode, skuCode, lotNo);
    }

    public static BasisRef warehouseSku(String warehouseCode, String skuCode) {
        return new BasisRef("warehouse_sku", warehouseCode, null, skuCode, null);
    }
}
