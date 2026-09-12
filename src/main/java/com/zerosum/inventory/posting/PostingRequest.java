package com.zerosum.inventory.posting;

import java.time.Instant;
import java.util.List;

/**
 * 포스팅 서비스의 공개 입력. {@code sourceType}·{@code sourceRef}·{@code reasonCode}·{@code reversesTxnId}는
 * 거래 유형에 따라 널일 수 있다 (예: REVERSAL이 아니면 reversesTxnId는 널).
 * {@code consumeAllocationIds}는 출고(SHIPMENT)가 이 거래에서 함께 소진할 할당 id다 (2단계 확장,
 * db/04-harness.sql tst_post의 p_consume_alloc과 대응) — SHIPMENT가 아니면 빈 리스트다.
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
        Instant occurredAt,
        List<Long> consumeAllocationIds) {

    /** 1단계 호출부(할당 소진이 없는 거래) 호환용. consumeAllocationIds는 빈 리스트로 채운다. */
    public PostingRequest(String idemKey, String txnType, String actorType, String actorId,
            List<PostingLineInput> lines, String sourceType, String sourceRef, String reasonCode,
            Long reversesTxnId, Instant occurredAt) {
        this(idemKey, txnType, actorType, actorId, lines, sourceType, sourceRef, reasonCode, reversesTxnId,
                occurredAt, List.of());
    }
}
