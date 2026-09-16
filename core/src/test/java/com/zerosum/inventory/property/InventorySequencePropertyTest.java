package com.zerosum.inventory.property;

import static org.assertj.core.api.Assertions.assertThat;

import com.zerosum.inventory.allocation.AllocateRequest;
import com.zerosum.inventory.allocation.AllocationGateway;
import com.zerosum.inventory.allocation.AllocationResult;
import com.zerosum.inventory.domain.AllocationException;
import com.zerosum.inventory.domain.Posted;
import com.zerosum.inventory.domain.PostingException;
import com.zerosum.inventory.domain.PostingOutcome;
import com.zerosum.inventory.domain.PreconditionFailed;
import com.zerosum.inventory.posting.PostingLineInput;
import com.zerosum.inventory.posting.PostingRequest;
import com.zerosum.inventory.posting.Preconditions;
import com.zerosum.inventory.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 속성 기반 시퀀스 테스트. docs/08-testing-roadmap.md:25가 지목한 마지막 미구현 테스트를 채운다.
 * 입고·이동·할당·출고·조정·역분개를 무작위로 섞은 시퀀스를 만들고, 매 연산 뒤 정합 검증 배치
 * (docs/06-events-reconciliation.md ①~⑤, {@link com.zerosum.inventory.repository.ReconciliationRepository})의
 * 쿼리가 0건인지 확인한다.
 *
 * <p>오라클은 둘이다. ① 불변식 오라클({@code assertReconciliationClean()})은 잔액·원장·할당·로케이션
 * 표시가 서로 어긋나지 않는지만 본다 — 잔액과 원장이 "같은 방향으로 함께" 틀리면 이 오라클은 통과해
 * 버린다. ② 모델 오라클은 테스트가 보낸 커맨드로부터 독립적으로 계산한 기대 재고(로케이션·SKU·로트별
 * on_hand_qty)를 실제 stock_balance와 양방향으로 전부 비교해 그 구멍을 잡는다. 거절된 연산은 모델을
 * 갱신하지 않으므로 ②는 애플리케이션의 롤백이 완전한지도 함께 검사한다.
 *
 * <p>생성기는 합법적으로 실패하는 연산(재고 없는 출고 등)도 만든다. 그 거절은 PostingException·
 * AllocationException의 {@code code()} 화이트리스트로만 받아들인다 — 목록 밖의 코드나 두 타입이 아닌
 * 예외(제약 위반 누출, {@code IdempotencyConflictException} 등)는 그대로 테스트 실패로 터뜨린다.
 * 전부 {@code catch (Exception e) {}}로 삼키면 이 테스트는 아무것도 검사하지 않게 되기 때문이다.
 *
 * <p>jqwik은 쓰지 않는다 — jqwik은 JUnit Platform의 별도 엔진이라 SpringExtension이 관여하지 않고,
 * {@code @Autowired} 필드가 널로 남으며 {@code @BeforeEach}도 실행되지 않는다(실측 확인). 대신 JUnit
 * Jupiter 위에서 시드 기반 {@link java.util.Random}으로 시퀀스를 생성한다 — 기본 시드는 고정 상수라
 * CI가 매번 같은 시퀀스를 돌리고, {@code -Dzerosum.property.seed=<n>}로 덮어쓸 수 있다.
 */
class InventorySequencePropertyTest extends AbstractIntegrationTest {

    @Autowired
    private AllocationGateway allocationGateway;

    // ── 연산 세계 (좁게 고정 — 넓히면 거절만 늘고 검사는 줄어든다) ──────────────────────
    private static final long DEFAULT_SEED = 20260915L;
    private static final String WAREHOUSE = "ICN01";
    private static final List<String> PHYSICAL_LOCATIONS = List.of("A-01-01-1", "A-01-01-2", "A-02-01-1", "RCV-01");
    private static final List<String> SKUS = List.of("SKU-300001", "SKU-200002");
    private static final List<SkuLot> SKU_LOTS = List.of(
            new SkuLot("SKU-300001", "DEFAULT"),
            new SkuLot("SKU-200002", "L20260901-A"),
            new SkuLot("SKU-200002", "L20260910-B"));
    // 기존 테스트(PostingFlowTest·IdempotencyTest·ConcurrencyTest·proposal 패키지 등)에서 실제로 쓰는 값만 쓴다.
    private static final List<String> REASON_CODES = List.of("LOST", "DAMAGED_IN_STORAGE", "CYCLE_COUNT", "DAMAGE");

    /** PostingException·AllocationException의 code() 중 "합법적 거절"로 허용하는 목록. */
    private static final Set<String> ALLOWED_REJECTION_CODES = Set.of(
            "INSUFFICIENT_STOCK", "NO_STOCK", "ORPHAN_CONSUME", "ALLOC_NOT_ACTIVE",
            "COUNT_IN_PROGRESS", "NOT_ZERO_SUM", "REASON_REQUIRED", "UNKNOWN_CODE");

    /** P1의 30회 반복 전체를 누적하는 통계 — 마지막 반복에서 한 번만 건강도를 단언한다. */
    private static final Stats P1_STATS = new Stats();

    private enum OpType { RECEIPT, MOVE, ADJUSTMENT, ALLOCATE, SHIPMENT, REVERSAL }

    private record SkuLot(String sku, String lot) {
    }

    /** 모델 오라클의 키. stock_balance의 자연키(로케이션·SKU·로트 코드)를 그대로 쓴다. */
    private record ModelKey(String location, String sku, String lot) {
    }

    /** 물리 로케이션 줄 하나가 on_hand_qty에 준 증감. 모델 갱신과 P2 보존 오라클이 공유해서 쓴다. */
    private record BalanceDelta(String location, String sku, String lot, int delta) {
    }

    /** 성공해서 아직 REVERSAL로 되돌리지 않은 거래 — REVERSAL 연산의 후보 풀. */
    private record ReversibleTxn(long txnId, List<PostingLineInput> lines) {
    }

    /** ALLOCATE가 실제로 예약한 잔액 행 — SHIPMENT 연산의 후보 풀. FEFO 결과를 DB에서 그대로 읽은 값이다. */
    private record AllocRecord(long allocationId, String orderLineRef, String location, String sku, String lot,
            int qty) {
    }

    private record OpRecord(OpType type, boolean success, String rejectionCode, List<BalanceDelta> deltas) {
        static OpRecord success(OpType type, List<BalanceDelta> deltas) {
            return new OpRecord(type, true, null, deltas);
        }

        static OpRecord rejected(OpType type, String code) {
            return new OpRecord(type, false, code, List.of());
        }
    }

    private record ThreadResult(Stats stats, long netQtyChange) {
    }

    // ── P1: 단일 스레드, 매 연산마다 오라클 ①②를 건다 ──────────────────────────────────
    // 시작점(반복 30회 × 연산 25개)이 11초 만에 끝나 2분 예산에 크게 못 미쳤다 — 반복 100회 ×
    // 연산 40개(4000 연산)로 늘렸다. 실측 수행 시간은 보고서 참고.

    @RepeatedTest(100)
    void p1_singleThreadedSequenceStaysReconciled(RepetitionInfo repetitionInfo) {
        long baseSeed = Long.getLong("zerosum.property.seed", DEFAULT_SEED);
        int repetition = repetitionInfo.getCurrentRepetition();
        int totalRepetitions = repetitionInfo.getTotalRepetitions();
        long seed = baseSeed + repetition;
        int opsPerSequence = 40;

        System.out.printf("[P1] rep=%d/%d seed=%d (baseSeed=%d, -Dzerosum.property.seed로 덮어쓸 수 있다)%n",
                repetition, totalRepetitions, seed, baseSeed);

        SequenceRun run = new SequenceRun(new Random(seed), "p1r" + repetition);
        Map<ModelKey, Integer> model = new HashMap<>();

        for (int i = 1; i <= opsPerSequence; i++) {
            String context = "[P1] baseSeed=%d rep=%d/%d seed=%d op=%d/%d"
                    .formatted(baseSeed, repetition, totalRepetitions, seed, i, opsPerSequence);
            OpRecord record = executeOperation(run, context);
            if (record.success()) {
                applyDeltas(model, record.deltas());
            }
            assertReconciliationClean();
            assertModelMatchesActual(model, context);
        }

        P1_STATS.mergeFrom(run.stats);
        if (repetition == totalRepetitions) {
            printAndAssertGeneratorHealth("P1", P1_STATS);
        }
    }

    // ── P2: 다중 스레드, 끝에서만 정합·보존 오라클을 건다 ───────────────────────────────
    // 시작점(8스레드 × 15연산)이 1.1초 만에 끝나 2분 예산에 크게 못 미쳤다 — 스레드당 연산을 50개로
    // 늘렸다(8×50=400연산). 실측 수행 시간은 보고서 참고.

    @Test
    void p2_concurrentSequencesPreserveReconciliationAndTotals() throws Exception {
        long baseSeed = Long.getLong("zerosum.property.seed", DEFAULT_SEED);
        int threadCount = 8;
        int opsPerThread = 50;
        System.out.printf("[P2] baseSeed=%d threads=%d opsPerThread=%d%n", baseSeed, threadCount, opsPerThread);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            CountDownLatch ready = new CountDownLatch(threadCount);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<ThreadResult>> futures = IntStream.range(0, threadCount)
                    .<Callable<ThreadResult>>mapToObj(t -> () -> {
                        ready.countDown();
                        start.await();
                        long seed = baseSeed + 1_000_000L + t;
                        SequenceRun run = new SequenceRun(new Random(seed), "p2t" + t);
                        long netQtyChange = 0;
                        for (int i = 1; i <= opsPerThread; i++) {
                            String context = "[P2] baseSeed=%d thread=%d seed=%d op=%d/%d"
                                    .formatted(baseSeed, t, seed, i, opsPerThread);
                            OpRecord record = executeOperation(run, context);
                            if (record.success()) {
                                for (BalanceDelta delta : record.deltas()) {
                                    netQtyChange += delta.delta();
                                }
                            }
                        }
                        return new ThreadResult(run.stats, netQtyChange);
                    })
                    .map(pool::submit)
                    .toList();

            assertThat(ready.await(10, TimeUnit.SECONDS)).as("모든 스레드가 준비될 때까지 대기").isTrue();
            start.countDown();

            List<ThreadResult> results = new ArrayList<>();
            for (Future<ThreadResult> future : futures) {
                results.add(future.get(120, TimeUnit.SECONDS));
            }

            // 오라클 ①: 인터리빙이 끝난 뒤 정합 검증 배치
            assertReconciliationClean();

            // 오라클 ②(P2용 — 보존): 인터리빙과 무관하게 성립하는 유일한 불변식이다.
            long expectedTotal = results.stream().mapToLong(ThreadResult::netQtyChange).sum();
            int actualTotal = actualTotalOnHand();
            assertThat((long) actualTotal)
                    .as("보존 오라클: 성공한 연산들의 순증감 합계(%d)와 실제 창고 전체 on_hand_qty 총합", expectedTotal)
                    .isEqualTo(expectedTotal);

            Stats merged = Stats.mergeAll(results.stream().map(ThreadResult::stats).toList());
            printAndAssertGeneratorHealth("P2", merged);
        } finally {
            pool.shutdown();
        }
    }

    // ── 연산 실행 — 화이트리스트 밖 예외는 그대로 터뜨린다 ───────────────────────────────

    private OpRecord executeOperation(SequenceRun run, String context) {
        OpType type = chooseType(run);
        run.stats.recordAttempt(type);
        try {
            OpRecord record = switch (type) {
                case RECEIPT -> doReceipt(run);
                case MOVE -> doMove(run);
                case ADJUSTMENT -> doAdjustment(run);
                case ALLOCATE -> doAllocate(run);
                case SHIPMENT -> doShipment(run);
                case REVERSAL -> doReversal(run);
            };
            if (record.success()) {
                run.stats.recordSuccess(type);
            } else {
                run.stats.recordRejection(record.rejectionCode());
            }
            return record;
        } catch (PostingException e) {
            return rejectOrThrow(run, type, e.code(), e, context);
        } catch (AllocationException e) {
            return rejectOrThrow(run, type, e.code(), e, context);
        } catch (RuntimeException e) {
            throw new AssertionError("%s 실행 중 허용되지 않은 예외(연산=%s, 내용=%s): %s"
                    .formatted(context, type, run.lastAttemptDescription, e), e);
        }
    }

    private OpRecord rejectOrThrow(SequenceRun run, OpType type, String code, RuntimeException e, String context) {
        if (!ALLOWED_REJECTION_CODES.contains(code)) {
            throw new AssertionError("%s: 화이트리스트에 없는 거절 코드 %s(연산=%s, 내용=%s) 원본 메시지=%s"
                    .formatted(context, code, type, run.lastAttemptDescription, e.getMessage()), e);
        }
        run.stats.recordRejection(code);
        return OpRecord.rejected(type, code);
    }

    /** SHIPMENT/REVERSAL은 후보 풀이 비어 있으면 뽑을 수 없다 — 최대 10회 다시 뽑고, 그래도 없으면 후보를 만드는 연산으로 대체한다. */
    private OpType chooseType(SequenceRun run) {
        OpType type = randomOpType(run.rnd);
        int guard = 0;
        while (needsFallback(run, type) && guard < 10) {
            type = randomOpType(run.rnd);
            guard++;
        }
        if (type == OpType.SHIPMENT && run.unconsumedAllocations.isEmpty()) {
            type = OpType.ALLOCATE;
        } else if (type == OpType.REVERSAL && run.reversiblePool.isEmpty()) {
            type = OpType.RECEIPT;
        }
        return type;
    }

    private static boolean needsFallback(SequenceRun run, OpType type) {
        return (type == OpType.SHIPMENT && run.unconsumedAllocations.isEmpty())
                || (type == OpType.REVERSAL && run.reversiblePool.isEmpty());
    }

    private static OpType randomOpType(Random rnd) {
        OpType[] values = OpType.values();
        return values[rnd.nextInt(values.length)];
    }

    // ── 여섯 연산 ────────────────────────────────────────────────────────────────────

    private OpRecord doReceipt(SequenceRun run) {
        String loc = pick(run.rnd, PHYSICAL_LOCATIONS);
        SkuLot sl = pick(run.rnd, SKU_LOTS);
        int qty = 1 + run.rnd.nextInt(50);
        List<PostingLineInput> lines = List.of(
                line(WAREHOUSE, "V-SUPPLIER", sl.sku(), sl.lot(), -qty),
                line(WAREHOUSE, loc, sl.sku(), sl.lot(), qty));
        String idemKey = run.nextIdemKey("receipt");
        run.lastAttemptDescription = "RECEIPT idemKey=%s loc=%s sku=%s lot=%s qty=%d"
                .formatted(idemKey, loc, sl.sku(), sl.lot(), qty);
        PostingRequest req = request(idemKey, "RECEIPT", null, null, lines.toArray(PostingLineInput[]::new));
        return submitPosting(run, OpType.RECEIPT, req, lines, true);
    }

    private OpRecord doMove(SequenceRun run) {
        String from;
        SkuLot sl;
        int qty;
        // 네 번에 세 번은 재고가 있는 잔액 행에서 출발지를 고른다 (이유는 pickStockedKey 주석 참고).
        StockedKey stocked = run.rnd.nextInt(4) == 0 ? null : pickStockedKey(run);
        if (stocked != null) {
            from = stocked.location();
            sl = new SkuLot(stocked.sku(), stocked.lot());
            qty = 1 + run.rnd.nextInt(Math.min(30, stocked.qty()));
        } else {
            from = pick(run.rnd, PHYSICAL_LOCATIONS);
            sl = pick(run.rnd, SKU_LOTS);
            qty = 1 + run.rnd.nextInt(30);
        }
        String to;
        do {
            to = pick(run.rnd, PHYSICAL_LOCATIONS);
        } while (to.equals(from));
        List<PostingLineInput> lines = List.of(
                line(WAREHOUSE, from, sl.sku(), sl.lot(), -qty),
                line(WAREHOUSE, to, sl.sku(), sl.lot(), qty));
        String idemKey = run.nextIdemKey("move");
        run.lastAttemptDescription = "MOVE idemKey=%s from=%s to=%s sku=%s lot=%s qty=%d"
                .formatted(idemKey, from, to, sl.sku(), sl.lot(), qty);
        PostingRequest req = request(idemKey, "MOVE", null, null, lines.toArray(PostingLineInput[]::new));
        return submitPosting(run, OpType.MOVE, req, lines, true);
    }

    private OpRecord doAdjustment(SequenceRun run) {
        String loc = pick(run.rnd, PHYSICAL_LOCATIONS);
        SkuLot sl = pick(run.rnd, SKU_LOTS);
        int qty = 1 + run.rnd.nextInt(20);
        boolean increase = run.rnd.nextBoolean();
        String reason = pick(run.rnd, REASON_CODES);
        List<PostingLineInput> lines = increase
                ? List.of(line(WAREHOUSE, loc, sl.sku(), sl.lot(), qty),
                        line(WAREHOUSE, "V-ADJUST", sl.sku(), sl.lot(), -qty))
                : List.of(line(WAREHOUSE, loc, sl.sku(), sl.lot(), -qty),
                        line(WAREHOUSE, "V-ADJUST", sl.sku(), sl.lot(), qty));
        String idemKey = run.nextIdemKey("adjust");
        run.lastAttemptDescription = "ADJUSTMENT idemKey=%s loc=%s sku=%s lot=%s qty=%s reason=%s"
                .formatted(idemKey, loc, sl.sku(), sl.lot(), increase ? "+" + qty : "-" + qty, reason);
        PostingRequest req = request(idemKey, "ADJUSTMENT", reason, null, lines.toArray(PostingLineInput[]::new));
        return submitPosting(run, OpType.ADJUSTMENT, req, lines, true);
    }

    private OpRecord doAllocate(SequenceRun run) {
        String sku = pick(run.rnd, SKUS);
        int qty = 1 + run.rnd.nextInt(20);
        boolean allowInCount = run.rnd.nextBoolean();
        String idemKey = run.nextIdemKey("alloc");
        run.lastAttemptDescription = "ALLOCATE idemKey=%s sku=%s qty=%d allowInCount=%s"
                .formatted(idemKey, sku, qty, allowInCount);
        AllocationResult result = allocationGateway.allocate(
                new AllocateRequest(idemKey, idemKey, WAREHOUSE, sku, qty, allowInCount));
        // FEFO를 여기서 다시 구현하지 않는다 — 서버가 실제로 예약한 잔액 행을 DB에서 그대로 읽는다.
        List<AllocRecord> created = result.allocationIds().stream()
                .map(id -> queryAllocation(id.value()))
                .toList();
        run.unconsumedAllocations.addAll(created);
        return OpRecord.success(OpType.ALLOCATE, List.of()); // 할당은 on_hand_qty를 바꾸지 않는다
    }

    private OpRecord doShipment(SequenceRun run) {
        int idx = run.rnd.nextInt(run.unconsumedAllocations.size());
        AllocRecord alloc = run.unconsumedAllocations.remove(idx);
        List<PostingLineInput> lines = List.of(
                line(WAREHOUSE, alloc.location(), alloc.sku(), alloc.lot(), -alloc.qty()),
                line(WAREHOUSE, "V-CUSTOMER", alloc.sku(), alloc.lot(), alloc.qty()));
        String idemKey = run.nextIdemKey("ship");
        run.lastAttemptDescription = "SHIPMENT idemKey=%s allocationId=%d loc=%s sku=%s lot=%s qty=%d"
                .formatted(idemKey, alloc.allocationId(), alloc.location(), alloc.sku(), alloc.lot(), alloc.qty());
        // sourceRef는 이 예약이 걸린 주문 줄이다 — 멱등 키가 아니다. 서버가 소진 대상 할당의 주문 줄과
        // 대조하므로(ALLOC_ORDER_MISMATCH) 어긋나면 거절된다.
        PostingRequest req = new PostingRequest(idemKey, "SHIPMENT", "USER", "user:test", lines, "ORDER",
                alloc.orderLineRef(), null, null, Instant.now(), List.of(alloc.allocationId()));
        return submitPosting(run, OpType.SHIPMENT, req, lines, true);
    }

    private OpRecord doReversal(SequenceRun run) {
        int idx = run.rnd.nextInt(run.reversiblePool.size());
        ReversibleTxn original = run.reversiblePool.remove(idx);
        List<PostingLineInput> reversedLines = original.lines().stream()
                .map(l -> line(l.warehouseCode(), l.locationCode(), l.skuCode(), l.lotNo(), -l.qty()))
                .toList();
        String idemKey = run.nextIdemKey("reverse");
        run.lastAttemptDescription = "REVERSAL idemKey=%s originalTxnId=%d reversedLines=%s"
                .formatted(idemKey, original.txnId(), reversedLines);
        PostingRequest req = request(idemKey, "REVERSAL", null, original.txnId(),
                reversedLines.toArray(PostingLineInput[]::new));
        // 역분개 성공분은 되돌릴 원본이 아니므로 다시 reversiblePool에 넣지 않는다 (이중 역분개는 이 테스트의 범위 밖).
        return submitPosting(run, OpType.REVERSAL, req, reversedLines, false);
    }

    /** postingGateway.post 호출 공통부. 성공하면 필요 시 REVERSAL 후보 풀에 등록하고 물리 줄 증감을 돌려준다. */
    private OpRecord submitPosting(SequenceRun run, OpType type, PostingRequest req, List<PostingLineInput> allLines,
            boolean trackForReversal) {
        PostingOutcome outcome = postingGateway.post(req, Preconditions.none());
        return switch (outcome) {
            case Posted posted -> {
                if (trackForReversal) {
                    run.reversiblePool.add(new ReversibleTxn(posted.txnId(), allLines));
                }
                yield OpRecord.success(type, physicalDeltas(allLines));
            }
            // Preconditions.none()의 predicate는 항상 true라 실전에는 도달하지 않지만, PostingOutcome이
            // sealed interface로 강제하는 계약이자 작업 지시 요구사항이라 거절로 명시적으로 처리해 둔다.
            case PreconditionFailed ignored -> OpRecord.rejected(type, "PRECONDITION_FAILED");
        };
    }

    private static List<BalanceDelta> physicalDeltas(List<PostingLineInput> lines) {
        return lines.stream()
                .filter(l -> PHYSICAL_LOCATIONS.contains(l.locationCode()))
                .map(l -> new BalanceDelta(l.locationCode(), l.skuCode(), l.lotNo(), l.qty()))
                .toList();
    }

    private static void applyDeltas(Map<ModelKey, Integer> model, List<BalanceDelta> deltas) {
        for (BalanceDelta d : deltas) {
            model.merge(new ModelKey(d.location(), d.sku(), d.lot()), d.delta(), Integer::sum);
        }
    }

    private record StockedKey(String location, String sku, String lot, int qty) {
    }

    /**
     * 재고가 있는 잔액 행 하나를 고른다. 없으면 널.
     *
     * <p>MOVE의 출발지를 12개 키(로케이션 4 × SKU·로트 3)에서 눈감고 고르면 대부분 빈 키를 집어
     * NO_STOCK으로 거절된다 — 실측 성공률이 시드 두 개에서 각각 11%·16%였고, 시퀀스 40연산당 성사된
     * 이동이 1.2개꼴이었다. 그러면 "이동한 자리에서 출고", "이동을 역분개" 같은 조합이 사실상 돌지 않아
     * 무작위로 섞는 의미가 옅어진다. 네 번에 한 번은 그대로 눈감고 골라 거절 경로도 계속 밟는다.
     *
     * <p>실제 상태를 읽는 것은 <b>생성기</b>지 오라클이 아니다 — 모델 오라클은 여전히 테스트가 발행한
     * 커맨드로부터만 기대 재고를 계산하므로 독립성은 그대로다. 시드 재현성을 위해 정렬된 목록에서
     * {@code run.rnd}로 뽑는다 ({@code ORDER BY random()}을 쓰면 시드가 무의미해진다).
     */
    private StockedKey pickStockedKey(SequenceRun run) {
        List<StockedKey> rows = jdbcClient.sql("""
                SELECT l.code AS location, s.code AS sku, lo.lot_no AS lot, b.on_hand_qty AS qty
                FROM stock_balance b
                JOIN location l ON l.id = b.location_id
                JOIN sku s ON s.id = b.sku_id
                JOIN lot lo ON lo.id = b.lot_id
                JOIN warehouse w ON w.id = b.warehouse_id
                WHERE w.code = :wh AND b.on_hand_qty > 0
                ORDER BY l.code, s.code, lo.lot_no
                """)
                .param("wh", WAREHOUSE)
                .query((rs, rowNum) -> new StockedKey(rs.getString("location"), rs.getString("sku"),
                        rs.getString("lot"), rs.getInt("qty")))
                .list();
        return rows.isEmpty() ? null : rows.get(run.rnd.nextInt(rows.size()));
    }

    private static <T> T pick(Random rnd, List<T> values) {
        return values.get(rnd.nextInt(values.size()));
    }

    // ── 조회 헬퍼 ────────────────────────────────────────────────────────────────────

    /** ALLOCATE가 실제로 예약한 잔액 행(로케이션·SKU·로트·수량)을 DB에서 읽는다 — FEFO 결과를 그대로 신뢰한다. */
    private AllocRecord queryAllocation(long allocationId) {
        record Row(String orderLineRef, String location, String sku, String lot, int qty) {
        }
        Row row = jdbcClient.sql("""
                SELECT a.order_line_ref AS order_line_ref, l.code AS location, s.code AS sku, lo.lot_no AS lot,
                       a.qty AS qty
                FROM allocation a
                JOIN stock_balance b ON b.id = a.balance_id
                JOIN location l ON l.id = b.location_id
                JOIN sku s ON s.id = b.sku_id
                JOIN lot lo ON lo.id = b.lot_id
                WHERE a.id = :id
                """)
                .param("id", allocationId)
                .query((rs, rowNum) -> new Row(rs.getString("order_line_ref"), rs.getString("location"),
                        rs.getString("sku"), rs.getString("lot"), rs.getInt("qty")))
                .single();
        return new AllocRecord(allocationId, row.orderLineRef(), row.location(), row.sku(), row.lot(), row.qty());
    }

    /** 모델 오라클 ②: 이 창고의 실제 stock_balance 전부를 모델과 양방향으로 비교한다. */
    private void assertModelMatchesActual(Map<ModelKey, Integer> model, String context) {
        record Row(String location, String sku, String lot, int qty) {
        }
        List<Row> rows = jdbcClient.sql("""
                SELECT l.code AS location, s.code AS sku, lo.lot_no AS lot, b.on_hand_qty AS qty
                FROM stock_balance b
                JOIN location l ON l.id = b.location_id
                JOIN sku s ON s.id = b.sku_id
                JOIN lot lo ON lo.id = b.lot_id
                JOIN warehouse w ON w.id = b.warehouse_id
                WHERE w.code = :wh
                """)
                .param("wh", WAREHOUSE)
                .query((rs, rowNum) -> new Row(rs.getString("location"), rs.getString("sku"), rs.getString("lot"),
                        rs.getInt("qty")))
                .list();

        Map<ModelKey, Integer> actual = new HashMap<>();
        for (Row row : rows) {
            actual.put(new ModelKey(row.location(), row.sku(), row.lot()), row.qty());
        }
        assertThat(actual).as("%s: 모델 오라클(기대 재고) 불일치 — 모델에만 있거나 DB에만 있는 키도 실패다", context)
                .isEqualTo(model);
    }

    private int actualTotalOnHand() {
        return jdbcClient.sql("""
                SELECT COALESCE(SUM(b.on_hand_qty), 0) FROM stock_balance b
                JOIN warehouse w ON w.id = b.warehouse_id WHERE w.code = :wh
                """)
                .param("wh", WAREHOUSE)
                .query(Integer.class)
                .single();
    }

    // ── 생성기 건강도 ────────────────────────────────────────────────────────────────

    private static void printAndAssertGeneratorHealth(String label, Stats stats) {
        stats.print(label);
        int totalAttempts = stats.totalAttempts();
        int totalSuccesses = stats.totalSuccesses();
        assertThat(totalAttempts).as("%s 전체 시도 수", label).isPositive();

        double successRate = (double) totalSuccesses / totalAttempts;
        assertThat(successRate)
                .as("%s 전체 성공률(40%% 미만이면 생성기가 사실상 아무것도 검사하지 못한 것)", label)
                .isGreaterThanOrEqualTo(0.40);

        // 연산별 성공률까지 본다. "최소 1회 성공"만으로는 약하다 — 실제로 MOVE가 출발지를 눈감고 골라
        // 성공률 11%에 머문 적이 있었고(시퀀스 40연산당 성사된 이동 1.2개꼴), 그 가드는 그것을 통과시켰다.
        // 여섯 중 하나가 사실상 안 도는 상태는 무작위로 섞는다는 이 테스트의 전제가 깨진 것이다.
        for (OpType type : OpType.values()) {
            int attempts = stats.attemptCount(type);
            assertThat(attempts).as("%s %s 연산 시도 수", label, type).isPositive();
            double rate = (double) stats.successCount(type) / attempts;
            assertThat(rate)
                    .as("%s %s 연산 성공률(시도=%d 성공=%d) — 25%% 미만이면 그 연산은 사실상 안 돈 것이다",
                            label, type, attempts, stats.successCount(type))
                    .isGreaterThanOrEqualTo(0.25);
        }
    }

    // ── 시퀀스 하나(P1의 한 반복, 또는 P2의 한 스레드)의 가변 상태 ──────────────────────

    private static final class SequenceRun {
        final Random rnd;
        final String label;
        final Stats stats = new Stats();
        final List<ReversibleTxn> reversiblePool = new ArrayList<>();
        final List<AllocRecord> unconsumedAllocations = new ArrayList<>();
        private long opCounter = 0;
        /** 실패 시 메시지에 담을, 가장 최근에 시도한 연산의 내용. */
        String lastAttemptDescription = "(아직 없음)";

        SequenceRun(Random rnd, String label) {
            this.rnd = rnd;
            this.label = label;
        }

        /** 연산 일련번호를 넣어 멱등 키를 매번 유일하게 만든다. */
        String nextIdemKey(String opName) {
            opCounter++;
            return "prop:%s:%s:%d".formatted(label, opName, opCounter);
        }
    }

    // ── 연산 종류별 시도/성공/거절 코드 집계 ────────────────────────────────────────────

    private static final class Stats {
        private final Map<OpType, Integer> attempts = new EnumMap<>(OpType.class);
        private final Map<OpType, Integer> successes = new EnumMap<>(OpType.class);
        private final Map<String, Integer> rejections = new TreeMap<>();

        Stats() {
            for (OpType type : OpType.values()) {
                attempts.put(type, 0);
                successes.put(type, 0);
            }
        }

        void recordAttempt(OpType type) {
            attempts.merge(type, 1, Integer::sum);
        }

        void recordSuccess(OpType type) {
            successes.merge(type, 1, Integer::sum);
        }

        void recordRejection(String code) {
            rejections.merge(code, 1, Integer::sum);
        }

        int successCount(OpType type) {
            return successes.get(type);
        }

        int attemptCount(OpType type) {
            return attempts.get(type);
        }

        int totalAttempts() {
            return attempts.values().stream().mapToInt(Integer::intValue).sum();
        }

        int totalSuccesses() {
            return successes.values().stream().mapToInt(Integer::intValue).sum();
        }

        void mergeFrom(Stats other) {
            other.attempts.forEach((k, v) -> attempts.merge(k, v, Integer::sum));
            other.successes.forEach((k, v) -> successes.merge(k, v, Integer::sum));
            other.rejections.forEach((k, v) -> rejections.merge(k, v, Integer::sum));
        }

        static Stats mergeAll(List<Stats> all) {
            Stats merged = new Stats();
            for (Stats s : all) {
                merged.mergeFrom(s);
            }
            return merged;
        }

        void print(String label) {
            System.out.println("[" + label + "] 연산 종류별 시도/성공:");
            for (OpType type : OpType.values()) {
                System.out.printf("  %-10s 시도=%d 성공=%d%n", type, attempts.get(type), successes.get(type));
            }
            System.out.println("[" + label + "] 거절 코드별 횟수: " + (rejections.isEmpty() ? "없음" : rejections));
            int totalAttempts = totalAttempts();
            int totalSuccesses = totalSuccesses();
            double rate = totalAttempts == 0 ? 0 : (100.0 * totalSuccesses / totalAttempts);
            System.out.printf("[%s] 전체 시도=%d 성공=%d (%.1f%%)%n", label, totalAttempts, totalSuccesses, rate);
        }
    }
}
