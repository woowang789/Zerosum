package com.zerosum.inventory.count;

/** 제출 결과. docs/05-count-session.md 제출 절차의 두 결과(CONFIRMED/REVIEW)에 대응한다. */
public sealed interface CountSubmitOutcome permits Confirmed, ReviewRequired {
}
