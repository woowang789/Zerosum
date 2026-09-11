package com.zerosum.inventory.posting;

/**
 * 커맨드 검증·포스팅 적용 단계에서 발생하는 비즈니스 오류.
 * {@code code}는 db/04-harness.sql의 tst_post가 올리는 오류 코드(NOT_ZERO_SUM, REASON_REQUIRED,
 * COUNT_IN_PROGRESS, NO_STOCK, INSUFFICIENT_STOCK, UNKNOWN_CODE)와 대응한다.
 */
public class PostingException extends RuntimeException {

    private final String code;

    public PostingException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
