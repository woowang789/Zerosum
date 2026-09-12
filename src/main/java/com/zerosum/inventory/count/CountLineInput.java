package com.zerosum.inventory.count;

/** 제출 커맨드 한 줄(실사자가 센 값). db/04-harness.sql tst_count_submit의 p_lines 원소 하나에 대응한다. */
public record CountLineInput(String skuCode, String lotNo, int countedQty) {
}
