# 스키마 검증

[데이터 모델](02-data-model.md)의 DDL을 PostgreSQL 16에 그대로 올리고, 실제 3PL 포맷의 목 데이터로 유스케이스를 돌려 정상 수행과 정상 실패를 확인한 기록이다. 구현 전에 스키마만으로 불변식이 지켜지는지 보는 것이 목적이다.

## 검증 방법

처음에는 문서에 있던 DDL을 한 글자도 고치지 않고 그대로 적용했다. 오류 없이 올라갔고 유스케이스도 전부 통과했다. 그 뒤 검증에서 나온 변경(아래 "검증에서 나온 변경" 참고)을 반영한 결과가 지금의 정본 [`db/migration/V1__init.sql`](../db/migration/V1__init.sql)이다. [쓰기 경로](04-write-path.md)의 포스팅·할당 흐름과 [실사](05-count-session.md)의 세션 흐름은 `db/04-harness.sql`에 PL/pgSQL로 재현했다. 운영 코드가 아니라 스키마가 그 흐름을 지탱하는지 보기 위한 하네스이며, 애플리케이션 계층 검사(가용 부족, 합계 0, 실사 중 거절)도 함께 재현해 DB 제약이 최후 방어선으로 남는지 확인했다.

[권한 경계](07-ai-integration.md#권한-경계)의 다섯 계정을 모두 만들고, 유스케이스마다 해당 계정으로 접속해 실행했다. 검증 환경에는 레플리카가 없어 `ai_ro`도 프라이머리를 보지만 권한 범위는 같다.

```bash
bash db/run.sh
```

컨테이너를 새로 띄우고 스키마·목 데이터·유스케이스를 순서대로 돌린 뒤 결과를 표로 출력한다. 동시성 시나리오는 커넥션을 병렬로 띄워야 해서 `db/run-concurrency.sh`로 분리했다.

| 항목 | 값 |
|---|---|
| DB | PostgreSQL 16.15 (`postgres:16` 컨테이너) |
| 스키마 | 테이블 14개, 뷰 2개, 지연 제약 트리거 1개 |
| 계정 | `migrator` / `app_admin` / `app_rw` / `ai_ro` / `ai_proposer` |
| 유스케이스 | 7개 그룹 83건, 단언 184건 |
| 결과 | **184건 전부 PASS** |

| 그룹 | 내용 | 단언 |
|---|---|---|
| A | 입고부터 출고까지 정상 흐름 | 40 |
| B | 멱등성 | 9 |
| C | 실사 세션 | 33 |
| D | 불변식 위반은 정상적으로 실패한다 | 39 |
| E | AI 제안과 권한 경계 | 29 |
| F | 정합 검증 배치 | 26 |
| G | 동시성 | 8 |

## 목 데이터

국내 이커머스 3PL 센터 두 곳을 가정했다. 코드 체계는 실제 WMS에서 흔한 형식을 따랐다.

| 구분 | 형식 | 값 |
|---|---|---|
| 창고 | `{지역 3자}{일련 2자}` | `ICN01` 인천 1센터, `YIT01` 용인 1센터 |
| 로케이션 | `{존}-{통로}-{랙}-{단}` | `A-01-01-1`, `A-01-02-1`, `B-01-01-1`, `A-02-01-1` … |
| 입고장·반품·불량·운송 | 용도별 코드 | `RCV-01`, `RCV-02`, `RTN-01`, `DMG-01`, `TRS-01` |
| 가상 로케이션 | 창고마다 하나씩 | `V-SUPPLIER`, `V-CUSTOMER`, `V-ADJUST` |
| SKU | `SKU-{6자리}` | 아래 표 |
| 로트 | `L{입고일}-{일련}` | `L20260820-A`, `L20260901-A`, `L20260910-B` … |
| 발주·주문·반품 | `{구분}-{일자}-{일련}` | `PO-20260908-0042`, `ORD-20260911-0001`, `RMA-20260911-0003` |
| 작업자 | `user:{이름}` | `user:kim.ys`, `user:park.jh`, `user:lee.sh`, `user:choi.dw` |

| SKU | 상품명 | 로트 관리 | 로트 (유통기한) |
|---|---|---|---|
| `SKU-100001` | 스탠다드 코튼 반팔티 화이트 M | — | `DEFAULT` |
| `SKU-100002` | 스탠다드 코튼 반팔티 블랙 L | — | `DEFAULT` |
| `SKU-200001` | 유기농 아몬드 200g | O | `L20260820-A` (2027-02-20), `L20260905-B` (2027-03-05) |
| `SKU-200002` | 콜드브루 원액 1L | O | `L20260901-A` (2026-10-01), `L20260910-B` (2026-10-10) |
| `SKU-300001` | 무선 마우스 MX-210 블랙 | — | `DEFAULT` |

기준일은 2026-09-11이다. 콜드브루 두 로트는 만료가 9일 차이라 FEFO 검증에 쓴다. 로트를 관리하지 않는 SKU도 `DEFAULT` 로트를 하나 갖는다.

## 읽는 법

유스케이스마다 **상황**을 자연어로 적고, 그 커맨드가 어떤 테이블에 무슨 일을 하는지를 **CRUD**로 적었다. `FOR SHARE` / `FOR UPDATE` 표기는 그 읽기가 잠금을 동반한다는 뜻이다. 숫자는 영향 행 수다.

코어 전체에서 **D(삭제)는 한 번도 일어나지 않는다.** 원장은 추가만 되고, 나머지는 상태 전이로 표현되기 때문이다. 아래 CRUD에서 D가 나오는 곳은 정합 검증 그룹의 뒷정리뿐이다.

## A. 입고부터 출고까지

2026-09-11 인천 1센터의 하루를 따라간다. 스크립트는 [`db/usecases/A-inbound-outbound.sql`](../db/usecases/A-inbound-outbound.sql)에 있다.

### UC-A01 · 입고

**상황.** 발주 `PO-20260908-0042`로 주문한 티셔츠 120개가 도착했다. 검수를 마치고 입고장 `RCV-01`에 내려놓는다. 공급사 가상 로케이션에서 입고장으로 옮기는 거래로 기록되므로, 재고는 생긴 것이 아니라 이동한 것이다. 가상 로케이션 쪽 원장 줄은 `on_hand_after`가 NULL로 남는다.

- **C** `idempotency_record` 1 (키 `receipt:PO-20260908-0042-1:1`) · `stock_balance` 1 (RCV-01 신규 키) · `inventory_txn` 1 · `inventory_ledger_entry` 2 · `outbox_event` 1
- **R** `warehouse`·`location`·`sku`·`lot` (코드 → id) · `location` FOR SHARE 1 (물리 로케이션만) · `stock_balance` FOR UPDATE 1
- **U** `stock_balance` 1 (`on_hand_qty` +120) · `idempotency_record` 1 (`result`)
- **D** 없음

**확인.** `RCV-01` 실재고 120. 공급사 가상 로케이션에는 잔액 행이 생기지 않는다.

### UC-A02 · 적치

**상황.** 작업 지시 `WO-20260911-0007`에 따라 입고장의 티셔츠 120개를 보관 로케이션 `A-01-01-1`로 올린다. 로케이션 두 개가 모두 물리 로케이션이라 둘 다 `FOR SHARE`로 잠그며, 잠금은 id 오름차순으로 잡는다.

- **C** `idempotency_record` 1 · `stock_balance` 1 (A-01-01-1 신규 키) · `inventory_txn` 1 · `inventory_ledger_entry` 2 · `outbox_event` 1
- **R** `location` FOR SHARE 2 · `stock_balance` FOR UPDATE 2
- **U** `stock_balance` 2 (−120 / +120) · `idempotency_record` 1
- **D** 없음

**확인.** `RCV-01` 0, `A-01-01-1` 120.

### UC-A03 · 로트 관리 SKU 입고

**상황.** 콜드브루를 두 번에 나눠 받는다. 만료가 늦은 `L20260910-B` 80개를 먼저 `A-01-01-2`에, 만료가 임박한 `L20260901-A` 50개를 나중에 `A-01-02-1`에 넣는다. 일부러 이 순서로 넣어, 뒤에 오는 FEFO 할당이 잔액 행 id 순서가 아니라 유통기한 순서를 따르는지 볼 수 있게 한다.

- **C** 입고 2건 × (`idempotency_record` 1 · `stock_balance` 1 · `inventory_txn` 1 · `inventory_ledger_entry` 2 · `outbox_event` 1)
- **R** `lot` (SKU별 로트 번호 해석) · `location` FOR SHARE 1 · `stock_balance` FOR UPDATE 1
- **U** `stock_balance` 1 · `idempotency_record` 1 (건마다)
- **D** 없음

### UC-A04 · 할당 (FEFO)

**상황.** 주문 `ORD-20260911-0001`의 콜드브루 60개를 잡는다. 할당은 물리적 이동이 아니므로 원장에 남지 않고 `allocated_qty`와 `allocation` 행으로만 관리된다. 로케이션은 잠그지 않고 잔액 행만 잠근다. 잠금은 id 순서로 잡고, FEFO 정렬은 잠금을 얻은 뒤 메모리에서 한다.

- **C** `idempotency_record` 1 · `allocation` 2 · `outbox_event` 1 (`StockAllocated`)
- **R** `stock_balance` FOR UPDATE (판매 가능 로케이션의 후보 전부) · `lot` (유통기한 정렬)
- **U** `stock_balance` 2 (`allocated_qty` +50 / +10) · `idempotency_record` 1
- **D** 없음

**확인.** 만료 임박 `L20260901-A`에서 50개를 먼저 전량 잡고, 모자란 10개를 `L20260910-B`에서 잡는다. 잔액 행 id는 `L20260910-B` 쪽이 더 작지만 FEFO가 이겼다. `A-01-02-1`의 가용 수량은 0이 된다(실재고 50 − 할당 50).

### UC-A05 · 출고

**상황.** 같은 주문을 출고한다. 두 로트에 걸쳐 있으므로 원장은 네 줄이 된다 — 로트마다 보관 로케이션에서 빠지는 줄과 고객 가상 로케이션으로 들어가는 줄. (SKU, 로트)별 합계 0은 로트 단위로 각각 성립한다. 출고 차감은 일반 차감과 달라서, 소진할 할당량만큼 `allocated_qty`와 `on_hand_qty`를 함께 줄인다.

- **C** `idempotency_record` 1 · `inventory_txn` 1 · `inventory_ledger_entry` 4 · `outbox_event` 1
- **R** `location` FOR SHARE 2 · `stock_balance` FOR UPDATE 2 · `allocation` 2 (소진 대상)
- **U** `stock_balance` 2 (`on_hand_qty`와 `allocated_qty` 동시 차감) · `allocation` 2 (`ACTIVE` → `CONSUMED`, `consumed_txn_id`) · `idempotency_record` 1
- **D** 없음

**확인.** `A-01-02-1` 0, `A-01-01-2` 70, 두 곳 모두 `allocated_qty` 0, 할당 2건 모두 `CONSUMED`.

### UC-A06 · 할당 해제

**상황.** 주문 `ORD-20260911-0002`로 티셔츠 30개를 잡아뒀다가 고객이 취소했다. 할당 행이 가리키는 잔액 행을 먼저 잠그고, `status = 'ACTIVE'` 조건으로 갱신해 영향 행 수가 1인지 확인한 뒤 `allocated_qty`를 줄인다. 출고와 해제가 같은 할당에 동시에 들어와도 같은 잔액 행 잠금에서 직렬화되고, 늦게 온 쪽은 영향 행 수 0으로 실패한다.

- **C** `idempotency_record` 1
- **R** `allocation` (해제 대상) · `stock_balance` FOR UPDATE 1
- **U** `allocation` 1 (`ACTIVE` → `RELEASED`, `closed_at`) · `stock_balance` 1 (`allocated_qty` −30) · `idempotency_record` 1
- **D** 없음

**확인.** 가용 수량이 90에서 120으로 복구된다. 실재고는 처음부터 끝까지 120으로 변하지 않는다.

### UC-A07~A09 · 반품과 검수

**상황.** 고객이 티셔츠 3개를 반품했다(`RMA-20260911-0003`). 고객 가상 로케이션에서 반품 검수 대기 `RTN-01`로 들어온다. 검수 결과 2개는 양품이라 보관 `A-01-01-1`로, 1개는 불량이라 `DMG-01`로 옮긴다. 반품 검수 대기와 불량이 컬럼이 아니라 로케이션이므로, 상태 변경이 전부 일반 이동 거래가 되어 원장에 남는다.

- **C** 거래 3건 × (`idempotency_record` 1 · `inventory_txn` 1 · `inventory_ledger_entry` 2 · `outbox_event` 1) · `stock_balance` 2 (RTN-01, DMG-01 신규 키)
- **R** `location` FOR SHARE 1~2 · `stock_balance` FOR UPDATE 1~2
- **U** `stock_balance` · `idempotency_record` (건마다)
- **D** 없음

**확인.** `RTN-01` 0, `DMG-01` 1, `A-01-01-1` 122. 불량 1개는 사라지지 않고 `DMG-01`에 남아 실물과 대사된다.

### UC-A10~A11 · 조정과 역분개

**상황.** 지게차 사고로 티셔츠 2개가 파손됐다고 보고돼 조정을 올렸는데(`INC-20260911-0012`, 사유 `DAMAGED_IN_STORAGE`), 확인해보니 다른 로케이션의 물건이었다. 원장은 수정할 수 없으므로 반대 부호의 역분개 거래로 정정한다. 원장에는 조정 2줄과 역분개 2줄이 모두 남아, 무엇이 잘못됐고 어떻게 고쳤는지가 추적된다.

- **C** 조정 1건 + 역분개 1건 × (`idempotency_record` 1 · `inventory_txn` 1 · `inventory_ledger_entry` 2 · `outbox_event` 1)
- **R** `location` FOR SHARE 1 · `stock_balance` FOR UPDATE 1 · `inventory_txn` (역분개할 원거래 id)
- **U** `stock_balance` 1 · `idempotency_record` 1 (건마다)
- **D** 없음 — 원장은 지우지 않는다

**확인.** 실재고가 122 → 120 → 122로 돌아온다. `source_ref = 'INC-20260911-0012'`인 원장은 4줄이 남는다. 역분개 거래는 `reverses_txn_id`로 원거래를 가리킨다.

### UC-A12~A13 · 센터 간 이동

**상황.** 용인센터로 티셔츠 40개를 보낸다. 출발 시점에 보관 로케이션에서 `TRS-01`(운송 중)로 옮기고, 도착 스캔 때 인천센터의 `TRS-01`에서 용인센터의 `RCV-01`로 옮긴다. 운송 중에도 재고가 잔액으로 추적되므로 트럭 위의 재고가 장부에서 사라지지 않는다. 도착 거래는 창고 두 곳에 걸치는데, 원장 줄마다 자기 `warehouse_id`를 갖고 복합 외래키로 검증되므로 문제가 없다.

- **C** 거래 2건 × (`idempotency_record` 1 · `inventory_txn` 1 · `inventory_ledger_entry` 2 · `outbox_event` 1) · `stock_balance` 2 (TRS-01, YIT01 RCV-01 신규 키)
- **R** `location` FOR SHARE 2 (도착 거래는 서로 다른 창고의 로케이션 2개) · `stock_balance` FOR UPDATE 2
- **U** `stock_balance` 2 · `idempotency_record` 1 (건마다)
- **D** 없음

**확인.** 출발 후 `TRS-01`에 40개가 보이고, 도착 후 인천 `TRS-01` 0 / 용인 `RCV-01` 40.

### UC-A14 · 슬로팅 재배치

**상황.** 출고 빈도 분석에 따라 티셔츠 30개를 `A-01-01-1`에서 `B-01-01-1`로 옮긴다. 이동 뒤에도 창고 단위 판매 가능 수량은 변하지 않는다.

- **C** `idempotency_record` 1 · `stock_balance` 1 · `inventory_txn` 1 · `inventory_ledger_entry` 2 · `outbox_event` 1
- **R** `location` FOR SHARE 2 · `stock_balance` FOR UPDATE 2 · `v_sellable_stock` (확인용)
- **U** `stock_balance` 2 · `idempotency_record` 1
- **D** 없음

**확인.** `v_sellable_stock`의 인천센터 티셔츠 판매 가능 수량 82 (`A-01-01-1` 52 + `B-01-01-1` 30).

## B. 멱등성

스크립트는 [`db/usecases/B-idempotency.sql`](../db/usecases/B-idempotency.sql)에 있다.

### UC-B01 · 같은 키 재요청

**상황.** 적치 요청을 보냈는데 응답이 오기 전에 네트워크가 끊겨 현장 앱이 같은 요청을 다시 보냈다. 멱등 키가 같으므로 두 번째 요청은 새 거래를 만들지 않고 저장된 결과를 그대로 돌려받는다.

- **C** 없음 — 키 선점이 `ON CONFLICT DO NOTHING`으로 튕긴다
- **R** `idempotency_record` 1 (`request_hash` 비교, `result` 반환)
- **U** 없음
- **D** 없음

**확인.** 거래는 여전히 1건, 재고도 그대로. 이중 적치가 일어나지 않는다.

### UC-B02 · 같은 키 다른 본문

**상황.** 같은 멱등 키인데 수량이 120에서 99로 바뀌어 들어왔다. 호출자가 키를 잘못 재사용한 것이므로 조용히 성공시키면 안 되고 409로 거절한다.

- **R** `idempotency_record` 1 (`request_hash` 불일치)
- **C/U/D** 없음 — 전부 롤백

### UC-B03 · 실패한 키의 재시도

**상황.** 재고가 없는 로케이션에서 출고를 시도해 실패했다. 비즈니스 오류로 롤백되면 멱등 키 기록도 함께 사라지므로, 입고가 들어온 뒤 같은 키로 재시도하면 그 시점의 재고로 다시 평가된다. "한 번 실패한 키는 영원히 막힌다"가 되지 않는다.

- **1차** — 전부 롤백. `idempotency_record`에 키가 남지 않는다
- **2차(입고 후)** — **C** `inventory_txn` 1 · `inventory_ledger_entry` 2 · `outbox_event` 1 · `idempotency_record` 1 / **U** `stock_balance` 1

**확인.** 1차 실패 후 `idempotency_record` 조회 0건. 2차에서 정상 출고되어 실재고 20 → 15.

## C. 실사 세션

`B-01-01-1`의 티셔츠 30개를 대상으로 순환 실사를 돈다. 스크립트는 [`db/usecases/C-count-session.sql`](../db/usecases/C-count-session.sql)에 있다.

### UC-C01~C03 · 시작과 경계

**상황.** 작업자가 `B-01-01-1`의 실사를 시작한다. 세션을 만들고 `location.count_session_id`에 표시하는 일이 한 트랜잭션에서 일어난다. 이 UPDATE는 로케이션 행에 배타 잠금을 걸어, 진행 중인 포스팅의 `FOR SHARE`가 끝나기를 기다린 뒤 표시된다. 표시가 커밋된 뒤 들어오는 출고는 거절되고, 같은 로케이션에 실사를 또 시작하려 해도 부분 유니크 인덱스가 막는다.

- **C** `idempotency_record` 1 · `count_session` 1
- **R** `location`·`warehouse` (코드 해석)
- **U** `location` 1 (`count_session_id`, `WHERE count_session_id IS NULL` 조건부) · `idempotency_record` 1
- **D** 없음

**확인.** 표시가 설정된다. 이후 출고는 `COUNT_IN_PROGRESS`로 거절되고, 두 번째 실사 시작은 `uq_count_session_active` 위반으로 거절된다.

### UC-C04 · 차이 없는 제출

**상황.** 30개를 세어 제출했고 시스템 수량과 같다. 세션 행을 잠그고 `OPEN`인지 확인한 뒤 라인별 실재고를 `system_qty`로 굳힌다. 표시가 커밋된 뒤로는 이 로케이션의 실재고를 바꾸는 포스팅이 모두 거절되므로 이 값은 실사 시작 시점의 값과 같다. 차이가 없으니 정정 거래 없이 표시만 지우고 닫는다.

- **C** `idempotency_record` 1 · `count_result` 1
- **R** `count_session` FOR UPDATE 1 · `stock_balance` (해당 로케이션 전체) · `sku`·`lot`
- **U** `count_session` 1 (`OPEN` → `CONFIRMED`, `submitted_at`, `closed_at`) · `location` 1 (표시 해제) · `idempotency_record` 1
- **D** 없음

**확인.** 세션 `CONFIRMED`, 표시 해제, `resolution_txn_id`는 NULL(정정할 것이 없었다).

### UC-C05 · 허용 오차 안의 차이

**상황.** 다시 실사해 29개를 셌다. 차이 −1개는 허용 오차(1개 이하이면서 5% 이하) 안이므로 사람 검토 없이 같은 트랜잭션에서 정정까지 끝낸다. 정정은 표시를 먼저 지우고 그다음에 조정을 포스팅한다. 표시를 지운 UPDATE가 로케이션 행을 배타 잠금으로 쥐고 있어 커밋 전에는 다른 포스팅이 끼어들 수 없고, 포스팅 서비스는 자기 트랜잭션에서 지운 표시를 보므로 실사용 예외 규칙이 따로 필요 없다.

- **C** `count_result` 1 · `idempotency_record` 2 (제출 + 정정) · `inventory_txn` 1 (`ADJUSTMENT`) · `inventory_ledger_entry` 2 · `outbox_event` 1
- **R** `count_session` FOR UPDATE 1 · `location` FOR SHARE 1 · `count_result` (차이 계산) · `stock_balance` FOR UPDATE 1
- **U** `location` 1 (표시 해제가 먼저) · `stock_balance` 1 (−1) · `count_session` 1 (`CONFIRMED`, `resolution_txn_id`)
- **D** 없음

**확인.** 실재고 29로 정정되고, 정정 거래가 `ADJUSTMENT`로 남으며 세션이 그 거래를 가리킨다.

### UC-C06~C08 · 오차를 넘는 차이

**상황.** 또 실사해 24개를 셌다. 차이 −5개는 오차를 넘으므로 자동 정정하지 않는다. 세션을 `REVIEW`로 바꾸고 `inventory_issue`를 열어 사람에게 넘긴다. 표시는 유지되므로 검토가 끝날 때까지 이 로케이션은 계속 잠겨 있고, 그동안 출고는 거절된다. 관리자가 확인 후 정정을 승인하면 그때 표시를 지우고 조정을 포스팅한다.

- **제출** — **C** `count_result` 1 · `inventory_issue` 1 (`COUNT_VARIANCE`, `OPEN`) · `idempotency_record` 1 / **U** `count_session` 1 (`OPEN` → `REVIEW`, `submitted_at`) / `location` 표시 **유지**
- **정정** — **C** `inventory_txn` 1 · `inventory_ledger_entry` 2 · `outbox_event` 1 · `idempotency_record` 1 / **U** `location` 1 (표시 해제) · `stock_balance` 1 (−5) · `count_session` 1 (`CONFIRMED`) · `inventory_issue` 1 (`RESOLVED`, `resolved_txn_id`)
- **D** 없음

**확인.** `REVIEW` 상태에서 실재고는 29 그대로이고 출고는 거절된다. 정정 후 실재고 24, 표시 해제, 이슈 `RESOLVED`.

### UC-C09 · 중단

**상황.** 작업자가 다른 구역과 헷갈려 잘못 센 것 같다고 해서, 정정 없이 세션을 버리고 재실사하기로 한다.

- **R** `count_session` FOR UPDATE 1
- **U** `location` 1 (표시 해제) · `count_session` 1 (`ABANDONED`, `closed_at`)
- **C/D** 없음

**확인.** 표시가 해제되고 실재고는 손대지 않는다. `CHECK (status <> 'ABANDONED' OR resolution_txn_id IS NULL)`이 "중단인데 정정 거래가 달린" 상태를 막는다.

### UC-C10~C12 · 판매 가능 수량과 할당 정책

**상황.** `A-01-01-1`(티셔츠 52개)의 실사를 시작하면 그 재고는 지금 출고할 수 없고 실사 결과에 따라 줄어들 수도 있다. 코어는 정책을 강제하지 않고 `v_sellable_stock`에서 판매 가능 수량과 실사 중 수량을 나눠 제공하며, 채널 연동이 이 뷰를 읽어 SKU별 정책을 적용한다. 할당 커맨드의 `allowInCount`는 채널 노출 정책과 같은 값을 따라야 한다 — 노출은 포함하면서 할당은 제외하면 채널에서 팔린 주문이 할당 단계에서 실패한다.

- **R** `v_sellable_stock` (`location.count_session_id` 기준으로 `FILTER` 분리) · `stock_balance` FOR UPDATE (할당 후보)
- **C** `allocation` (허용한 경우에만) · `outbox_event`
- **U** `stock_balance.allocated_qty`
- **D** 없음

**확인.** 실사 시작과 동시에 판매 가능 수량이 76 → 24로, 실사 중 수량이 0 → 52로 갈린다. `allowInCount = false`로 30개를 요청하면 가용 24개뿐이라 `INSUFFICIENT_STOCK`이고, `true`면 실사 중 로케이션까지 후보에 들어와 성공한다. 어느 쪽이든 실재고와 원장은 그대로다 — 이 결정은 정합이 아니라 판매 정책의 문제다.

## D. 불변식 위반은 정상적으로 실패한다

앱 검사를 통과해버린 상황을 가정해, DB 제약이 최후 방어선으로 서는지 본다. 스크립트는 [`db/usecases/D-invariants.sql`](../db/usecases/D-invariants.sql)과 [`db/usecases/D2-master-constraints.sql`](../db/usecases/D2-master-constraints.sql)에 있다.

| # | 상황 | 기대 | 실제 |
|---|---|---|---|
| UC-D01a | 가용 24개인 로케이션에서 999개 출고 | 앱이 먼저 막는다 | `INSUFFICIENT_STOCK` |
| UC-D01b | 앱을 건너뛰고 실재고를 −1로 UPDATE | I1 CHECK | `23514 stock_balance_check` |
| UC-D02a | 실재고보다 큰 할당량으로 UPDATE | I2 CHECK | `23514 stock_balance_check` |
| UC-D02b | 할당량을 음수로 UPDATE | I2 CHECK | `23514 stock_balance_check` |
| UC-D03b | 할당 12개가 걸린 재고 15개를 조정으로 전량 차감 | 가용 초과로 거절 | `INSUFFICIENT_STOCK` |
| UC-D03c | 할당을 먼저 해제한 뒤 같은 조정 | 통과 | 실재고 0 |
| UC-D04a | (SKU, 로트) 합계가 −5/+3인 커맨드 | 커맨드 검증이 막는다 | `NOT_ZERO_SUM` |
| UC-D04b | 기존 거래에 한쪽 원장만 추가 | I3 지연 제약 트리거 | `inventory_txn 13 is not zero-sum` |
| UC-D05a | `app_rw`가 원장 UPDATE | I4 권한 거부 | `42501 permission denied` |
| UC-D05b | `app_rw`가 원장 DELETE | I4 권한 거부 | `42501 permission denied` |
| UC-D05c | `app_rw`가 거래 헤더 UPDATE | 권한 거부 | `42501 permission denied` |
| UC-D06 | 같은 멱등 키로 거래를 두 번 생성 | I6 UNIQUE | `23505 inventory_txn_idem_key_key` |
| UC-D07 | 다른 SKU의 로트로 잔액 행 생성 | I9 복합 외래키 | `23503 stock_balance_lot_id_sku_id_fkey` |
| UC-D08 | 다른 창고의 로케이션으로 잔액 행 생성 | I9 복합 외래키 | `23503 stock_balance_location_id_warehouse_id_fkey` |
| UC-D09 | 사유 코드 없는 조정 | CHECK | `23514 inventory_txn_check` |
| UC-D10 | `REVERSAL`인데 원거래 참조 없음 | CHECK | `23514 inventory_txn_check1` |
| UC-D11 | `REVERSAL`이 아닌데 원거래 참조 있음 | CHECK | `23514 inventory_txn_check1` |
| UC-D12 | `actor_type = 'AI'`로 거래 기록 | CHECK (AI는 실행 주체가 될 수 없다) | `23514 inventory_txn_actor_type_check` |
| UC-D13 | 같은 거래를 두 번 역분개 | UNIQUE | `23505 inventory_txn_reverses_txn_id_key` |
| UC-D14 | 이미 출고된 입고를 역분개 | 음수 방지로 실패 | `INSUFFICIENT_STOCK` |
| UC-D15 | 변동량 0인 원장 | CHECK | `23514 inventory_ledger_entry_qty_delta_check` |
| UC-D17 | 수량 0짜리 할당 | CHECK | `23514 allocation_qty_check` |
| UC-D18 | `CONFIRMED`인데 닫힌 시각이 없는 세션 | CHECK | `23514 count_session_check1` |
| UC-D16a | `app_admin`이 신규 보관 로케이션 생성 | 허용 | 통과 |
| UC-D16b | 정의되지 않은 로케이션 유형 `FREEZER` | CHECK | `23514 location_location_type_check` |
| UC-D19 | 같은 창고에 같은 로케이션 코드 | UNIQUE | `23505 location_warehouse_id_code_key` |
| UC-D20 | 같은 SKU에 같은 로트 번호 | UNIQUE | `23505 lot_sku_id_lot_no_key` |
| UC-D21 | `EXECUTED`인데 실행 거래가 없는 제안 | I8 CHECK | `23514 action_proposal_check` |
| UC-D22 | 로케이션 표시가 다른 로케이션의 세션을 가리킴 | I10 복합 외래키 | `23503 location_count_session_id_id_fkey` |
| UC-D23 | 로트 미관리 SKU에 `DEFAULT` 로트를 하나 더 | I11 UNIQUE | `23505 lot_sku_id_lot_no_key` |
| UC-D24 | 로트 미관리 SKU에 `DEFAULT`가 아닌 로트 번호 | I11 CHECK | `23514 lot_check` |
| UC-D25 | 로트의 `lot_managed`가 SKU와 불일치 | I11 복합 외래키 | `23503 lot_sku_id_lot_managed_fkey` |
| UC-D26 | `app_rw`가 로케이션을 만듦 | 권한 거부 | `42501 permission denied` |
| UC-D27 | `app_rw`가 SKU 이름을 고침 | 권한 거부 | `42501 permission denied` |
| UC-D28 | `app_rw`가 실사 표시 컬럼만 변경 | 허용 | 통과 |
| UC-D29 | `app_rw`가 로케이션 유형을 변경 | 권한 거부 | `42501 permission denied` |

**UC-D14가 실패하는 것이 맞다.** 이미 출고된 입고를 되돌리려면 재고가 음수가 돼야 하므로, 이 실패가 "출고를 먼저 정리해야 한다"는 정정 순서를 알려준다. 같은 이유로 UC-D03b도 실패하고, 할당 해제를 먼저 한 UC-D03c는 통과한다.

**UC-D23~D25는 검증 결과로 새로 생긴 제약(I11)이다.** 원래 스키마에서는 `sku.lot_managed`가 아무것도 강제하지 않아, 로트 미관리 SKU에 로트를 여러 개 만들어도 막히지 않았다. 그러면 같은 재고가 두 잔액 행으로 갈라진다. `lot_managed`를 `lot`에 복사해 복합 외래키로 묶고 CHECK를 걸어 DB 제약으로 올렸다.

**UC-D26~D29는 권한 경계다.** 마스터 데이터 쓰기는 `app_admin`의 일이고 `app_rw`는 읽기만 한다. 유일한 예외가 `location.count_session_id`인데, 실사 표시는 쓰기 경로의 일부이기 때문이다. 컬럼 단위 GRANT로 그 한 칸만 열어뒀다.

**UC-D04b는 지연 제약이라 특별하다.** 트리거가 커밋 시점에 검사하므로, 검증에서는 `SET CONSTRAINTS ALL IMMEDIATE`로 즉시 검사로 바꿔 잡았다. 운영에서는 커밋이 거부되는 형태로 나타난다.

## E. AI 제안과 권한 경계

스크립트는 [`db/usecases/E-proposal.sql`](../db/usecases/E-proposal.sql), [`E2-ai-permissions.sql`](../db/usecases/E2-ai-permissions.sql), [`E3-proposer-permissions.sql`](../db/usecases/E3-proposer-permissions.sql)에 있다.

### UC-E01 · 제안 생성

**상황.** 리밸런싱 에이전트가 "만료 10-10인 콜드브루 로트가 상단 랙에 있어 소진이 느리다. 픽존으로 20개 당기자"는 이동을 제안한다. 에이전트는 무엇을 근거로 봤는지(어느 잔액 행인지)만 지정하고, 서버가 제안을 저장하는 순간 DB에서 직접 조회해 `basis_snapshot`에 값과 조회 시각을 기록한다. LLM이 옮겨 적은 숫자는 틀릴 수 있기 때문이다.

- **C** `action_proposal` 1 (`PENDING`, `basis_snapshot`, `agent_meta`, `expires_at`)
- **R** `stock_balance`·`location`·`sku`·`lot` (근거 수치를 서버가 직접 조회)
- **U/D** 없음 — 제안 생성은 재고를 건드리지 않는다

**확인.** `basis_snapshot.observations[0].available_qty`가 실제 가용 수량 70으로 채워진다. 에이전트가 재시도로 같은 제안을 다시 넣으면 `uq_proposal_pending`이 막아 대기 중 제안은 하나만 남는다.

### UC-E03 · 승인과 실행

**상황.** 관리자가 제안을 승인한다. 제안 행을 `FOR UPDATE`로 잠가 승인 연타와 동시 승인을 직렬화하고, 만료·상태를 확인한 뒤 `basis_snapshot`을 다시 검증한다. 통과하면 `proposal:{id}`를 멱등 키로 사람이 만든 커맨드와 똑같은 검증·쓰기 경로를 탄다.

- **R** `action_proposal` FOR UPDATE 1 · `stock_balance` (basis 재검증) · 포스팅 경로의 읽기 전부
- **C** `idempotency_record` 1 (`proposal:{id}`) · `inventory_txn` 1 (`proposal_id` 연결) · `inventory_ledger_entry` 2 · `outbox_event` 1
- **U** `stock_balance` 2 · `action_proposal` 1 (`EXECUTED`, `decided_by`, `executed_txn_id`)
- **D** 없음

**확인.** 실행 거래의 `actor_id`는 승인자 `user:choi.dw`다 — AI는 실행 주체가 될 수 없다. 거래가 `proposal_id`로 제안을 역참조하고, 제안은 `executed_txn_id`로 거래를 가리킨다. 같은 제안을 다시 승인해도 `ALREADY_DECIDED`로 끝나고 거래는 1건 그대로다.

### UC-E05 · 근거가 무너진 제안

**상황.** 픽존 보충 제안을 올린 뒤, 승인 전에 40개가 출고돼 근거로 삼은 가용 수량이 크게 달라졌다. 승인 시 basis 재검증이 허용 오차(10%)를 넘는 차이를 발견하면 원장에 아무것도 쓰지 않고 제안을 `STALE`로 바꿔 커밋한다. 이 실패를 예외로 던지면 트랜잭션이 롤백 전용으로 표시되어 `STALE` 기록까지 사라지므로, 예외가 아닌 결과 값으로 돌려줘야 한다.

- **R** `action_proposal` FOR UPDATE 1 · `stock_balance` (관측값 비교)
- **U** `action_proposal` 1 (`STALE`, `decided_by`, `decided_at`)
- **C** 없음 — `inventory_txn`도 `inventory_ledger_entry`도 만들어지지 않는다
- **D** 없음

**확인.** 제안은 `STALE`이고 `decided_by`가 남는다(커밋이 살아남았다는 증거). `proposal:{id}` 멱등 키로 만들어진 거래는 0건이다. 에이전트는 최신 데이터로 다시 제안하면 된다.

### UC-E06 · 만료된 제안

**상황.** 유효기간이 지난 제안을 승인하려 한다. 재고 상태와 무관하게 `EXPIRED`로 닫힌다.

- **R** `action_proposal` FOR UPDATE 1 / **U** `action_proposal` 1 (`EXPIRED`) / **C·D** 없음

### UC-E07~E15 · 권한 경계

**상황.** AI 계정이 할 수 있는 일과 없는 일을 계정 단위로 확인한다. 창고 접근 권한은 LLM에게 맡기지 않고 도구 내부에서 강제하지만, 그 아래에 DB 권한이 한 겹 더 있어야 한다.

| # | 계정 | 시도 | 결과 |
|---|---|---|---|
| UC-E07 | `ai_ro` | 조회 뷰 `v_available_stock` 읽기 | 허용 |
| UC-E08 | `ai_ro` | `stock_balance` 직접 읽기 | `42501` 거부 |
| UC-E09 | `ai_ro` | `inventory_ledger_entry` 직접 읽기 | `42501` 거부 |
| UC-E10 | `ai_ro` | 재고 직접 변경 | `42501` 거부 |
| UC-E11 | `ai_ro` | 제안 생성 | `42501` 거부 |
| UC-E12 | `ai_proposer` | 제안 생성 | 허용 |
| UC-E13 | `ai_proposer` | 제안을 `EXECUTED`로 변경 | `42501` 거부 |
| UC-E14 | `ai_proposer` | 재고 직접 변경 | `42501` 거부 |
| UC-E15 | `ai_proposer` | 원장에 직접 쓰기 | `42501` 거부 |

AI가 스키마를 알고 SQL을 만들어내더라도 계정 권한이 실행을 막는다. 조회는 테이블이 아니라 뷰로만 열려 있어 노출 컬럼도 통제된다.

## F. 정합 검증 배치

동시성 시나리오까지 모두 끝난 최종 상태에서 다섯 가지 검증을 돌린다. 스크립트는 [`db/usecases/F-reconciliation.sql`](../db/usecases/F-reconciliation.sql)에 있다.

**상황.** 하루치 거래(입고·적치·할당·출고·반품·조정·역분개·센터 간 이동·실사 정정·제안 실행·동시성 400여 건)를 처리한 뒤, 잔액과 원장이 어긋나지 않았는지 확인한다. 각 쿼리는 단일 SQL이라 하나의 스냅샷에서 실행되므로 운영 중에 돌려도 거짓 불일치가 나오지 않는다.

- **R** `stock_balance`·`inventory_ledger_entry`·`allocation`·`location`·`count_session` (전부 읽기 전용)
- **C/U/D** 없음 — 불일치가 나와도 자동 보정하지 않는다

**확인.** ①~⑤ 전부 0건.

그다음 일부러 데이터를 틀어놓고 각 검증이 잡아내는지 본다.

| # | 틀어놓은 것 | 잡아낸 검증 |
|---|---|---|
| UC-F02 | 잔액 투영을 몰래 3개 늘림 | ① 잔액 = 원장 합계 |
| UC-F03 | ACTIVE 할당 없이 `allocated_qty`만 올림 | ② 할당량 = ACTIVE 할당 합계 |
| UC-F04 | 원장의 `on_hand_after` 한 줄을 조작 | ③ 원장 체인 — **끊긴 지점이 조작한 그 줄을 정확히 가리킨다** |
| UC-F05 | 공급사 가상 로케이션에 잔액 행 생성 | ④ 가상 로케이션 잔액 행 |
| UC-F06 | 세션은 닫혔는데 로케이션 표시만 남김 | ⑤ 실사 표시 |

**UC-F04가 이 설계의 핵심 장치다.** 같은 잔액 키의 원장이 항상 그 잔액 행의 잠금 아래에서 기록되므로 id 순서가 곧 실제 적용 순서이고, 체인이 처음 끊긴 행이 틀어짐이 시작된 거래를 정확히 짚는다. 이 거래 id가 AI 원인 분석의 출발점이 된다.

**UC-F05는 DB가 막지 못하는 것을 배치가 잡는 사례다.** 가상 로케이션에 잔액 행을 만드는 것을 막는 제약은 없다(가상 여부가 생성 컬럼이라 CHECK로 참조할 수 없다). 앱이 규칙을 지키고 배치가 사후에 확인하는 구조다.

각 항목은 원복 후 다시 검증해 ①~⑤ 전부 0건으로 돌아오는 것까지 확인했다.

## G. 동시성

여러 커넥션을 병렬로 띄워 잠금 규칙을 확인한다. 스크립트는 [`db/run-concurrency.sh`](../db/run-concurrency.sh)에 있다.

| # | 상황 | 기대 | 실제 |
|---|---|---|---|
| UC-G01 | 가용 50개인 SKU에 20커넥션 × 10회 = 200회 동시 할당 (1개씩) | 성공 정확히 50건 | 50건, `allocated_qty` = 50, 데드락 0 |
| UC-G02 | 같은 멱등 키로 10커넥션 동시 출고 | 거래 1건 | 거래 1건, 실재고는 3개만 감소 |
| UC-G03 | `A-01-01-1`↔`A-01-02-1` 반대 방향 이동 10커넥션 × 20회 | 데드락 0건 | 데드락 0건, 두 로케이션 합계 불변 |
| UC-G04 | 같은 로케이션에 10커넥션 동시 실사 시작 | 세션 1개 | 1개 |

**UC-G01**은 잔액 행 `FOR UPDATE`가 동시 차감을 직렬화한다는 것을, **UC-G02**는 멱등 키 선점이 경합에서도 한 번만 통과한다는 것을 보여준다. 두 번째 요청은 첫 번째 트랜잭션이 끝날 때까지 대기했다가 저장된 결과를 받는다.

**UC-G03이 잠금 순서 규칙의 증거다.** 모든 쓰기 경로가 `location`을 id 오름차순으로 잠그므로, A→B와 B→A가 동시에 들어와도 잠금 순서가 역전되지 않아 데드락이 생기지 않는다. 비즈니스 방향과 잠금 순서를 분리한 설계가 실제로 동작한다.

## 검증에서 나온 변경

검증 결과를 놓고 결정한 것들이다. 반영한 것, 뒤로 미룬 것, 그대로 두기로 한 것으로 나눠 적는다.

### 반영한 것

**스키마 정본을 마이그레이션으로 옮겼다.** 문서에 245줄짜리 DDL이 있고 검증본이 그 복사본이었는데, 구현이 시작되면 마이그레이션이 세 번째 사본이 된다. 사본이 셋이면 한 글자 차이는 아무도 못 찾는다. 이제 [`db/migration/V1__init.sql`](../db/migration/V1__init.sql)이 정본이고, [데이터 모델](02-data-model.md)은 설계 의도가 드러나는 부분만 발췌한다.

**`sku.base_uom`과 `sku.expiry_managed`를 제거하고, `sku.lot_managed`는 DB 제약으로 올렸다.** 셋 다 검증에서 한 번도 참조되지 않았고 강제하는 제약도 없었다. 코어는 EA 정수만 다루므로 `base_uom`은 할 일이 없고, FEFO는 `lot.expiry_date`의 NULL 여부만으로 동작하므로 `expiry_managed`도 마찬가지였다. `lot_managed`만 남긴 이유는 이것만 정합에 직접 영향이 있기 때문이다 — 로트 미관리 SKU에 로트가 두 개 생기면 같은 재고가 두 잔액 행으로 갈라진다. `lot`에 값을 복사하고 복합 외래키로 묶어 CHECK가 참조할 수 있게 했다(I11).

**`action_proposal.evidence_refs`를 제거했다.** `basis_snapshot`(서버가 채운 수치)과 `rationale`(사람이 읽을 근거)이 이미 있어, 근거 필드가 셋이면 구현할 때마다 어디에 넣을지 고민하게 된다.

**`app_admin` 롤을 신설했다.** 원래 권한 경계 표에는 마스터 데이터를 누가 쓰는지가 없었다. 로케이션을 하나 잘못 만들면 재고가 유령 로케이션으로 들어가고, 되돌리려면 조정 거래가 아니라 마이그레이션이 필요하다. 사고의 성격이 달라서 일상 쓰기 경로와 분리했다.

**`inventory_txn (source_type, source_ref)` 인덱스를 추가했다.** "이 발주의 입고 이력", "이 주문의 출고"는 1단계부터 바로 쓰이는 조회인데 인덱스가 없었다.

**`allowInCount`와 실사 허용 오차에서 기본값을 뺐다.** 둘 다 호출자나 설정이 명시해야 하는 값이다. 특히 `allowInCount`는 채널 노출 정책과 짝을 맞춰야 하는데, 기본값이 있으면 노출만 바뀐 날 조용히 주문이 실패한다.

**가상 로케이션을 창고마다 두는 것을 문서에 명시했다.** `location.warehouse_id`가 NOT NULL이라 선택의 여지가 없었는데 어디에도 적혀 있지 않아, 구현자가 멈출 지점이었다.

### 미룬 것

**`inventory_issue`의 `count_session_id`와 처리 이력 컬럼** — 3단계로 미뤘다. 지금은 실사 차이 이슈를 `detail->>'countSessionId'` JSONB 문자열 비교로 찾아야 하고 외래키도 없으며, 상태가 세 단계인데 누가 언제 처리했는지가 남지 않는다. 이 테이블을 실제로 쓰기 시작하는 단계에 함께 넣는다. 인덱스도 그때 붙인다(이 테이블에는 지금 기본키 말고 인덱스가 하나도 없다).

**`inventory_ledger_entry.occurred_at` 비정규화** — 4단계로 미뤘다. 수요 예측 집계가 가장 큰 테이블에 매번 조인을 붙이는 구조지만, 비정규화는 되돌리기 어렵고 이 규모에서는 병목이 아니다. 실제 집계 쿼리를 측정한 뒤 판단한다.

**`action_proposal (status, created_at)` 인덱스** — 승인 대기 목록 조회용이라 4단계에.

### 그대로 두기로 한 것

**`idempotency_record`의 보존 정책** — 두지 않는다. 행이 계속 쌓이는 것은 맞지만, 이 규모에서 정리 배치와 보존 기간 관리가 주는 이득보다 추가되는 운영 복잡도가 크다. 실제로 문제가 되면 그때 파티셔닝을 검토한다.

**`stock_balance.warehouse_id`와 `inventory_ledger_entry.warehouse_id`** — `location_id`로 유도 가능한 중복처럼 보이지만, 복합 외래키 `(location_id, warehouse_id)`로 I9를 강제하는 장치이자 `idx_balance_wh_sku`의 선두 컬럼이다. UC-D08이 실제로 이 제약에 걸렸다. 의도된 비정규화다.

**`count_session.submitted_at`과 짝 CHECK** — `REVIEW`/`CONFIRMED`인데 제출 시각이 없는 상태를 막는다. UC-D18이 걸렸다.

**`location.is_sellable` 생성 컬럼** — 뷰 두 개가 모두 쓴다. 다만 "`STORAGE`만 판매 가능"이라는 정책이 DDL에 굳어 있어, 판매 가능한 로케이션 유형이 늘어나면 마이그레이션이 필요하다. 지금 바꿀 일은 아니고 알고만 있으면 된다.

**검증 하네스** — 유지한다. Java 구현이 들어오면 포스팅 로직은 중복되지만, 스키마만 바꿨을 때 Java 컴파일 없이 몇 초 만에 184건을 다시 돌릴 수 있다. 유스케이스 시나리오는 [테스트 전략](08-testing-roadmap.md#테스트-전략)대로 Testcontainers 테스트로 옮긴다.

### 검증 중 걸러낸 것

처음에는 I11을 위해 `CREATE UNIQUE INDEX uq_lot_single_default ON lot (sku_id) WHERE NOT lot_managed`도 넣었다가 뺐다. `CHECK (lot_managed OR lot_no = 'DEFAULT')`가 미관리 SKU의 로트 번호를 `DEFAULT`로 고정하고, `UNIQUE (sku_id, lot_no)`가 SKU당 `DEFAULT`를 하나로 제한하므로, 두 제약만으로 이미 로트가 하나로 고정된다. 부분 인덱스는 아무것도 더하지 않았다.
