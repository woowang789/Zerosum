package com.zerosum.inventory.posting;

import java.util.List;

/**
 * 잠금 직후 잔액 스냅샷. {@link PreconditionFailed}가 호출자에게 돌려주는 "지금 상태".
 * 멱등 재요청으로 PRECONDITION_FAILED를 재현하는 경우에는 잠금을 다시 잡지 않으므로 빈 스냅샷을 돌려준다
 * (1단계는 선행 조건을 쓰지 않아 이 경로가 실제로 타지 않는다).
 */
public record BalanceSnapshot(List<BalanceLine> lines) {

    public static BalanceSnapshot empty() {
        return new BalanceSnapshot(List.of());
    }
}
