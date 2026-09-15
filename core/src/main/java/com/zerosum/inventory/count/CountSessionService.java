package com.zerosum.inventory.count;

import com.zerosum.inventory.domain.IdempotencyConflictException;
import com.zerosum.inventory.domain.Posted;
import com.zerosum.inventory.domain.PostingOutcome;
import com.zerosum.inventory.domain.PreconditionFailed;
import com.zerosum.inventory.master.Location;
import com.zerosum.inventory.master.LocationRepository;
import com.zerosum.inventory.master.Lot;
import com.zerosum.inventory.master.LotRepository;
import com.zerosum.inventory.master.Sku;
import com.zerosum.inventory.master.SkuRepository;
import com.zerosum.inventory.posting.PostingLineInput;
import com.zerosum.inventory.posting.PostingRequest;
import com.zerosum.inventory.posting.PostingService;
import com.zerosum.inventory.posting.Preconditions;
import com.zerosum.inventory.repository.CountResultRepository;
import com.zerosum.inventory.repository.CountSessionRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실사 세션 생명주기(시작·제출·정정·중단). 단계와 잠금 순서는 docs/05-count-session.md와
 * db/04-harness.sql의 tst_count_start·tst_count_submit·tst_count_resolve·tst_count_abandon에 대응한다.
 * 데드락·직렬화 오류 재시도는 이 클래스 안이 아니라 {@link CountSessionGateway}가 트랜잭션 바깥에서 감싼다
 * (PostingService/PostingGateway와 같은 구조).
 *
 * <p>정정 단계의 조정 거래는 {@link PostingService}를 게이트웨이 없이 직접 호출한다 — 표시를 지운 UPDATE와
 * 같은 트랜잭션 안에서 실행돼야 하므로(05-count-session.md 정정 절차), PostingGateway를 거치면 이미 진행 중인
 * 트랜잭션 안에서 재시도 루프가 자기 호출되어 "롤백 전용으로 표시된 트랜잭션을 재시도"하는 문제가 생긴다
 * (docs/04-write-path.md 구현 메모: 재시도는 반드시 @Transactional 경계 바깥에서 감싼다).
 */
@Service
public class CountSessionService {

    private final LocationRepository locationRepository;
    private final SkuRepository skuRepository;
    private final LotRepository lotRepository;
    private final CountSessionRepository countSessionRepo;
    private final CountResultRepository countResultRepo;
    private final PostingService postingService;
    private final int tolQty;
    private final double tolPct;

    CountSessionService(
            LocationRepository locationRepository,
            SkuRepository skuRepository,
            LotRepository lotRepository,
            CountSessionRepository countSessionRepo,
            CountResultRepository countResultRepo,
            PostingService postingService,
            // 허용 오차는 기본값을 두지 않는다 — 운영 설정에서 오는 값이다 (05-count-session.md).
            // application.yml의 zerosum.count.tolerance.*가 출발값(차이 1개 이하이면서 5% 이하)을 담는다.
            @Value("${zerosum.count.tolerance.qty}") int tolQty,
            @Value("${zerosum.count.tolerance.pct}") double tolPct) {
        this.locationRepository = locationRepository;
        this.skuRepository = skuRepository;
        this.lotRepository = lotRepository;
        this.countSessionRepo = countSessionRepo;
        this.countResultRepo = countResultRepo;
        this.postingService = postingService;
        this.tolQty = tolQty;
        this.tolPct = tolPct;
    }

    /**
     * 실사 시작. 이 커맨드만 멱등 기록을 쓰지 않는다 — 멱등 키의 업무 식별자는 (창고, 로케이션)인데
     * "이 로케이션을 실사한다"는 포스팅(receipt:PO-123:1)과 달리 <b>반복되는 일</b>이라 대상이 곧
     * 유일한 키가 되지 못한다. 실제로 그랬을 때는 idempotency_record에 해제가 없어(I6) 첫 세션이 닫힌 뒤
     * 다시 시작해도 닫힌 세션 id가 그대로 재생됐다 — 한 로케이션을 평생 한 번만 실사할 수 있었다.
     *
     * <p>재시도의 재생은 상태가 대신한다. "이 로케이션에 열린 실사가 있다"는 이미
     * {@code location.count_session_id}가 들고 있고, 유일성은 부분 유니크 인덱스
     * {@code uq_count_session_active}(I10)가 DB에서 강제한다. 멱등 기록은 같은 사실을 한 겹 더 들고
     * 있었을 뿐이고, 그 중복이 버그였다.
     *
     * <p>로케이션 행을 FOR UPDATE로 잠그는 것이 동시 시작을 직렬화한다. 잠금 순서(location →
     * stock_balance, 04-write-path.md)는 그대로다 — 시작은 stock_balance를 건드리지 않고, 뒤따르는
     * markLocationCounting의 UPDATE가 어차피 같은 강도로 잠그던 행이라 잠금이 세지지도 않는다.
     */
    @Transactional
    public long start(StartCountRequest request) {
        Location location = locationRepository.findByWarehouse_CodeAndCode(request.warehouseCode(), request.locationCode())
                .orElseThrow(() -> new CountSessionException("UNKNOWN_CODE",
                        "창고·로케이션 코드 중 해석되지 않은 것이 있다: %s/%s"
                                .formatted(request.warehouseCode(), request.locationCode())));

        Long openSessionId = countSessionRepo.lockLocationForUpdate(location.getId());
        if (openSessionId != null) {
            // 이미 열린 실사가 있다 — 재시도의 재생에 해당하므로 그 세션 id를 그대로 돌려준다.
            // 표시가 닫힌 세션을 가리키는 상태는 I10 위반이라 정합 검증 배치 ⑤가 잡는다.
            return openSessionId;
        }

        // 세션 생성과 로케이션 표시를 한 트랜잭션에서 한다 (05-count-session.md 시작)
        long sessionId = countSessionRepo.insertSession(location.getId(), request.startedBy());
        int marked = countSessionRepo.markLocationCounting(location.getId(), sessionId);
        // 표시가 널인 것을 FOR UPDATE 아래에서 확인한 뒤이므로 1이 아니면 버그다 (정정·중단의 판정과 같다)
        requireExactlyOne(marked, sessionId);
        return sessionId;
    }

    @Transactional
    public CountSubmitOutcome submit(SubmitCountRequest request) {
        String requestHash = sha256Hex(request.sessionId() + "|" + request.lines().stream()
                .map(l -> l.skuCode() + "," + l.lotNo() + "," + l.countedQty())
                .collect(Collectors.joining(";")));

        boolean isNew = countSessionRepo.tryClaimIdempotencyKey(request.idemKey(), "COUNT_SUBMIT", requestHash);
        if (!isNew) {
            if (!countSessionRepo.storedRequestHash(request.idemKey()).equals(requestHash)) {
                throw new IdempotencyConflictException(request.idemKey());
            }
            CountSessionRepository.SubmitReplay replay = countSessionRepo.replaySubmitResult(request.idemKey());
            return "REVIEW".equals(replay.status()) ? new ReviewRequired() : new Confirmed(replay.resolutionTxnId());
        }

        // 세션을 FOR UPDATE로 잠그고 OPEN인지 확인한다 (05-count-session.md 제출)
        CountSessionRepository.LockedSession session = countSessionRepo.lockForUpdate(request.sessionId());
        if (!"OPEN".equals(session.status())) {
            throw new CountSessionException("COUNT_NOT_OPEN",
                    "세션 %d 상태 %s".formatted(request.sessionId(), session.status()));
        }

        Map<SkuLotKey, Integer> countedByKey = resolveCountedLines(request.lines());

        // 센 라인과 시스템 재고를 FULL JOIN: 라인에 없는 시스템 재고는 0개로 센 것으로 본다 (tst_count_submit과 동일)
        Map<SkuLotKey, Integer> systemByKey = new LinkedHashMap<>();
        for (CountResultRepository.SystemBalanceLine line : countResultRepo.systemBalances(session.locationId())) {
            systemByKey.put(new SkuLotKey(line.skuId(), line.lotId()), line.onHandQty());
        }
        Set<SkuLotKey> allKeys = new LinkedHashSet<>(systemByKey.keySet());
        allKeys.addAll(countedByKey.keySet());

        boolean anyOverTolerance = false;
        boolean anyDiff = false;
        for (SkuLotKey key : allKeys) {
            int systemQty = systemByKey.getOrDefault(key, 0);
            int countedQty = countedByKey.getOrDefault(key, 0);
            countResultRepo.insertResult(request.sessionId(), session.locationId(), key.skuId(), key.lotId(),
                    systemQty, countedQty, request.submittedBy());

            int diff = Math.abs(countedQty - systemQty);
            if (diff != 0) {
                anyDiff = true;
                // 출발값은 "차이 1개 이하이면서 5% 이하"(AND)이므로, 오차를 넘는 조건은 그 여집합인 OR이다
                if (diff > tolQty || diff > systemQty * tolPct) {
                    anyOverTolerance = true;
                }
            }
        }

        if (anyOverTolerance) {
            countSessionRepo.markReview(request.sessionId());
            countResultRepo.insertVarianceIssues(request.sessionId());
            countSessionRepo.completeSubmitReview(request.idemKey());
            return new ReviewRequired();
        }

        countSessionRepo.markSubmittedStillOpen(request.sessionId());
        Long resolutionTxnId;
        if (anyDiff) {
            // 오차 이내 차이는 같은 트랜잭션에서 바로 정정한다 — 정정 자체의 멱등 키는 제출 키에서 파생한다
            resolutionTxnId = claimAndResolve(request.idemKey() + ":resolve", request.sessionId(), request.submittedBy());
        } else {
            confirmWithoutVariance(request.sessionId(), session.locationId(), request.submittedBy());
            resolutionTxnId = null;
        }

        countSessionRepo.completeSubmitConfirmed(request.idemKey(), resolutionTxnId);
        return new Confirmed(resolutionTxnId);
    }

    @Transactional
    public Long resolve(ResolveCountRequest request) {
        return claimAndResolve(request.idemKey(), request.sessionId(), request.resolvedBy());
    }

    @Transactional
    public void abandon(AbandonCountRequest request) {
        String requestHash = sha256Hex(String.valueOf(request.sessionId()));

        boolean isNew = countSessionRepo.tryClaimIdempotencyKey(request.idemKey(), "COUNT_ABANDON", requestHash);
        if (!isNew) {
            if (!countSessionRepo.storedRequestHash(request.idemKey()).equals(requestHash)) {
                throw new IdempotencyConflictException(request.idemKey());
            }
            return;
        }

        CountSessionRepository.LockedSession session = countSessionRepo.lockForUpdate(request.sessionId());
        if (!("OPEN".equals(session.status()) || "REVIEW".equals(session.status()))) {
            throw new CountSessionException("COUNT_CLOSED",
                    "세션 %d 상태 %s".formatted(request.sessionId(), session.status()));
        }

        int cleared = countSessionRepo.clearLocationFlag(session.locationId(), request.sessionId());
        requireExactlyOne(cleared, request.sessionId());
        countSessionRepo.abandon(request.sessionId(), request.abandonedBy());
        countSessionRepo.completeAbandoned(request.idemKey());
    }

    // ── 정정 (제출의 자동 정정 경로와 공개 resolve()가 함께 쓴다) ─────────────────────────

    private Long claimAndResolve(String idemKey, long sessionId, String user) {
        String requestHash = sha256Hex(String.valueOf(sessionId));

        boolean isNew = countSessionRepo.tryClaimIdempotencyKey(idemKey, "COUNT_RESOLVE", requestHash);
        if (!isNew) {
            if (!countSessionRepo.storedRequestHash(idemKey).equals(requestHash)) {
                throw new IdempotencyConflictException(idemKey);
            }
            return countSessionRepo.replayResolvedResult(idemKey).resolutionTxnId();
        }

        Long txnId = resolveInternal(idemKey, sessionId, user);
        countSessionRepo.completeResolved(idemKey, txnId);
        return txnId;
    }

    /**
     * 정정 절차 본체. 세션을 잠그고, 표시를 먼저 지운 뒤, 같은 트랜잭션에서 조정 거래를 포스팅하고
     * 세션을 CONFIRMED로 바꾼다 (05-count-session.md 정정). 정정이 실패하면(예: 실재고가 할당량보다
     * 적어져 CHECK 위반) 표시를 지운 UPDATE까지 함께 롤백되어 로케이션은 계속 잠긴 채로 남는다 —
     * 이 메서드가 예외를 던지기만 하면 되고 별도 보상 처리는 필요 없다.
     */
    private Long resolveInternal(String idemKey, long sessionId, String user) {
        CountSessionRepository.LockedSession session = countSessionRepo.lockForUpdate(sessionId);
        boolean resolvable = ("OPEN".equals(session.status()) || "REVIEW".equals(session.status())) && session.submitted();
        if (!resolvable) {
            throw new CountSessionException("COUNT_NOT_SUBMITTED",
                    "세션 %d 상태 %s".formatted(sessionId, session.status()));
        }

        int cleared = countSessionRepo.clearLocationFlag(session.locationId(), sessionId);   // 표시를 먼저 지운다
        requireExactlyOne(cleared, sessionId);

        List<CountResultRepository.DiffLine> diffLines = countResultRepo.diffLines(sessionId);
        if (diffLines.isEmpty()) {
            countSessionRepo.confirm(sessionId, null, user);
            return null;
        }

        CountSessionRepository.WarehouseLocationCode codes = countSessionRepo.codesForLocation(session.locationId());
        List<PostingLineInput> lines = new ArrayList<>();
        for (CountResultRepository.DiffLine diff : diffLines) {
            lines.add(new PostingLineInput(codes.warehouseCode(), codes.locationCode(), diff.skuCode(), diff.lotNo(),
                    diff.diff()));
            lines.add(new PostingLineInput(codes.warehouseCode(), "V-ADJUST", diff.skuCode(), diff.lotNo(),
                    -diff.diff()));
        }
        PostingRequest adjustment = new PostingRequest(idemKey + ":post", "ADJUSTMENT", "USER", user, lines,
                "COUNT", String.valueOf(sessionId), "COUNT_VARIANCE", null, Instant.now());

        PostingOutcome outcome = postingService.post(adjustment, Preconditions.none());
        long txnId = switch (outcome) {
            case Posted posted -> posted.txnId();
            // Preconditions.none()은 항상 true이므로 실제로는 도달하지 않는다 — 스위치를 철저히 하기 위한 분기
            case PreconditionFailed ignored ->
                    throw new IllegalStateException("선행 조건 없는 포스팅에서 PreconditionFailed가 나왔다");
        };

        countSessionRepo.confirm(sessionId, txnId, user);
        countResultRepo.resolveVarianceIssues(sessionId, txnId, user);
        return txnId;
    }

    private void confirmWithoutVariance(long sessionId, long locationId, String user) {
        int cleared = countSessionRepo.clearLocationFlag(locationId, sessionId);
        requireExactlyOne(cleared, sessionId);
        countSessionRepo.confirm(sessionId, null, user);
    }

    private void requireExactlyOne(int affectedRows, long sessionId) {
        if (affectedRows != 1) {
            // 세션을 FOR UPDATE로 잠근 상태에서만 로케이션 표시를 바꾸므로 이 경로는 버그가 아니면 도달할 수 없다 (I10)
            throw new CountSessionException("COUNT_LOCATION_MISMATCH",
                    "세션 %d의 로케이션 표시가 예상과 다르다".formatted(sessionId));
        }
    }

    private Map<SkuLotKey, Integer> resolveCountedLines(List<CountLineInput> lines) {
        Map<SkuLotKey, Integer> countedByKey = new LinkedHashMap<>();
        for (CountLineInput line : lines) {
            if (line.countedQty() < 0) {
                throw new CountSessionException("NEGATIVE_COUNTED_QTY", "센 수량은 음수일 수 없다: %s".formatted(line));
            }
            Sku sku = skuRepository.findByCode(line.skuCode()).orElseThrow(() -> unknownLineCode(line));
            Lot lot = lotRepository.findBySku_IdAndLotNo(sku.getId(), line.lotNo()).orElseThrow(() -> unknownLineCode(line));
            SkuLotKey key = new SkuLotKey(sku.getId(), lot.getId());
            if (countedByKey.containsKey(key)) {
                throw new CountSessionException("DUPLICATE_COUNT_LINE", "같은 SKU·로트가 중복으로 제출됐다: %s".formatted(line));
            }
            countedByKey.put(key, line.countedQty());
        }
        return countedByKey;
    }

    private CountSessionException unknownLineCode(CountLineInput line) {
        return new CountSessionException("UNKNOWN_CODE", "SKU·로트 코드 중 해석되지 않은 것이 있다: %s".formatted(line));
    }

    private record SkuLotKey(long skuId, long lotId) {
    }

    // RequestHash(posting 패키지)·AllocationService의 sha256Hex와 같은 계산이지만, 이 서비스 자신의 입력을
    // 해시하므로 재사용하지 않고 그대로 옮겨 적었다 (AllocationService의 같은 판단 참고 — RequestHash는
    // posting 패키지 package-private이라 외부에서 재사용할 수도 없다).
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
