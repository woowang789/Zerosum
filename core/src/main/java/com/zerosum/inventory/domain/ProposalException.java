package com.zerosum.inventory.domain;

/**
 * 제안 생성 단계에서의 거부. 승인 시점 예외(예: inventory_txn의 CHECK로 ADJUSTMENT는 reason_code를
 * 요구하는 것)로 미루면 제안이 PENDING인 채로 "버튼이 먹지 않는" 상태가 되므로, 생성 시점에 미리
 * 거부하기 위한 타입이다. domain.PostingException과 같은 모양이다.
 */
public class ProposalException extends RuntimeException {

    private final String code;

    public ProposalException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
