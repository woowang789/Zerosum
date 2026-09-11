package com.zerosum.inventory.posting;

import java.time.Instant;
import java.util.List;

/**
 * 포스팅 서비스의 공개 입력. {@code sourceType}·{@code sourceRef}·{@code reasonCode}·{@code reversesTxnId}는
 * 거래 유형에 따라 널일 수 있다 (예: REVERSAL이 아니면 reversesTxnId는 널).
 */
public record PostingRequest(
        String idemKey,
        String txnType,
        String actorType,
        String actorId,
        List<PostingLineInput> lines,
        String sourceType,
        String sourceRef,
        String reasonCode,
        Long reversesTxnId,
        Instant occurredAt) {
}
