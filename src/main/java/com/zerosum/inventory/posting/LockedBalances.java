package com.zerosum.inventory.posting;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** id 오름차순으로 잠근 잔액 행 전체. 가상 로케이션 키는 여기 없으므로 find()가 비어 있으면 잔액 행이 없다는 뜻이다. */
public final class LockedBalances {

    private final List<LockedBalance> ordered;
    private final Map<BalanceKey, LockedBalance> byKey;

    public LockedBalances(List<LockedBalance> ordered) {
        this.ordered = List.copyOf(ordered);
        this.byKey = this.ordered.stream().collect(Collectors.toMap(LockedBalance::key, Function.identity()));
    }

    public Optional<LockedBalance> find(BalanceKey key) {
        return Optional.ofNullable(byKey.get(key));
    }

    public BalanceSnapshot snapshot() {
        return new BalanceSnapshot(ordered.stream().map(LockedBalance::toLine).toList());
    }
}
