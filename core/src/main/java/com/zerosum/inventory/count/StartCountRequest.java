package com.zerosum.inventory.count;

/**
 * 실사 시작 커맨드. db/04-harness.sql tst_count_start의 입력과 대응하지만 멱등 키는 받지 않는다 —
 * "이 로케이션을 실사한다"는 반복되는 일이라 업무 식별자(창고, 로케이션)가 유일하지 않기 때문이다.
 * 대상을 멱등 키로 삼으면 두 번째 실사가 첫 번째의 재생이 되어버린다(순환 실사가 평생 한 번만 가능해진다).
 * 재시도의 재생은 {@code location.count_session_id}가 대신한다 — CountSessionService#start 참고.
 */
public record StartCountRequest(String warehouseCode, String locationCode, String startedBy) {
}
