package com.zerosum.inventory.domain;

import java.util.List;

public record LockedLocations(List<LockedLocation> locations) {

    /** 실사 진행 중인 로케이션이 있으면 거절한다 (docs/04-write-path.md의 requireNoActiveCountSession). */
    public void requireNoActiveCountSession() {
        List<String> blocked = locations.stream()
                .filter(l -> l.countSessionId() != null)
                .map(LockedLocation::code)
                .toList();
        if (!blocked.isEmpty()) {
            throw new PostingException("COUNT_IN_PROGRESS",
                    "실사 진행 중인 로케이션 (%s)".formatted(String.join(", ", blocked)));
        }
    }
}
