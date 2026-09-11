package com.zerosum.inventory.posting;

/** FOR SHARE로 잠근 시점의 로케이션. {@code countSessionId}가 널이 아니면 실사 진행 중이다. */
public record LockedLocation(LocationId id, String code, Long countSessionId) {
}
