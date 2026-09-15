package com.zerosum.inventory.web.error;

/**
 * 코어 예외(ProposalException 등)가 아니라 웹 계층 자체의 요청 형태 검증 실패. 가상 로케이션을 물리 줄에
 * 직접 지정하는 것처럼 코어에 넘기기 전에 이미 거절해야 하는 요청이 여기 해당한다 ({@code code}는
 * {@code ApiExceptionHandler}의 코어 예외 코드와 겹치지 않는다).
 */
public class InvalidRequestException extends RuntimeException {

    private final String code;

    public InvalidRequestException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
