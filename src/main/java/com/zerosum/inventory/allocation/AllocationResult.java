package com.zerosum.inventory.allocation;

import java.util.List;

/** 할당 결과. {@code allocationIds}는 새로 만들어졌거나(최초 실행), 멱등 재요청으로 되돌려준 기존 할당 id 목록이다. */
public record AllocationResult(List<AllocationId> allocationIds) {
}
