package com.zerosum.inventory.domain;

/**
 * FOR UPDATE로 잠근 직후의 잔액 스냅샷. 선행 조건 검사({@link PreconditionFailed}의 근거)와
 * NO_STOCK 판정(잔액 행 존재 여부)에만 쓰고, 실제 증감 적용은 여기서 계산하지 않는다.
 * 한 거래에 같은 잔액 키를 가리키는 줄이 여러 개 있을 수 있어, 이 record가 들고 있는
 * on_hand_qty는 두 번째 줄부터는 이미 낡은 값이기 때문이다 — 적용은 항상
 * {@link com.zerosum.inventory.repository.StockBalanceRepository#applyDelta}의 상대 갱신에 맡긴다.
 */
public record LockedBalance(BalanceId id, WarehouseId warehouseId, BalanceKey key, int onHandQty, int allocatedQty) {

    public BalanceLine toLine() {
        return new BalanceLine(id, key.locationId(), key.skuId(), key.lotId(), onHandQty, allocatedQty);
    }
}
