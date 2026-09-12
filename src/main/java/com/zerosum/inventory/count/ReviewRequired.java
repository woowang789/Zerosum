package com.zerosum.inventory.count;

/** 허용 오차를 넘는 차이가 있어 세션이 REVIEW로 남았다. 로케이션 표시는 유지되고 사람의 정정 승인을 기다린다. */
public record ReviewRequired() implements CountSubmitOutcome {
}
