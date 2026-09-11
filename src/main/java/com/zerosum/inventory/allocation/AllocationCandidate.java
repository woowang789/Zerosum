package com.zerosum.inventory.allocation;

import com.zerosum.inventory.posting.BalanceId;
import java.time.LocalDate;

/**
 * FEFO 정렬 대상 잔액 행. {@code stock_balance} FOR UPDATE로 잠근 뒤 다시 읽은 값 + 로트 유통기한.
 * posting 패키지의 {@link com.zerosum.inventory.posting.LockedBalance}는 유통기한을 담지 않으므로
 * (포스팅 흐름에는 FEFO가 없다) 재사용하지 않고 할당 전용으로 별도로 둔다.
 */
public record AllocationCandidate(BalanceId id, int onHandQty, int allocatedQty, LocalDate expiryDate) {

    public int availableQty() {
        return onHandQty - allocatedQty;
    }
}
