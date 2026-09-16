# 쓰기 경로

## 멱등성

쓰기 API는 `Idempotency-Key`를 받는다. 키는 호출자가 비즈니스 의미로 만든다. `ship:{주문라인}:{출고차수}`, `receipt:{발주라인}:{입고차수}`, `proposal:{제안id}` 같은 식이다. 요청마다 랜덤 UUID를 새로 만들면 재시도할 때 키가 달라져 멱등성이 무의미해진다. LLM이 키를 만들게 해서도 안 된다.

**업무 식별자가 키가 되려면 그 일이 평생 한 번 일어나야 한다.** 위 예가 모두 그렇다 — 그 발주라인의 1차 입고는 한 번뿐이다. 반복되는 일에 대상을 키로 삼으면 두 번째부터는 첫 번째의 재생이 된다. `idempotency_record`에는 해제가 없으므로(I6) 영영 그렇다. 반복되는 일의 재시도 재현은 멱등 기록이 아니라 상태가 맡아야 한다 — [실사 시작](05-count-session.md#실사-세션)이 그 예다.

세 번 어겼고 세 번 고쳤다. 어긋난 모양은 모두 **업무 식별자로 만든 키가 그 업무를 가리키지 않게 되는 것**이었지만, 고친 방법은 갈렸다 — 기준은 **직렬화할 단일 행이 있느냐**다.

| 키 | 무엇이 어긋났나 | 고친 방법 |
|---|---|---|
| `count:start:{창고}:{로케이션}` | 순환 실사는 반복되는 일이라 두 번째 실사가 첫 번째의 재생이 됐다 | 상태가 재현을 맡는다 — `location.count_session_id`를 `FOR UPDATE`로 읽어 열린 세션을 돌려준다 |
| `shipment:{주문줄}:{차수}` | 다른 주문의 예약을 소진해도 통과해, 키가 가리키는 주문과 실제로 나간 예약이 갈렸다 | 소진 대상 할당의 주문 줄을 이 출고가 내건 주문 줄과 대조한다 (`ALLOC_ORDER_MISMATCH`) |
| `allocate:{주문줄}` | 해제하고 다시 잡는 것이 정상 흐름인데 대상만으로 키를 만들었다 | 키에 회차를 넣는다 — `allocate:{주문줄}:{회차}` |

할당에서 실사와 다른 길을 고른 이유는 잠글 행이 없기 때문이다. 실사 시작은 로케이션 행이 이미 있어 그 한 행을 잠그면 동시 시작이 줄을 서지만, 할당은 시작 시점에 `allocation` 행이 아직 없다. 그래서 동시 중복 방지를 위 `ON CONFLICT DO NOTHING`의 키 선점에 그대로 맡기고, 대신 키가 회차마다 달라지게 했다 — 자세히는 아래 [할당 흐름](#할당-흐름).

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

    // ② 커맨드 검증: 줄이 하나 이상, (SKU, 로트)별 합계 0, 조정 사유 코드, 가상 로케이션 규칙
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

②의 **가상 로케이션 규칙**은 거래 유형마다 어느 가상 로케이션을 어느 방향으로 쓸 수 있는지다. [거래 유형](03-transactions.md) 표의 **가상 로케이션 열만** 옮긴 것이고, 물리 줄의 부호는 그 반대다. 표의 나머지 — 물리 줄의 출발→도착이 그 유형에 맞는지(예: MOVE가 `RECEIVING → STORAGE`인지), 로케이션 유형과 창고가 맞는지 — 는 ②가 보지 않는다. 그쪽은 복합 외래키(I9)와 로케이션 유형 CHECK가 닿는 만큼만 지켜진다.

| 거래 | 가상 줄 | 물리 줄 |
|---|---|---|
| RECEIPT | `V-SUPPLIER` 음수 | 양수 |
| SHIPMENT | `V-CUSTOMER` 양수 | 음수 |
| RETURN | `V-CUSTOMER` 음수 | 양수 |
| ADJUSTMENT | `V-ADJUST`만, 부호 자유 | 부호 자유 |
| MOVE · TRANSFER_OUT · TRANSFER_IN | 금지 | 부호 자유 |
| REVERSAL | **면제** | **면제** |

조정만 부호가 자유로운 것은 분실도 발견도 조정이기 때문이다. 대신 조정에는 사유 코드가 필수이고(`REASON_REQUIRED`, `inventory_txn` CHECK) 웹에서는 SUPERVISOR만 부를 수 있다.

이 규칙이 실제로 막는 것은 **부호**다. 쓰기 창구가 클라이언트 qty로 `-qty`/`+qty` 상대 줄을 만드는 이상, qty가 음수면 거래의 방향이 통째로 뒤집혀 `txn_type = SHIPMENT`·`reason_code = NULL`인 거래가 실재고를 늘린다. 원장과 잔액은 완벽히 일치하므로 [정합 검증](06-events-reconciliation.md#정합-검증-배치) ①~⑤가 전부 0건이고 배치는 영원히 이것을 보지 못한다 — 조정만 SUPERVISOR로 묶어 둔 통제가 우회되는 것이다. 창구에서 qty > 0을 막는 것과 별개로 코어가 이 규칙을 갖는 이유는 `:core`가 웹 말고도 MCP·제안 승인 경로에서 호출되기 때문이다.

**REVERSAL은 면제한다.** 역분개는 정의상 원거래의 반대 방향이라, 입고의 역분개는 이 규칙이 RECEIPT에 금지하는 바로 그 모양(`V-SUPPLIER` 양수 / 물리 음수)이 된다. 면제의 대가는 분명히 해 둔다 — REVERSAL 전용 검증은 지금 없다. `reverses_txn_id`가 실려 갈 뿐이고, 그 줄들이 정말 원거래 줄의 부호 반대인지는 아무도 대조하지 않는다. 남는 방어선은 (SKU, 로트)별 합계 0, `reverses_txn_id` UNIQUE(같은 거래를 두 번 역분개할 수 없다), 음수 재고 금지 셋뿐이다. 지금은 역분개를 만드는 HTTP 창구가 없어 노출이 코어 호출자로 제한되지만, 창구를 열 때는 "원거래 원장 줄을 읽어 부호만 뒤집는다"를 커맨드를 만드는 쪽이 아니라 ②에서 강제해야 한다.

출고가 할당을 소진할 때는 두 가지를 더 본다. 소진 대상 할당이 가리키는 잔액 행이 이 거래의 물리 줄에 있어야 하고(`ORPHAN_CONSUME` — 없으면 할당량은 그대로인데 할당만 닫혀 I5가 조용히 깨진다), 그 할당들이 걸린 주문 줄이 하나뿐이면서 이 출고가 내건 주문 줄과 같아야 한다(`ALLOC_ORDER_MISMATCH`). 뒤의 것은 재고 수치가 아니라 추적 가능성의 문제다 — 어긋나면 원장은 이 주문으로 나갔다고 하는데 예약은 저 주문의 것이 되고, 무엇보다 `ship:{주문라인}:{출고차수}` 키가 그 업무를 가리키지 않게 되어 나중에 그 주문을 진짜 출고할 때 새 거래 대신 이 거래가 재생된다. 둘 다 DB CHECK로는 표현되지 않아 포스팅 서비스가 막는다.

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
    // 멱등 키는 호출자가 주지 않는다. 회차 = 이 주문 줄에서 이미 닫힌(ACTIVE가 하나도 없는) 할당 회차의 수.
    // 1차 때 0, 재시도 때도 0(아직 ACTIVE라), 해제·소진 뒤엔 1이다.
    String idemKey = "allocate:%s:%d".formatted(cmd.orderLineRef(), allocationRepo.closedRoundCount(cmd.orderLineRef()));
    Optional<IdempotencyRecord> existing = idempotency.claim(idemKey, "ALLOCATE", cmd.requestHash());
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
        allocationRepo.insert(idemKey, cmd.orderLineRef(), item.balance().id(), item.qty());
    }
    outbox.append(StockAllocated.of(cmd, plan));
    idempotency.complete(idemKey, plan.toResultMap());
    return new AllocationResult(plan);
}
```

회차를 키에 넣는 이유는 [멱등성](#멱등성)의 표에 적었다. **회차는 allocation 행 수가 아니라 `idem_key`로 묶은 회차 단위로 센다.** 한 번의 할당이 FEFO로 여러 로트에 걸쳐 행을 여럿 만들고 그중 일부만 먼저 닫히는 일(부분 소진)이 있는데, 행을 세면 그 사이의 재시도가 새 회차로 넘어가 예약이 두 번 잡힌다. 어느 한 행이라도 ACTIVE인 동안에는 회차가 그대로고, 재시도는 같은 키로 재생된다.

동시 중복 방지는 그대로 키 선점이 맡는다. 동시에 들어온 둘은 커밋된 상태만 보므로 같은 회차를 계산하고, 진 쪽은 이긴 트랜잭션이 끝날 때까지 대기했다가 저장된 결과를 재생한다. 회차 계산을 잠금으로 보호하지 않는 것은 [잠금 규칙](#잠금-규칙) 때문이다 — `allocation`을 `stock_balance`보다 먼저 잠그면 해제(`stock_balance` → `allocation`)와 순서가 역전되어 데드락이 생긴다. 그 대가로 남는 창은 하나뿐이고 순차 호출에서는 닫혀 있다: **해제가 아직 커밋되지 않은 순간에 같은 주문 줄의 할당이 겹쳐 들어오면 닫히는 중인 회차를 재생한다.** 해제 응답을 받은 뒤 할당을 부르는 호출자는 이 창을 만나지 않는다.

ACTIVE 예약이 있는 동안 같은 주문 줄을 다른 수량으로 요청하면 회차가 그대로라 같은 키에 다른 본문이 되어 409다. 이 부분은 바뀌지 않았다 — 달라진 것은 해제·소진 뒤에는 회차가 올라가 그 주문 줄이 영구히 재할당 불가로 남지 않는다는 점이다.

할당 해제는 할당 행이 가리키는 잔액 행을 먼저 잠근 뒤, `UPDATE allocation SET status = 'RELEASED', closed_at = now() WHERE id = :id AND status = 'ACTIVE'`의 영향 행 수가 1인지 확인하고 `allocated_qty`를 줄인다. 출고와 해제가 같은 할당에 동시에 들어와도 같은 잔액 행 잠금에서 직렬화되고, 늦게 온 쪽은 영향 행 수 0으로 실패한다.

단일 행만 바꾸는 단순 차감이라면 `UPDATE ... WHERE id = :id AND on_hand_qty - allocated_qty >= :qty` 후 영향 행 수를 확인하는 조건부 갱신으로도 충분하다. 다만 여러 행이 얽히는 순간부터는 잠금 규칙으로 통일하는 편이 안전하다.

## 구현 메모

쓰기 경로는 JdbcClient나 jOOQ로 SQL을 드러내서 작성한다. JPA 변경 감지에 맡기면 잠금 SQL과 갱신 조건이 코드에서 보이지 않아 코드 리뷰로 정합성을 검증하기 어렵다. 마스터 데이터 CRUD는 JPA를 써도 무방하다.

`@Transactional`은 프록시를 거쳐야 적용된다. 같은 클래스 안에서 다른 메서드를 부르는 자기 호출과 private·final 메서드에는 적용되지 않으므로, 포스팅 서비스와 승인 서비스처럼 트랜잭션 경계가 되는 메서드는 별도 빈의 public 메서드로 둔다. 잔액·원장·할당 리포지토리에는 `@Transactional(propagation = Propagation.MANDATORY)`를 걸어, 실수로 트랜잭션 밖에서 호출되면 즉시 예외가 나게 한다. 또한 JDBC 트랜잭션은 스레드에 묶여 있으므로 트랜잭션 안에서 `@Async`나 `CompletableFuture`로 작업을 다른 스레드에 넘기지 않는다.

널과 식별자 실수는 컴파일 단계에서 막는다. 패키지에 JSpecify의 `@NullMarked`를 선언하고 빌드에서 NullAway로 검사하면, 가상 로케이션처럼 잔액 행이 없을 수 있는 경우의 널 처리 누락이 빌드 오류가 된다. `SkuId`, `LocationId`, `LotId` 같은 식별자는 `record`로 감싸서 `long` 인자의 순서를 바꿔 넣는 실수를 컴파일 에러로 만든다.

데드락이나 직렬화 오류가 나면 트랜잭션 전체를 짧은 지터를 두고 최대 3회 재시도한다. Spring Framework 7에 내장된 재시도 API를 쓰되, 재시도는 반드시 `@Transactional` 경계 바깥에서 감싼다. 안쪽에서 재시도하면 이미 롤백 전용으로 표시된 트랜잭션 안에서 같은 작업을 반복하게 된다. 멱등 키가 있으므로 재시도가 중복 실행으로 이어지지 않는다.

잔액 행을 잠근 트랜잭션 안에서는 HTTP, Kafka, LLM 같은 외부 호출을 하지 않는다. 잠금을 쥔 채 외부 지연을 기다리면 같은 SKU의 모든 쓰기가 줄줄이 막히고, 외부 부작용은 DB 롤백으로 되돌릴 수 없다. AI 기능을 붙일 때 "LLM 판단을 받아서 바로 차감"하는 코드가 이 규칙을 가장 쉽게 깬다.
