package com.zerosum.inventory.web.security;

/**
 * 검증을 통과한 창고 코드. 컨트롤러는 {@code ?warehouse=} 원본 문자열을 읽지 않고 이 타입을 받는다 —
 * 이 타입을 얻는 유일한 길이 {@link WarehouseScopeArgumentResolver}이므로, 창고 검증을 빠뜨린
 * 엔드포인트를 만들 수 없다.
 *
 * <p>검증을 도우미 메서드로 두면 새 엔드포인트에서 부르는 것을 잊을 수 있다. 타입으로 강제하면
 * 잊을 수가 없다.
 */
public record WarehouseScope(String code) {
}
