package com.zerosum.inventory.allocation;

import com.zerosum.inventory.domain.AllocationCandidate;
import com.zerosum.inventory.domain.AllocationException;
import com.zerosum.inventory.domain.AllocationId;
import com.zerosum.inventory.domain.IdempotencyConflictException;
import com.zerosum.inventory.domain.SkuId;
import com.zerosum.inventory.domain.WarehouseId;
import com.zerosum.inventory.master.SkuRepository;
import com.zerosum.inventory.master.WarehouseRepository;
import com.zerosum.inventory.repository.AllocationRepository;
import com.zerosum.inventory.repository.AllocationRepository.AllocationRow;
import com.zerosum.inventory.repository.OutboxRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 할당 서비스. FEFO로 잔액 행을 골라 할당하고, 할당을 해제한다.
 * 단계와 순서는 docs/04-write-path.md의 할당 흐름 의사 코드, db/04-harness.sql의 tst_allocate·
 * tst_release_alloc과 대응한다. 데드락·직렬화 오류 재시도는 이 클래스 안이 아니라
 * {@link AllocationGateway}가 트랜잭션 바깥에서 감싼다 (PostingService/PostingGateway와 같은 구조).
 */
@Service
public class AllocationService {

    // 유통기한 빠른 순, 기한 없는 로트는 뒤로, 동률은 잔액 행 id (docs/04-write-path.md 할당 흐름)
    private static final Comparator<AllocationCandidate> FEFO =
            Comparator.comparing(AllocationCandidate::expiryDate, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparingLong(c -> c.id().value());

    private final WarehouseRepository warehouseRepository;
    private final SkuRepository skuRepository;
    private final AllocationRepository allocationRepo;
    private final OutboxRepository outboxRepo;

    AllocationService(WarehouseRepository warehouseRepository, SkuRepository skuRepository,
            AllocationRepository allocationRepo, OutboxRepository outboxRepo) {
        this.warehouseRepository = warehouseRepository;
        this.skuRepository = skuRepository;
        this.allocationRepo = allocationRepo;
        this.outboxRepo = outboxRepo;
    }

    /**
     * 할당. 멱등 키는 호출자가 주지 않고 여기서 {@code allocate:{주문줄}:{회차}}로 파생한다.
     *
     * <p>키가 {@code allocate:{주문줄}}이던 동안은 <b>업무 식별자가 키가 되려면 그 일이 평생 한 번
     * 일어나야 한다</b>(docs/04-write-path.md)를 어긴 것이었다. "이 주문 줄을 할당한다"는 반복되는 일이다 —
     * 해제하고 다시 잡는 것을 docs/03·05가 정상 흐름으로 전제한다. 그래서 해제 뒤 재할당이 첫 할당의
     * 재생이 되어, 호출자는 200과 id 목록을 받지만 {@code allocated_qty}는 0이고 allocation 행은 RELEASED
     * 하나뿐이었다(조용한 초과 판매). I5는 양쪽 다 0이라 정합 검증 ②도 이것을 보지 못한다. 수량을 바꿔
     * 보내면 409라 그 주문 줄은 영구히 재할당 불가가 됐다. 같은 형태를 이미 두 번 고쳤다 —
     * {@code count:start:{창고}:{로케이션}}, {@code shipment:{주문줄}:{차수}}.
     *
     * <p>회차는 이 주문 줄에서 <b>이미 닫힌(ACTIVE가 하나도 없는) 할당 회차의 수</b>다
     * ({@link AllocationRepository#closedRoundCount}). 1차 때 0, 재시도 때도 0(아직 ACTIVE라), 해제·소진
     * 뒤엔 1이다. 실사 시작처럼 상태가 재현을 맡되, 실사와 달리 여기에는 직렬화할 단일 행이 없으므로
     * (할당은 아직 없다) 동시 중복 방지는 그대로 아래 {@code INSERT ... ON CONFLICT DO NOTHING}의 키
     * 선점이 맡는다. 동시에 들어온 둘은 커밋된 상태만 보므로 같은 회차를 계산하고, 진 쪽은 이긴 트랜잭션이
     * 끝날 때까지 대기했다가 저장된 결과를 재생한다. 회차 계산을 잠금으로 보호하지 않는 이유는 잠금 순서다 —
     * allocation을 stock_balance보다 먼저 잠그면 해제(stock_balance → allocation)와 순서가 역전되어
     * 데드락이 생긴다. 그 대가로 남는 창은 하나뿐이고 순차 호출에서는 닫혀 있다: 해제가 아직 커밋되지 않은
     * 순간에 같은 주문 줄의 할당이 겹쳐 들어오면 닫히는 중인 회차를 재생한다.
     */
    @Transactional
    public AllocationResult allocate(AllocateRequest request) {
        // 회차 키를 만들기 **전에** 막는다. qty가 0 이하면 배분 루프가 한 바퀴도 돌지 않아 allocation 행이
        // 0건인 채로 결과가 기록되는데, 회차는 "ACTIVE가 하나도 없는 idem_key"로 세므로 행이 아예 없는
        // 회차는 영원히 닫히지 않는다 — 그 주문 줄은 회차 0에 고정돼 이후 정상 할당이 매번 409가 된다.
        // 이 검증이 없앤 바로 그 상태(주문 줄이 영구히 재할당 불가)가 다른 문으로 되살아난다.
        // 코어가 막아야 한다 — :web 말고 MCP·제안 경로도 이 서비스를 부른다.
        if (request.qty() <= 0) {
            throw new AllocationException("NON_POSITIVE_QTY",
                    "할당 수량은 양수여야 한다: %d".formatted(request.qty()));
        }
        String idemKey = "allocate:%s:%d".formatted(
                request.orderLineRef(), allocationRepo.closedRoundCount(request.orderLineRef()));
        String requestHash = sha256Hex(request.orderLineRef() + "|" + request.warehouseCode() + "|"
                + request.skuCode() + "|" + request.qty());

        // ① 멱등 키 선점. 이미 처리된 키면 저장된 결과를 반환 (본문이 다르면 409)
        boolean isNew = allocationRepo.tryClaimIdempotencyKey(idemKey, "ALLOCATE", requestHash);
        if (!isNew) {
            if (!allocationRepo.storedRequestHash(idemKey).equals(requestHash)) {
                throw new IdempotencyConflictException(idemKey);
            }
            // 재생이 거짓말을 하지 않게 한다. 회차는 "ACTIVE가 하나도 없는 idem_key"로 세므로, 일부만
            // 해제·소진된 회차는 아직 열린 것으로 남는다 — 그대로 재생하면 RELEASED·CONSUMED가 섞인
            // id 목록을 200으로 돌려주게 되고, 호출자는 30개가 예약된 줄 알지만 실제 ACTIVE는 10개다.
            // 이 회차 방식이 없애려던 "조용한 초과 판매"의 부분 버전이고, allocated_qty와 ACTIVE 합계는
            // 서로 맞으므로 정합 검증 ②도 보지 못한다.
            //
            // 대가: 부분 소진 뒤에 도착한 정직한 재시도(네트워크 재전송 등)도 이 거절을 받는다. 틀린
            // 답을 200으로 주는 것보다 낫다고 보고 택했다 — 호출자는 상태를 다시 읽어 판단할 수 있다.
            if (allocationRepo.closedCountOf(idemKey) > 0) {
                throw new AllocationException("ALLOC_ROUND_PARTIALLY_CLOSED",
                        "이 회차의 예약 일부가 이미 해제·소진됐다 — 재생할 수 없다 (%s)".formatted(idemKey));
            }
            return new AllocationResult(allocationRepo.replayAllocationIds(idemKey));
        }

        WarehouseId warehouseId = new WarehouseId(warehouseRepository.findByCode(request.warehouseCode())
                .orElseThrow(() -> unknownCode(request)).getId());
        SkuId skuId = new SkuId(skuRepository.findByCode(request.skuCode())
                .orElseThrow(() -> unknownCode(request)).getId());

        // 후보는 잠금 없이 조회 → id 순서로 잠금 → 잠근 뒤 가용 수량을 다시 계산
        List<Long> candidateIds = allocationRepo.findCandidateBalanceIds(warehouseId, skuId, request.allowInCount());
        allocationRepo.lockForUpdate(candidateIds);
        List<AllocationCandidate> loaded = allocationRepo.loadForFefo(candidateIds);

        // FEFO 정렬은 잠금 획득 후 메모리에서 (잠금 순서와 비즈니스 순서를 분리)
        List<AllocationCandidate> ordered = loaded.stream()
                .filter(c -> c.availableQty() > 0)
                .sorted(FEFO)
                .toList();

        List<AllocationId> allocationIds = new ArrayList<>();
        int remaining = request.qty();
        for (AllocationCandidate candidate : ordered) {
            if (remaining <= 0) {
                break;
            }
            int take = Math.min(remaining, candidate.availableQty());
            allocationRepo.incrementAllocated(candidate.id(), take);
            allocationIds.add(allocationRepo.insertAllocation(
                    idemKey, request.orderLineRef(), candidate.id(), take));
            remaining -= take;
        }
        if (remaining > 0) {
            throw new InsufficientStockException(request.skuCode(), request.qty(), remaining);
        }

        outboxRepo.appendStockAllocated(skuId, request.orderLineRef(), request.qty(), allocationIds);
        allocationRepo.completeAllocated(idemKey, allocationIds);
        return new AllocationResult(allocationIds);
    }

    /**
     * 할당 해제. 할당이 가리키는 잔액 행을 먼저 잠근 뒤, 영향 행 수 1을 확인하고 allocated_qty를 줄인다
     * (docs/04-write-path.md 할당 해제 규칙). 멱등 재현은 db/04-harness.sql의 tst_release_alloc처럼 같은 키의
     * 재요청에 매번 다시 실행하지 않고 0을 돌려준다는 점은 같지만, 본문(할당 id 목록)이 다르면 post/allocate와
     * 마찬가지로 409로 거절한다 — tst_release_alloc은 해시를 저장만 하고 비교하지 않는데, 같은 키에 다른
     * 할당 id가 오는 걸 조용히 무시하면 호출자가 실수로 다른 주문의 할당을 해제 요청하고도 아무 일도
     * 안 일어난 것으로 착각할 수 있어(검증 지적) post/allocate·1단계 RequestHash와 같은 수준으로 맞췄다.
     */
    @Transactional
    public int release(String idemKey, List<AllocationId> allocationIds) {
        String requestHash = sha256Hex(allocationIds.stream()
                .map(id -> String.valueOf(id.value()))
                .collect(Collectors.joining(",")));

        boolean isNew = allocationRepo.tryClaimIdempotencyKey(idemKey, "RELEASE_ALLOC", requestHash);
        if (!isNew) {
            if (!allocationRepo.storedRequestHash(idemKey).equals(requestHash)) {
                throw new IdempotencyConflictException(idemKey);
            }
            return 0;
        }

        List<Long> balanceIds = allocationRepo.distinctBalanceIds(allocationIds);
        allocationRepo.lockForUpdate(balanceIds);

        List<AllocationRow> rows = allocationRepo.findByIdsOrderById(allocationIds);
        for (AllocationRow row : rows) {
            allocationRepo.closeAsReleased(row.id());
            allocationRepo.decrementAllocated(row.balanceId(), row.qty());
        }
        return rows.size();
    }

    private AllocationException unknownCode(AllocateRequest request) {
        return new AllocationException("UNKNOWN_CODE",
                "창고·SKU 코드 중 해석되지 않은 것이 있다: %s".formatted(request));
    }

    // RequestHash(posting 패키지)와 같은 계산이지만, PostingRequest가 아닌 이 서비스 자신의 입력을 해시하므로
    // 재사용 대신 그대로 옮겨 적었다 (posting.RequestHash는 package-private이라 외부에서 재사용할 수도 없다).
    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
