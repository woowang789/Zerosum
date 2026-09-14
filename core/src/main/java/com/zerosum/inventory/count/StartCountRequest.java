package com.zerosum.inventory.count;

/** 실사 시작 커맨드. db/04-harness.sql tst_count_start의 입력과 대응한다. */
public record StartCountRequest(String idemKey, String warehouseCode, String locationCode, String startedBy) {
}
