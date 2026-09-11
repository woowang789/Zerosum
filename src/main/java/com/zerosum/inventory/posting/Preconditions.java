package com.zerosum.inventory.posting;

import java.util.function.Predicate;

/**
 * 선행 조건이 없는 포스팅용 편의 상수. {@code postingGateway.post(request, Preconditions.none())}처럼 쓴다.
 * PostingService에 같은 클래스를 다시 호출하는 편의 오버로드를 두지 않는 이유는
 * docs/04-write-path.md 포스팅 흐름의 마지막 주석 — 자기 호출은 @Transactional이 적용되지 않는다 — 참고.
 */
public final class Preconditions {

    private static final Predicate<LockedBalances> NONE = balances -> true;

    private Preconditions() {
    }

    public static Predicate<LockedBalances> none() {
        return NONE;
    }
}
