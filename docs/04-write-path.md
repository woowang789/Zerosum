# 쓰기 경로

## 멱등성

모든 쓰기 API는 `Idempotency-Key`를 필수로 받는다. 키는 호출자가 비즈니스 의미로 만든다. `ship:{주문라인}:{출고차수}`, `receipt:{발주라인}:{입고차수}`, `proposal:{제안id}` 같은 식이다. 요청마다 랜덤 UUID를 새로 만들면 재시도할 때 키가 달라져 멱등성이 무의미해진다. LLM이 키를 만들게 해서도 안 된다.

```sql
-- 키 선점: 같은 키가 동시에 들어오면 두 번째 요청은 첫 번째 트랜잭션이 끝날 때까지 대기한다
INSERT INTO idempotency_record (idem_key, command_type, request_hash)
VALUES (:idemKey, :commandType, :requestHash)
ON CONFLICT (idem_key) DO NOTHING
RETURNING idem_key;
```

행이 삽입되면 실행을 계속한다. 삽입되지 않았다면 기존 행을 읽어 `request_hash`가 같으면 저장된 결과를 그대로 반환하고, 다르면 409를 반환한다. 재고 부족 같은 비즈니스 오류로 롤백되면 키 기록도 함께 사라지므로, 같은 키로 나중에 재시도하면 그 시점의 재고로 다시 평가된다.

## 잠금 규칙

규칙은 하나다. **location은 FOR SHARE, stock_balance는 FOR UPDATE로, 이 순서대로, 각각 id 오름차순으로 잠근다.** 모든 쓰기 경로가 같은 순서를 따르므로 잠금 순서 역전으로 인한 데드락이 생기지 않는다. FEFO처럼 비즈니스 정렬이 필요한 경우에도 잠금은 id 순서로 먼저 잡고, 비즈니스 정렬은 잠금을 얻은 뒤 메모리에서 한다. 없는 잔액 행은 잠금 전에 키 정렬 순서대로 0 수량으로 먼저 만든다.

location을 FOR SHARE로 잠그는 이유는 실사와의 경합 때문이다. 실사 시작은 `location.count_session_id`를 채우는 UPDATE로 로케이션 행에 배타 잠금을 걸기 때문에 진행 중인 포스팅이 끝나기를 기다린 뒤 표시되고, 그 이후 들어오는 포스팅은 커밋된 표시를 읽고 거절된다(흐름은 [실사 세션](05-count-session.md#실사-세션)). 실사 흐름은 이 규칙 앞에 `count_session` 행 잠금을 하나 더 두어 count_session → location → stock_balance 순서를 지킨다. 할당은 실재고를 바꾸지 않으므로 location 잠금 없이 잔액 행만 잠근다.

격리 수준은 PostgreSQL 기본값인 READ COMMITTED에 명시적 행 잠금을 조합한다. SERIALIZABLE도 정합성은 지켜주지만 충돌 시점을 예측하기 어렵고 재시도가 잦아진다. 재고처럼 핫스팟이 뚜렷한 도메인에서는 명시적 잠금이 동작을 설명하고 테스트하기 쉽다.

## 포스팅 흐름

```java
// 의사 코드: 단계와 순서를 보여주기 위한 것
// 결과 타입 (Java 21 sealed interface + record)
sealed interface PostingOutcome permits Posted, PreconditionFailed {}
record Posted(long txnId) implements PostingOutcome {}
record PreconditionFailed(BalanceSnapshot current) implements PostingOutcome {}

@Transactional
public PostingOutcome post(PostingCommand cmd, Predicate<LockedBalances> precondition) {
    // ① 멱등 키 선점. 이미 처리된 키면 저장된 결과를 반환 (본문이 다르면 409)
    Optional<IdempotencyRecord> existing = idempotency.claim(cmd.idemKey(), cmd.type(), cmd.requestHash());
    if (existing.isPresent()) {
        return existing.get().toPostingOutcome();
    }

    // ② 커맨드 검증: (SKU, 로트)별 합계 0, 조정 사유 코드, 가상 로케이션 규칙
    cmd.validate();

    // ③ 잠금: location FOR SHARE → stock_balance FOR UPDATE, 둘 다 id 오름차순
    LockedLocations locations = locationRepo.lockForShareOrderById(cmd.physicalLocationIds());
    locations.requireNoActiveCountSession();   // 실사 중이면 거절 (정정은 표시를 먼저 지운 뒤 포스팅하므로 예외 규칙 없음, 05-count-session.md)
    balanceRepo.insertZeroRowsIfAbsent(cmd.sortedKeysWithPositiveDelta());   // ON CONFLICT DO NOTHING
    LockedBalances balances = balanceRepo.lockForUpdateOrderById(cmd.physicalKeys());

    // ④ 잠금 직후 선행 조건 (예: AI 제안의 basis 재검증). 실패는 예외가 아니라 결과로 반환
    if (!precondition.test(balances)) {
        idempotency.complete(cmd.idemKey(), Map.of("status", "PRECONDITION_FAILED"));
        return new PreconditionFailed(balances.snapshot());
    }

    // ⑤ 적용. 가용 부족이면 예외 → 전체 롤백. DB CHECK는 최후 방어선
    InventoryTxn txn = txnRepo.insert(cmd);
    for (PostingEntry entry : cmd.entries()) {
        @Nullable Integer onHandAfter = balances.find(entry.key())       // 가상 로케이션은 잔액 행이 없으므로 null
                .map(b -> b.apply(entry.qtyDelta(), cmd.consumedQtyOf(entry.key())))
                .orElse(null);
        ledgerRepo.insert(txn.id(), entry, onHandAfter);
    }
    allocationRepo.markConsumed(cmd.allocationIds(), txn.id());        // WHERE status = 'ACTIVE', 영향 행 수 검증
    outbox.append(StockPosted.of(txn, cmd.entries()));
    idempotency.complete(cmd.idemKey(), Map.of("txnId", txn.id()));
    return new Posted(txn.id());
}
// 선행 조건이 없으면 posting.post(cmd, Preconditions.none())처럼 호출한다.
// 편의용 오버로드에서 같은 클래스의 post(cmd, ...)를 부르면 자기 호출이 되어 @Transactional이 적용되지 않는다.
```

잔액 행의 `apply` 규칙은 세 가지다. 증가는 실재고에 더한다. 일반 차감은 실재고 − 할당량이 차감량 이상이어야 한다. 출고 차감은 소진할 할당량만큼 할당량과 실재고를 함께 줄인다. 어떤 경우든 결과가 CHECK 제약을 어기면 DB가 커밋을 거부한다.

## 할당 흐름

```java
// 의사 코드
// 유통기한 빠른 순, 기한 없는 로트는 뒤로, 동률은 id
private static final Comparator<LockedBalance> FEFO =
        Comparator.comparing((LockedBalance b) -> b.lot().expiryDate(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingLong(LockedBalance::id);

@Transactional
public AllocationResult allocate(AllocateCommand cmd) {
    Optional<IdempotencyRecord> existing = idempotency.claim(cmd.idemKey(), "ALLOCATE", cmd.requestHash());
    if (existing.isPresent()) {
        return existing.get().toAllocationResult();
    }

    // 후보는 잠금 없이 조회 → id 순서로 잠금 → 잠근 뒤 가용 수량을 다시 계산
    // 실사 중 로케이션은 기본 제외, 채널 노출 정책이 허용한 SKU만 포함 (05-count-session.md)
    List<Long> candidateIds = balanceRepo.findSellableBalanceIds(cmd.warehouseId(), cmd.skuId(), cmd.allowInCount());
    LockedBalances locked = balanceRepo.lockForUpdateOrderById(candidateIds);

    // FEFO 정렬은 잠금 획득 후 메모리에서 (잠금 순서와 비즈니스 순서를 분리)
    List<LockedBalance> ordered = locked.stream().sorted(FEFO).toList();
    AllocationPlan plan = AllocationPlan.cover(ordered, cmd.qty())
            .orElseThrow(() -> new InsufficientStockException(cmd.skuId(), cmd.qty()));

    for (PlannedAllocation item : plan.items()) {
        item.balance().allocate(item.qty());                                 // allocated_qty += qty
        allocationRepo.insert(cmd.idemKey(), cmd.orderLineRef(), item.balance().id(), item.qty());
    }
    outbox.append(StockAllocated.of(cmd, plan));
    idempotency.complete(cmd.idemKey(), plan.toResultMap());
    return new AllocationResult(plan);
}
```

할당 해제는 할당 행이 가리키는 잔액 행을 먼저 잠근 뒤, `UPDATE allocation SET status = 'RELEASED', closed_at = now() WHERE id = :id AND status = 'ACTIVE'`의 영향 행 수가 1인지 확인하고 `allocated_qty`를 줄인다. 출고와 해제가 같은 할당에 동시에 들어와도 같은 잔액 행 잠금에서 직렬화되고, 늦게 온 쪽은 영향 행 수 0으로 실패한다.

단일 행만 바꾸는 단순 차감이라면 `UPDATE ... WHERE id = :id AND on_hand_qty - allocated_qty >= :qty` 후 영향 행 수를 확인하는 조건부 갱신으로도 충분하다. 다만 여러 행이 얽히는 순간부터는 잠금 규칙으로 통일하는 편이 안전하다.

## 구현 메모

쓰기 경로는 JdbcClient나 jOOQ로 SQL을 드러내서 작성한다. JPA 변경 감지에 맡기면 잠금 SQL과 갱신 조건이 코드에서 보이지 않아 코드 리뷰로 정합성을 검증하기 어렵다. 마스터 데이터 CRUD는 JPA를 써도 무방하다.

`@Transactional`은 프록시를 거쳐야 적용된다. 같은 클래스 안에서 다른 메서드를 부르는 자기 호출과 private·final 메서드에는 적용되지 않으므로, 포스팅 서비스와 승인 서비스처럼 트랜잭션 경계가 되는 메서드는 별도 빈의 public 메서드로 둔다. 잔액·원장·할당 리포지토리에는 `@Transactional(propagation = Propagation.MANDATORY)`를 걸어, 실수로 트랜잭션 밖에서 호출되면 즉시 예외가 나게 한다. 또한 JDBC 트랜잭션은 스레드에 묶여 있으므로 트랜잭션 안에서 `@Async`나 `CompletableFuture`로 작업을 다른 스레드에 넘기지 않는다.

널과 식별자 실수는 컴파일 단계에서 막는다. 패키지에 JSpecify의 `@NullMarked`를 선언하고 빌드에서 NullAway로 검사하면, 가상 로케이션처럼 잔액 행이 없을 수 있는 경우의 널 처리 누락이 빌드 오류가 된다. `SkuId`, `LocationId`, `LotId` 같은 식별자는 `record`로 감싸서 `long` 인자의 순서를 바꿔 넣는 실수를 컴파일 에러로 만든다.

데드락이나 직렬화 오류가 나면 트랜잭션 전체를 짧은 지터를 두고 최대 3회 재시도한다. Spring Framework 7에 내장된 재시도 API를 쓰되, 재시도는 반드시 `@Transactional` 경계 바깥에서 감싼다. 안쪽에서 재시도하면 이미 롤백 전용으로 표시된 트랜잭션 안에서 같은 작업을 반복하게 된다. 멱등 키가 있으므로 재시도가 중복 실행으로 이어지지 않는다.

잔액 행을 잠근 트랜잭션 안에서는 HTTP, Kafka, LLM 같은 외부 호출을 하지 않는다. 잠금을 쥔 채 외부 지연을 기다리면 같은 SKU의 모든 쓰기가 줄줄이 막히고, 외부 부작용은 DB 롤백으로 되돌릴 수 없다. AI 기능을 붙일 때 "LLM 판단을 받아서 바로 차감"하는 코드가 이 규칙을 가장 쉽게 깬다.
