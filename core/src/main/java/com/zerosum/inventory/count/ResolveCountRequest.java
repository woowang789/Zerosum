package com.zerosum.inventory.count;

/** 정정(승인) 커맨드. REVIEW로 남은 세션을 검토한 뒤 사람이 승인할 때 쓴다 (db/04-harness.sql tst_count_resolve). */
public record ResolveCountRequest(String idemKey, long sessionId, String resolvedBy) {
}
