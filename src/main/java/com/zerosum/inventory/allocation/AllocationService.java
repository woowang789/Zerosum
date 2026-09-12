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

    @Transactional
    public AllocationResult allocate(AllocateRequest request) {
        String requestHash = sha256Hex(request.orderLineRef() + "|" + request.warehouseCode() + "|"
                + request.skuCode() + "|" + request.qty());

        // ① 멱등 키 선점. 이미 처리된 키면 저장된 결과를 반환 (본문이 다르면 409)
        boolean isNew = allocationRepo.tryClaimIdempotencyKey(request.idemKey(), "ALLOCATE", requestHash);
        if (!isNew) {
            if (!allocationRepo.storedRequestHash(request.idemKey()).equals(requestHash)) {
                throw new IdempotencyConflictException(request.idemKey());
            }
            return new AllocationResult(allocationRepo.replayAllocationIds(request.idemKey()));
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
                    request.idemKey(), request.orderLineRef(), candidate.id(), take));
            remaining -= take;
        }
        if (remaining > 0) {
            throw new InsufficientStockException(request.skuCode(), request.qty(), remaining);
        }

        outboxRepo.appendStockAllocated(skuId, request.orderLineRef(), request.qty(), allocationIds);
        allocationRepo.completeAllocated(request.idemKey(), allocationIds);
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
