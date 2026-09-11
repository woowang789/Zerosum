package com.zerosum.inventory.posting;

/** 포스팅 커맨드 한 줄의 원본 입력. 코드(창고·로케이션·SKU·로트)는 {@link PostingLineResolver}가 id로 해석한다. */
public record PostingLineInput(
        String warehouseCode,
        String locationCode,
        String skuCode,
        String lotNo,
        int qty) {
}
