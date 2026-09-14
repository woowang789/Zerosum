package com.zerosum.inventory.domain;

/** 같은 멱등 키에 다른 요청 본문이 들어왔다 (docs/04-write-path.md 멱등성 — 409). */
public class IdempotencyConflictException extends PostingException {

    public IdempotencyConflictException(String idemKey) {
        super("IDEM_CONFLICT_409", "같은 멱등 키에 다른 요청 본문 (%s)".formatted(idemKey));
    }
}
