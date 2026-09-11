package com.zerosum.inventory.posting;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 코드 해석과 검증을 마친 포스팅 커맨드. docs/04-write-path.md 포스팅 흐름의 {@code cmd}에 대응한다.
 * 1단계 범위(RECEIPT/MOVE/ADJUSTMENT/REVERSAL)에는 할당 소진이 없으므로, 출고 전용 규칙은 없다.
 */
public record PostingCommand(
        String idemKey,
        String txnType,
        String actorType,
        String actorId,
        List<ResolvedLine> entries,
        String sourceType,
        String sourceRef,
        String reasonCode,
        Long reversesTxnId,
        Instant occurredAt) {

    /** ② 커맨드 검증: (SKU, 로트)별 합계 0, 조정 사유 코드 필수. */
    public void validate() {
        Map<SkuLotKey, Integer> sums = new LinkedHashMap<>();
        for (ResolvedLine entry : entries) {
            sums.merge(new SkuLotKey(entry.skuId(), entry.lotId()), entry.qtyDelta(), Integer::sum);
        }
        boolean zeroSum = sums.values().stream().allMatch(sum -> sum == 0);
        if (!zeroSum) {
            throw new PostingException("NOT_ZERO_SUM", "(SKU, 로트)별 수량 합이 0이 아니다");
        }
        if ("ADJUSTMENT".equals(txnType) && reasonCode == null) {
            throw new PostingException("REASON_REQUIRED", "조정 거래에는 사유 코드가 필요하다");
        }
    }

    /** 물리 로케이션 id, 중복 제거 후 오름차순. location FOR SHARE 잠금 순서. */
    public List<LocationId> physicalLocationIds() {
        return entries.stream()
                .filter(e -> !e.virtual())
                .map(ResolvedLine::locationId)
                .distinct()
                .sorted()
                .toList();
    }

    /** 물리 로케이션의 (로케이션, SKU, 로트) 키, 중복 제거. stock_balance FOR UPDATE 대상. */
    public List<BalanceKey> physicalKeys() {
        return entries.stream()
                .filter(e -> !e.virtual())
                .map(ResolvedLine::key)
                .distinct()
                .toList();
    }

    /** 증가분이 있는 물리 로케이션 줄. 잠금 전에 0 수량 잔액 행을 미리 만드는 대상. */
    public List<ResolvedLine> positiveDeltaPhysicalLines() {
        return entries.stream()
                .filter(e -> !e.virtual() && e.qtyDelta() > 0)
                .sorted(Comparator.comparingLong((ResolvedLine e) -> e.locationId().value())
                        .thenComparingLong(e -> e.skuId().value())
                        .thenComparingLong(e -> e.lotId().value()))
                .toList();
    }

    private record SkuLotKey(SkuId skuId, LotId lotId) {
    }
}
