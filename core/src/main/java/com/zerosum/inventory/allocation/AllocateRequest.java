package com.zerosum.inventory.allocation;

/**
 * 할당 서비스의 공개 입력. docs/04-write-path.md 할당 흐름의 {@code cmd}에 대응한다.
 * {@code allowInCount}는 기본값을 두지 않는다 — 채널 노출 정책과 짝을 맞춰야 하므로 호출자가 매번 명시해야 한다
 * (05-count-session.md: 기본값이 있으면 노출 정책만 바뀐 날 조용히 주문이 실패한다).
 *
 * <p>멱등 키는 입력에 없다. {@code allocate:{주문줄}:{회차}}의 회차를 트랜잭션 안에서 세어야 하므로
 * 키 파생은 {@link AllocationService#allocate}가 한다 — 이유는 그 메서드의 주석을 보라.
 */
public record AllocateRequest(
        String orderLineRef,
        String warehouseCode,
        String skuCode,
        int qty,
        boolean allowInCount) {
}
