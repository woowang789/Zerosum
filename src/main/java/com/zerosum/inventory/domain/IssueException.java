package com.zerosum.inventory.domain;

/**
 * 이슈 처리(인지·종결) 단계에서 발생하는 비즈니스 오류. posting.PostingException·allocation.AllocationException과
 * 같은 역할이지만, 이슈 처리는 포스팅 트랜잭션이 아니므로 별도 타입으로 둔다.
 */
public class IssueException extends RuntimeException {

    private final String code;

    public IssueException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
