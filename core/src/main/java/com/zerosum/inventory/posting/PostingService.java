package com.zerosum.inventory.posting;

import com.zerosum.inventory.domain.LockedBalance;
import com.zerosum.inventory.domain.LockedBalances;
import com.zerosum.inventory.domain.LockedLocations;
import com.zerosum.inventory.domain.Posted;
import com.zerosum.inventory.domain.PostingCommand;
import com.zerosum.inventory.domain.PostingException;
import com.zerosum.inventory.domain.PostingOutcome;
import com.zerosum.inventory.domain.PreconditionFailed;
import com.zerosum.inventory.domain.ResolvedLine;
import com.zerosum.inventory.repository.AllocationRepository;
import com.zerosum.inventory.repository.IdempotencyRepository;
import com.zerosum.inventory.repository.InventoryTxnRepository;
import com.zerosum.inventory.repository.LedgerRepository;
import com.zerosum.inventory.repository.LocationLockRepository;
import com.zerosum.inventory.repository.OutboxRepository;
import com.zerosum.inventory.repository.StockBalanceRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 포스팅 서비스. 잔액과 원장을 바꾸는 유일한 쓰기 경로다 (docs/01-principles.md).
 * 단계와 순서는 docs/04-write-path.md의 포스팅 흐름 의사 코드, db/04-harness.sql의 tst_post와 대응한다.
 * 데드락·직렬화 오류 재시도는 이 클래스 안이 아니라 {@link PostingGateway}가 트랜잭션 바깥에서 감싼다.
 */
@Service
public class PostingService {

    private final IdempotencyRepository idempotencyRepo;
    private final PostingLineResolver lineResolver;
    private final LocationLockRepository locationLockRepo;
    private final StockBalanceRepository balanceRepo;
    private final InventoryTxnRepository txnRepo;
    private final LedgerRepository ledgerRepo;
    private final OutboxRepository outboxRepo;
    private final AllocationRepository allocationRepo;

    PostingService(IdempotencyRepository idempotencyRepo, PostingLineResolver lineResolver,
            LocationLockRepository locationLockRepo, StockBalanceRepository balanceRepo,
            InventoryTxnRepository txnRepo, LedgerRepository ledgerRepo, OutboxRepository outboxRepo,
            AllocationRepository allocationRepo) {
        this.idempotencyRepo = idempotencyRepo;
        this.lineResolver = lineResolver;
        this.locationLockRepo = locationLockRepo;
        this.balanceRepo = balanceRepo;
        this.txnRepo = txnRepo;
        this.ledgerRepo = ledgerRepo;
        this.outboxRepo = outboxRepo;
        this.allocationRepo = allocationRepo;
    }

    @Transactional
    public PostingOutcome post(PostingRequest request, Predicate<LockedBalances> precondition) {
        String requestHash = RequestHash.of(request);

        // ① 멱등 키 선점. 이미 처리된 키면 저장된 결과를 반환 (본문이 다르면 409)
        Optional<PostingOutcome> existing = idempotencyRepo.claim(request.idemKey(), request.txnType(), requestHash);
        if (existing.isPresent()) {
            return existing.get();
        }

        // ② 커맨드 검증: 코드 해석, (SKU, 로트)별 합계 0, 조정 사유 코드
        List<ResolvedLine> entries = request.lines().stream().map(lineResolver::resolve).toList();
        PostingCommand cmd = new PostingCommand(request.idemKey(), request.txnType(), request.actorType(),
                request.actorId(), entries, request.sourceType(), request.sourceRef(), request.reasonCode(),
                request.reversesTxnId(), request.occurredAt(), request.consumeAllocationIds(), request.proposalId());
        cmd.validate();

        // ③ 잠금: location FOR SHARE → stock_balance FOR UPDATE, 둘 다 id 오름차순
        LockedLocations locations = locationLockRepo.lockForShareOrderById(cmd.physicalLocationIds());
        locations.requireNoActiveCountSession();
        balanceRepo.insertZeroRowsIfAbsent(cmd.positiveDeltaPhysicalLines());
        LockedBalances balances = balanceRepo.lockForUpdateOrderById(cmd.physicalKeys());

        // ④ 잠금 직후 선행 조건. 실패는 예외가 아니라 결과로 반환
        if (!precondition.test(balances)) {
            idempotencyRepo.completePreconditionFailed(request.idemKey());
            return new PreconditionFailed(balances.snapshot());
        }

        // ⑤ 적용. 가용 부족이면 예외 → 전체 롤백. DB CHECK는 최후 방어선
        // SHIPMENT가 아니면 consumeAllocationIds가 비어 있어 consumedQtyByBalance는 빈 맵을 돌려준다
        // (AllocationRepository#consumedQtyByBalance) — 1단계 거래 유형은 이 조회가 사실상 no-op이다.
        Map<Long, Integer> consumedQtyByBalance = allocationRepo.consumedQtyByBalance(cmd.consumeAllocationIds());
        long txnId = txnRepo.insert(cmd);
        for (ResolvedLine entry : cmd.entries()) {
            Integer onHandAfter;
            if (entry.virtual()) {
                onHandAfter = null; // 가상 로케이션은 잔액 행이 없다
            } else {
                LockedBalance balance = balances.find(entry.key())
                        .orElseThrow(() -> new PostingException("NO_STOCK",
                                "%s/%s/%s 잔액 행이 없다".formatted(entry.locationCode(), entry.skuCode(), entry.lotNo())));
                // 소진량은 맵에서 꺼내며 지운다 — on_hand_qty의 delta는 줄마다 달라 DB 상대 갱신으로 누적해도 맞지만,
                // consumedQty는 "이 잔액 행에서 이번에 소진할 총량"으로 줄과 무관하게 고정값이라 같은 잔액 키를
                // 가리키는 줄이 여럿이면 getOrDefault로는 매 줄 반복 적용돼 allocated_qty가 이중으로 깎인다
                // (검증에서 발견된 결함 — 1단계의 절대값 덮어쓰기 결함과 같은 형태가 allocated_qty 차원에서 재현된 것).
                // remove()로 이 거래에서 이 잔액 행에 대해 정확히 한 번만(첫 줄에서) 적용하고, 같은 키의 나머지
                // 줄은 0을 받게 한다 — StockBalanceRepository#applyDelta(BalanceId, int, int) 갱신된 논증 참고.
                Integer consumedQty = consumedQtyByBalance.remove(balance.id().value());
                onHandAfter = balanceRepo.applyDelta(balance.id(), entry.qtyDelta(), consumedQty != null ? consumedQty : 0);
            }
            ledgerRepo.insert(txnId, entry, onHandAfter);
        }

        // 소진 대상 할당은 반드시 이 거래의 물리 줄이 가리키는 잔액에 붙어 있어야 한다 — 할당을 소진한다는
        // 것은 그 재고가 이 거래로 나갔다는 뜻이고, 나갔다면 그 잔액에 원장 줄(applyDelta 호출)이 있어야 한다.
        // 루프가 끝난 뒤에도 맵에 남은 항목이 있다면 그 잔액은 이 거래의 어느 줄도 가리키지 않았다는 뜻이다 —
        // allocated_qty는 그대로인데 ⑥에서 해당 할당만 CONSUMED로 닫히면 I5(할당량 = ACTIVE 할당 합계)가
        // 조용히 깨진다. DB CHECK로는 못 잡는 위반이라(어느 CHECK도 allocated_qty와 ACTIVE 할당 합계를 비교하지
        // 않는다) 여기서 미리 걸러야 한다 (검증에서 발견: 호출자가 소진 대상 할당과 출고 줄의 잔액을 다르게 넘긴 버그).
        // 참조 구현(db/04-harness.sql tst_post)은 잔액마다 v_used를 조회할 뿐 이 대응 관계를 검사하지 않아
        // 같은 구멍이 있다 — 하네스는 184건으로 검증된 파일이라 고치지 않고, Java 쪽만 의도적으로 더 엄격하게 뒀다.
        if (!consumedQtyByBalance.isEmpty()) {
            throw new PostingException("ORPHAN_CONSUME",
                    "소진 대상 할당이 가리키는 잔액 행 id %s가 이 거래의 물리 줄에 없다"
                            .formatted(consumedQtyByBalance.keySet()));
        }

        // ⑥ 할당 소진 표시. WHERE status='ACTIVE'의 영향 행 수가 요청한 id 수와 다르면 롤백 (AllocationRepository#markConsumed)
        allocationRepo.markConsumed(cmd.consumeAllocationIds(), txnId);

        // ⑦ 아웃박스 + 멱등 결과 기록
        outboxRepo.appendStockPosted(txnId, cmd);
        idempotencyRepo.completePosted(request.idemKey(), txnId);
        return new Posted(txnId);
    }
}
