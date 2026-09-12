package com.zerosum.inventory.domain;

/**
 * 할당·할당 해제 단계에서 발생하는 비즈니스 오류. {@code code}는 db/04-harness.sql의 tst_allocate·
 * tst_release_alloc가 올리는 오류 코드(INSUFFICIENT_STOCK, ALLOC_NOT_ACTIVE)와 대응한다.
 * posting 패키지의 {@link com.zerosum.inventory.posting.PostingException}과 같은 역할이지만,
 * 할당은 포스팅 트랜잭션이 아니므로 별도 타입으로 둔다.
 */
public class AllocationException extends RuntimeException {

    private final String code;

    public AllocationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
