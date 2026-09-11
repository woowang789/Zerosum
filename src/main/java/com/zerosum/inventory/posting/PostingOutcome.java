package com.zerosum.inventory.posting;

/** 포스팅 결과. docs/04-write-path.md 포스팅 흐름의 결과 타입. */
public sealed interface PostingOutcome permits Posted, PreconditionFailed {
}
