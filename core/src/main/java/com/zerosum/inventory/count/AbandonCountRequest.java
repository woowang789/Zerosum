package com.zerosum.inventory.count;

/** 중단 커맨드. 정정 없이 세션을 ABANDONED로 닫는다 (db/04-harness.sql tst_count_abandon). */
public record AbandonCountRequest(String idemKey, long sessionId, String abandonedBy) {
}
