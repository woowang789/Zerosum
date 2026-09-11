package com.zerosum.inventory.count;

/**
 * 실사 세션 흐름(시작·제출·정정·중단)에서 발생하는 비즈니스 오류. {@code code}는 db/04-harness.sql의
 * tst_count_start·tst_count_submit·tst_count_resolve·tst_count_abandon이 올리는 오류 코드
 * (COUNT_ALREADY_OPEN, COUNT_NOT_OPEN, COUNT_NOT_SUBMITTED, COUNT_CLOSED, UNKNOWN_CODE)와 대응한다.
 * posting.PostingException·allocation.AllocationException과 같은 역할이지만, 실사는 포스팅
 * 트랜잭션이 아니므로(정정 단계에서만 내부적으로 포스팅을 호출한다) 별도 타입으로 둔다.
 */
public class CountSessionException extends RuntimeException {

    private final String code;

    public CountSessionException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
