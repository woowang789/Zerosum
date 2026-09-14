package com.zerosum.inventory.domain;

/** {@code location.id}. long 인자 순서 실수를 컴파일 에러로 만들기 위한 식별자 래퍼. */
public record LocationId(long value) implements Comparable<LocationId> {
    @Override
    public int compareTo(LocationId other) {
        return Long.compare(value, other.value);
    }
}
