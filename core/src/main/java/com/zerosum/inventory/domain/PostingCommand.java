package com.zerosum.inventory.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 코드 해석과 검증을 마친 포스팅 커맨드. docs/04-write-path.md 포스팅 흐름의 {@code cmd}에 대응한다.
 * {@code consumeAllocationIds}는 출고(SHIPMENT)가 함께 소진할 할당 id다 (2단계 확장) — 그 외 거래 유형은 빈 리스트다.
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
        Instant occurredAt,
        List<Long> consumeAllocationIds,
        Long proposalId) {

    // 상대 줄 전용 가상 로케이션 코드 (db/03-seed.sql). 창고마다 하나씩 있고 코드는 창고 간에 겹친다.
    private static final String V_SUPPLIER = "V-SUPPLIER";
    private static final String V_CUSTOMER = "V-CUSTOMER";
    private static final String V_ADJUST = "V-ADJUST";

    /** ② 커맨드 검증: 줄이 하나 이상, (SKU, 로트)별 합계 0, 조정 사유 코드 필수, 거래 유형별 가상 로케이션 규칙. */
    public void validate() {
        // 줄이 하나도 없으면 아래 영합 검사가 공집합에 대해 참이 되어 통과하고, I3의 지연 제약 트리거는
        // AFTER INSERT ON inventory_ledger_entry ... FOR EACH ROW라 삽입된 줄이 없으면 발화조차 하지 않는다.
        // 그래서 원장 줄 없는 유령 거래가 남는데, 정합 검증 ①~⑤는 원장과 잔액을 대조하므로 그 존재를 모른다.
        // 더 나쁜 것은 업무 식별자로 만든 멱등 키를 영구히 태운다는 점이다 — 나중의 진짜 출고는 본문 해시가
        // 달라 409로 거절되고, ship:{주문라인}:{출고차수}는 다시 만들 수도 없는 키다.
        // AI 쓰기 표면에는 같은 검사가 이미 있었다 (ProposalCreationService의 ENTRIES_REQUIRED).
        if (entries.isEmpty()) {
            throw new PostingException("ENTRIES_REQUIRED", "줄이 하나도 없는 커맨드는 만들 거래가 없다");
        }

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
        validateVirtualLocationRule();
    }

    /**
     * 거래 유형별 가상 로케이션 규칙. 어느 가상 로케이션을 어느 방향으로 쓸 수 있는지는
     * docs/03-transactions.md의 표가 정본이고, 이 검사가 docs/04-write-path.md ②의 "가상 로케이션 규칙"이다.
     *
     * <p>이 규칙이 없을 때 뚫린 곳은 <b>부호</b>였다. 컨트롤러가 클라이언트 qty로 {@code -qty}/{@code +qty}
     * 줄을 만드는데 qty가 음수면 부호가 통째로 뒤집혀, txn_type=SHIPMENT·reason_code=NULL인 거래가 실재고를
     * 늘린다. 원장과 잔액은 완벽히 일치하므로 정합 검증 ①~⑤가 전부 0건이고 배치는 영원히 이것을 보지 못한다.
     * 실재고를 사유 없이 바꾸는 유일한 거래 유형이라 SUPERVISOR로 묶어 둔 ADJUSTMENT 통제가 그대로 열린다.
     * 컨트롤러에서 qty 부호를 막는 것과 별개로 코어가 스스로를 지켜야 한다 — :core는 웹 말고도 MCP·제안
     * 승인 경로에서 호출된다.
     *
     * <p><b>REVERSAL은 면제한다.</b> 역분개는 정의상 원거래의 반대 방향이라, 입고의 역분개는 이 규칙이
     * RECEIPT에 금지하는 바로 그 모양(V-SUPPLIER 양수 / 물리 음수)이 된다. 면제하지 않으면 역분개가 전부
     * 깨진다. 대신 <b>면제의 대가</b>를 적어 둔다: REVERSAL 전용 검증은 지금 아예 없다({@code reversesTxnId}는
     * 그냥 실려 갈 뿐이다). 그래서 txn_type=REVERSAL로 들어오면 어느 가상 로케이션이든 어느 방향으로든 쓸 수
     * 있고, 그 줄들이 정말로 원거래 줄의 부호 반대인지는 아무도 대조하지 않는다. 남는 방어선은 셋뿐이다 —
     * (SKU, 로트)별 합계 0, {@code reverses_txn_id} UNIQUE(같은 거래를 두 번 역분개할 수 없다),
     * 그리고 음수 재고 금지. 지금은 REVERSAL을 만드는 HTTP 창구가 없어(PostingController에 역분개
     * 엔드포인트가 없다) 노출이 테스트와 코어 호출자로 제한되지만, 역분개를 창구로 열 때는 "원거래 원장 줄을
     * 읽어 부호만 뒤집는다"를 커맨드를 만드는 쪽이 아니라 여기서 강제해야 한다.
     *
     * <p>참조 구현(db/04-harness.sql의 tst_post)에는 이 규칙도, 위 ENTRIES_REQUIRED도 없다. 하네스는
     * 스키마(제약·트리거·인덱스)가 지키는 것을 검증하는 독립 구현이고 이 둘은 스키마로 표현되지 않는
     * 커맨드 검증이라, 위 ORPHAN_CONSUME 때와 같은 이유로 Java 쪽만 의도적으로 더 엄격하게 둔다.
     */
    private void validateVirtualLocationRule() {
        if ("REVERSAL".equals(txnType)) {
            return;
        }
        VirtualRule rule = virtualRuleOf(txnType);
        for (ResolvedLine entry : entries) {
            if (entry.virtual()) {
                if (!entry.locationCode().equals(rule.allowedCode())) {
                    throw new PostingException("VIRTUAL_LOCATION_NOT_ALLOWED",
                            "%s 거래는 가상 로케이션 %s를 쓸 수 없다".formatted(txnType, entry.locationCode()));
                }
                if (rule.virtualSign() != null && Integer.signum(entry.qtyDelta()) != rule.virtualSign()) {
                    throw new PostingException("LINE_DIRECTION_MISMATCH",
                            "%s 거래의 %s 줄은 %s여야 한다: %d".formatted(txnType, entry.locationCode(),
                                    rule.virtualSign() > 0 ? "양수" : "음수", entry.qtyDelta()));
                }
            } else if (rule.virtualSign() != null && Integer.signum(entry.qtyDelta()) != -rule.virtualSign()) {
                throw new PostingException("LINE_DIRECTION_MISMATCH",
                        "%s 거래의 물리 줄 %s는 %s여야 한다: %d".formatted(txnType, entry.locationCode(),
                                rule.virtualSign() > 0 ? "음수" : "양수", entry.qtyDelta()));
            }
        }
    }

    /** 거래 유형이 쓸 수 있는 가상 로케이션과 그 줄의 부호. 둘 다 널이면 가상 로케이션 금지, 부호는 자유다. */
    private static VirtualRule virtualRuleOf(String txnType) {
        return switch (txnType) {
            case "RECEIPT" -> new VirtualRule(V_SUPPLIER, -1);
            case "SHIPMENT" -> new VirtualRule(V_CUSTOMER, 1);
            case "RETURN" -> new VirtualRule(V_CUSTOMER, -1);
            // 조정만 부호가 자유다 — 분실도 발견도 조정이다. 대신 사유 코드가 필수고(위 REASON_REQUIRED,
            // inventory_txn CHECK) 웹에서는 SUPERVISOR만 부를 수 있다.
            case "ADJUSTMENT" -> new VirtualRule(V_ADJUST, null);
            // MOVE·TRANSFER_OUT·TRANSFER_IN은 물리 로케이션 사이의 이동이라 가상 로케이션을 쓰지 않는다.
            // 표에 없는 유형(txn_type CHECK가 막는다)도 같은 취급이다 — 모르는 유형에 느슨한 쪽을 주지 않는다.
            default -> new VirtualRule(null, null);
        };
    }

    private record VirtualRule(String allowedCode, Integer virtualSign) {
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
