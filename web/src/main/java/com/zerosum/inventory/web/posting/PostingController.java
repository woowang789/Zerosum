package com.zerosum.inventory.web.posting;

import com.zerosum.inventory.domain.Posted;
import com.zerosum.inventory.domain.PostingOutcome;
import com.zerosum.inventory.posting.PostingGateway;
import com.zerosum.inventory.posting.PostingLineInput;
import com.zerosum.inventory.posting.PostingRequest;
import com.zerosum.inventory.posting.Preconditions;
import com.zerosum.inventory.web.error.InvalidRequestException;
import com.zerosum.inventory.web.security.AccessGuard;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 물리 재고를 바꾸는 포스팅 쓰기 창구(입고·출고·이동·조정). 이 컨트롤러가 지키는 것 넷:
 *
 * <ol>
 *   <li><b>멱등 키는 클라이언트가 보내지 않는다.</b> 발주라인·주문라인·이동/조정 참조 같은 업무 식별자를
 *       요청으로 받아 서버가 {@code "{거래유형}:{참조}[:차수]"} 꼴로 파생한다. 클라이언트가 무작위 키를
 *       보내면 재시도해도 매번 새 거래가 돼 멱등성이 무의미해진다.</li>
 *   <li><b>가상 로케이션은 클라이언트가 지정할 수 없다.</b> 물리 로케이션 코드에 {@code V-SUPPLIER}·
 *       {@code V-CUSTOMER}·{@code V-ADJUST}가 오면 {@link #requirePhysicalLocation}이 즉시 거절한다.
 *       상대 줄은 거래 유형에 따라 이 컨트롤러가 채운다 — 그러지 않으면 복식부기 규칙이 클라이언트
 *       손에 넘어간다.</li>
 *   <li><b>행위자는 {@link Authentication}에서만 읽는다.</b> 요청 DTO에 actorId 필드를 두지 않는다.</li>
 *   <li><b>입고·출고·이동의 수량은 양수다.</b> 상대 줄을 {@code -qty}/{@code +qty}로 만드는 이상 음수
 *       qty는 거래의 방향을 통째로 뒤집는다 ({@link #requirePositiveQty}).</li>
 * </ol>
 *
 * <p>창고 범위는 {@code ?warehouse=} 쿼리 파라미터가 아니라 요청 본문의 {@code warehouseCode}이므로
 * {@link com.zerosum.inventory.web.security.WarehouseScope}(쿼리 파라미터 전용)를 쓸 수 없다 —
 * {@link com.zerosum.inventory.web.issue.IssueController}의 {@code {id}} 엔드포인트와 같은 이유로
 * {@link AccessGuard#requireWarehouse}를 직접 부른다. 모든 물리 줄이 이 한 창고에서만 만들어지므로
 * (아래 각 메서드가 줄을 만들 때 항상 검증된 {@code warehouseCode} 하나만 쓴다) 줄 단위로 다시 검증할
 * 필요가 없다.
 *
 * <p>{@link Preconditions#none()}만 쓰므로 {@link PostingGateway#post}는 항상 {@link Posted}를 돌려준다
 * (아래 {@link #toResponse} 참고) — 선행 조건이 필요한 경로(제안 승인)는 이미 {@code ProposalController}가
 * 담당한다.
 */
@RestController
public class PostingController {

    // 상대 줄 전용 가상 로케이션 코드 (db/03-seed.sql). 창고마다 하나씩 있고 코드는 창고 간에 겹친다.
    private static final String V_SUPPLIER = "V-SUPPLIER";
    private static final String V_CUSTOMER = "V-CUSTOMER";
    private static final String V_ADJUST = "V-ADJUST";
    private static final Set<String> VIRTUAL_LOCATION_CODES = Set.of(V_SUPPLIER, V_CUSTOMER, V_ADJUST);

    private final PostingGateway postingGateway;

    PostingController(PostingGateway postingGateway) {
        this.postingGateway = postingGateway;
    }

    @PostMapping("/api/receipts")
    public PostingResultResponse receive(@RequestBody ReceiptRequest request, Authentication authentication) {
        AccessGuard.requireAnyRole(authentication, "OPERATOR", "SUPERVISOR");
        AccessGuard.requireWarehouse(authentication, request.warehouseCode());
        requirePhysicalLocation(request.locationCode());
        requirePositiveQty(request.qty());

        // 업무 식별자(발주라인:입고차수)에서 파생 — docs/04-write-path.md의 receipt:{발주라인}:{입고차수} 그대로.
        String idemKey = "receipt:%s:%d".formatted(request.poLineRef(), request.receiptSeq());
        List<PostingLineInput> lines = List.of(
                new PostingLineInput(request.warehouseCode(), request.locationCode(), request.skuCode(),
                        request.lotNo(), request.qty()),
                new PostingLineInput(request.warehouseCode(), V_SUPPLIER, request.skuCode(), request.lotNo(),
                        -request.qty()));

        PostingOutcome outcome = postingGateway.post(
                new PostingRequest(idemKey, "RECEIPT", "USER", authentication.getName(), lines, "PO",
                        request.poLineRef(), null, null, Instant.now(), List.of()),
                Preconditions.none());
        return toResponse(outcome);
    }

    @PostMapping("/api/shipments")
    public PostingResultResponse ship(@RequestBody ShipmentRequest request, Authentication authentication) {
        AccessGuard.requireAnyRole(authentication, "OPERATOR", "SUPERVISOR");
        AccessGuard.requireWarehouse(authentication, request.warehouseCode());
        request.lines().forEach(line -> {
            requirePhysicalLocation(line.locationCode());
            requirePositiveQty(line.qty());
        });

        // 업무 식별자(주문라인:출고차수)에서 파생 — docs/04-write-path.md의 ship:{주문라인}:{출고차수} 그대로.
        String idemKey = "shipment:%s:%d".formatted(request.orderLineRef(), request.shipmentSeq());

        // 물리 줄은 그대로(음수), V-CUSTOMER 상대 줄은 (SKU, 로트)별로 합쳐 하나씩 — 같은 주문라인이
        // FEFO로 여러 로트에서 나갈 수 있어 물리 줄이 여럿이어도 상대 줄은 로트마다 하나면 된다.
        List<PostingLineInput> lines = new ArrayList<>();
        Map<SkuLot, Integer> totalsBySkuLot = new LinkedHashMap<>();
        for (ShipmentLineInput line : request.lines()) {
            lines.add(new PostingLineInput(request.warehouseCode(), line.locationCode(), line.skuCode(),
                    line.lotNo(), -line.qty()));
            totalsBySkuLot.merge(new SkuLot(line.skuCode(), line.lotNo()), line.qty(), Integer::sum);
        }
        totalsBySkuLot.forEach((skuLot, qty) -> lines.add(
                new PostingLineInput(request.warehouseCode(), V_CUSTOMER, skuLot.skuCode(), skuLot.lotNo(), qty)));

        List<Long> consumeAllocationIds = request.consumeAllocationIds() == null
                ? List.of()
                : request.consumeAllocationIds();
        PostingOutcome outcome = postingGateway.post(
                new PostingRequest(idemKey, "SHIPMENT", "USER", authentication.getName(), lines, "ORDER",
                        request.orderLineRef(), null, null, Instant.now(), consumeAllocationIds),
                Preconditions.none());
        return toResponse(outcome);
    }

    @PostMapping("/api/moves")
    public PostingResultResponse move(@RequestBody MoveRequest request, Authentication authentication) {
        AccessGuard.requireAnyRole(authentication, "OPERATOR", "SUPERVISOR");
        AccessGuard.requireWarehouse(authentication, request.warehouseCode());
        requirePhysicalLocation(request.fromLocationCode());
        requirePhysicalLocation(request.toLocationCode());
        requirePositiveQty(request.qty());

        // 업무 식별자(이동 참조)에서 파생 — 이동은 발주·주문 같은 상위 문서가 없을 수 있어 클라이언트가
        // 준 이동 참조(예: WMS 이동 작업 번호) 하나로 충분하다.
        String idemKey = "move:%s".formatted(request.moveRef());
        List<PostingLineInput> lines = List.of(
                new PostingLineInput(request.warehouseCode(), request.fromLocationCode(), request.skuCode(),
                        request.lotNo(), -request.qty()),
                new PostingLineInput(request.warehouseCode(), request.toLocationCode(), request.skuCode(),
                        request.lotNo(), request.qty()));

        PostingOutcome outcome = postingGateway.post(
                new PostingRequest(idemKey, "MOVE", "USER", authentication.getName(), lines, "MOVE",
                        request.moveRef(), null, null, Instant.now(), List.of()),
                Preconditions.none());
        return toResponse(outcome);
    }

    @PostMapping("/api/adjustments")
    public PostingResultResponse adjust(@RequestBody AdjustmentRequest request, Authentication authentication) {
        // 조정만 SUPERVISOR 전용 — 실재고를 사유 없이 바꾸는 유일한 거래 유형이다.
        AccessGuard.requireAnyRole(authentication, "SUPERVISOR");
        AccessGuard.requireWarehouse(authentication, request.warehouseCode());
        requirePhysicalLocation(request.locationCode());

        // 업무 식별자(조정 참조)에서 파생. reasonCode는 여기서 비어 있어도 막지 않는다 — 코어의
        // PostingCommand#validate가 ADJUSTMENT + reasonCode 없음을 REASON_REQUIRED(409)로 거절한다.
        String idemKey = "adjustment:%s".formatted(request.adjustmentRef());
        List<PostingLineInput> lines = List.of(
                new PostingLineInput(request.warehouseCode(), request.locationCode(), request.skuCode(),
                        request.lotNo(), request.qty()),
                new PostingLineInput(request.warehouseCode(), V_ADJUST, request.skuCode(), request.lotNo(),
                        -request.qty()));

        PostingOutcome outcome = postingGateway.post(
                new PostingRequest(idemKey, "ADJUSTMENT", "USER", authentication.getName(), lines, "ADJUSTMENT",
                        request.adjustmentRef(), request.reasonCode(), null, Instant.now(), List.of()),
                Preconditions.none());
        return toResponse(outcome);
    }

    /**
     * 입고·출고·이동은 양수 수량만 받는다. 이 컨트롤러가 클라이언트 qty로 {@code -qty}/{@code +qty} 줄을
     * 만들기 때문에, qty가 음수면 부호가 통째로 뒤집혀 출고가 실재고를 늘리고 입고가 실재고를 없앤다.
     * 코어의 가상 로케이션 규칙({@code PostingCommand#validate})이 같은 것을 한 겹 더 막지만, 이것은 요청
     * 형태의 문제라 여기서 400으로 돌려준다. 조정에는 걸지 않는다 — 분실(음수)도 발견(양수)도 조정이고,
     * 코어도 ADJUSTMENT만 부호를 자유로 둔다.
     */
    private static void requirePositiveQty(int qty) {
        if (qty <= 0) {
            throw new InvalidRequestException("NON_POSITIVE_QTY", "수량은 양수여야 한다: %d".formatted(qty));
        }
    }

    private static void requirePhysicalLocation(String locationCode) {
        if (VIRTUAL_LOCATION_CODES.contains(locationCode)) {
            throw new InvalidRequestException("VIRTUAL_LOCATION_NOT_ALLOWED",
                    "물리 로케이션만 지정할 수 있다 (상대 줄은 서버가 채운다): %s".formatted(locationCode));
        }
    }

    private static PostingResultResponse toResponse(PostingOutcome outcome) {
        // Preconditions.none()은 항상 true이므로 PreconditionFailed는 나오지 않는다
        // (CountSessionService#resolveInternal의 같은 분기 참고).
        if (outcome instanceof Posted posted) {
            return new PostingResultResponse(posted.txnId());
        }
        throw new IllegalStateException("선행 조건 없는 포스팅에서 PreconditionFailed가 나왔다");
    }

    private record SkuLot(String skuCode, String lotNo) {
    }

    public record PostingResultResponse(long txnId) {
    }

    public record ReceiptRequest(String poLineRef, int receiptSeq, String warehouseCode, String locationCode,
            String skuCode, String lotNo, int qty) {
    }

    public record ShipmentRequest(String orderLineRef, int shipmentSeq, String warehouseCode,
            List<ShipmentLineInput> lines, List<Long> consumeAllocationIds) {
    }

    public record ShipmentLineInput(String locationCode, String skuCode, String lotNo, int qty) {
    }

    public record MoveRequest(String moveRef, String warehouseCode, String fromLocationCode, String toLocationCode,
            String skuCode, String lotNo, int qty) {
    }

    public record AdjustmentRequest(String adjustmentRef, String warehouseCode, String locationCode, String skuCode,
            String lotNo, int qty, String reasonCode) {
    }
}
