package com.zerosum.inventory.allocation;

import com.zerosum.inventory.domain.AllocationException;

/** 가용 수량이 요청한 할당량에 못 미친다 (docs/04-write-path.md 할당 흐름의 InsufficientStockException). */
public class InsufficientStockException extends AllocationException {

    public InsufficientStockException(String skuCode, int requestedQty, int shortBy) {
        super("INSUFFICIENT_STOCK", "%s 할당 요청 %d, 가용 부족 %d".formatted(skuCode, requestedQty, shortBy));
    }
}
