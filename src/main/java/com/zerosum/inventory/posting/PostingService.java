package com.zerosum.inventory.posting;

import com.zerosum.inventory.repository.IdempotencyRepository;
import com.zerosum.inventory.repository.InventoryTxnRepository;
import com.zerosum.inventory.repository.LedgerRepository;
import com.zerosum.inventory.repository.LocationLockRepository;
import com.zerosum.inventory.repository.OutboxRepository;
import com.zerosum.inventory.repository.StockBalanceRepository;
import java.util.List;
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

    PostingService(IdempotencyRepository idempotencyRepo, PostingLineResolver lineResolver,
            LocationLockRepository locationLockRepo, StockBalanceRepository balanceRepo,
            InventoryTxnRepository txnRepo, LedgerRepository ledgerRepo, OutboxRepository outboxRepo) {
        this.idempotencyRepo = idempotencyRepo;
        this.lineResolver = lineResolver;
        this.locationLockRepo = locationLockRepo;
        this.balanceRepo = balanceRepo;
        this.txnRepo = txnRepo;
        this.ledgerRepo = ledgerRepo;
        this.outboxRepo = outboxRepo;
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
                request.reversesTxnId(), request.occurredAt());
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
        long txnId = txnRepo.insert(cmd);
        for (ResolvedLine entry : cmd.entries()) {
            Integer onHandAfter;
            if (entry.virtual()) {
                onHandAfter = null; // 가상 로케이션은 잔액 행이 없다
            } else {
                LockedBalance balance = balances.find(entry.key())
                        .orElseThrow(() -> new PostingException("NO_STOCK",
                                "%s/%s/%s 잔액 행이 없다".formatted(entry.locationCode(), entry.skuCode(), entry.lotNo())));
                // 상대 갱신 + 가용 검사를 DB에 맡긴다 (같은 키가 이 거래에 여러 줄 있어도 누적으로 맞다 — StockBalanceRepository 참고)
                onHandAfter = balanceRepo.applyDelta(balance.id(), entry.qtyDelta());
            }
            ledgerRepo.insert(txnId, entry, onHandAfter);
        }

        // ⑥ 아웃박스 + 멱등 결과 기록 (1단계는 할당이 범위 밖이라 소진 단계가 없다)
        outboxRepo.appendStockPosted(txnId, cmd);
        idempotencyRepo.completePosted(request.idemKey(), txnId);
        return new Posted(txnId);
    }
}
