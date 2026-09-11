# 데이터 모델

## 테이블 구성

| 구분 | 테이블 | 역할 |
|---|---|---|
| 마스터 | `warehouse` | 창고 |
| 마스터 | `location` | 로케이션. 유형이 곧 재고 상태, 진행 중인 실사 표시 |
| 마스터 | `sku` | 상품. 로트 관리 여부 |
| 마스터 | `lot` | 로트. 로트 미관리 SKU도 DEFAULT 로트 1개 |
| 코어 | `stock_balance` | 잔액 투영. 잠금과 CHECK의 대상 |
| 코어 | `idempotency_record` | 모든 쓰기 커맨드의 멱등 키 |
| 코어 | `inventory_txn` | 거래 헤더. 누가, 왜, 언제 |
| 코어 | `inventory_ledger_entry` | 원장. 추가만 가능 |
| 코어 | `allocation` | 주문 라인별 할당 |
| 코어 | `outbox_event` | 이벤트 발행 대기열 |
| 코어 | `count_session` | 실사 세션. 진행 중이면 해당 로케이션의 포스팅을 막는다 |
| AI 지원 | `action_proposal` | AI·자동화 제안과 승인 상태 |
| AI 지원 | `inventory_issue` | 정합 불일치와 이상 탐지 결과 |
| AI 지원 | `count_result` | 실사 결과 |

가상 로케이션은 창고마다 하나씩 둔다. `location.warehouse_id`가 NOT NULL이라 전역 가상 로케이션을 만들 수 없고, 원장 줄마다 자기 `warehouse_id`를 가져야 복합 외래키 검증이 성립하기 때문이다. 창고별 입출고 집계가 자연스러워지는 것은 덤이다. 잔액 행을 두지 않으므로 공급사 로케이션이 창고 수만큼 늘어나도 잠금 병목은 생기지 않는다.

## 정의

**스키마 정본은 [`db/migration/V1__init.sql`](../db/migration/V1__init.sql)이다.** 아래는 설계 의도가 드러나는 부분만 발췌한 것이고, 전체 정의(인덱스, AI 지원 테이블, 실사 테이블)는 그 파일에 있다.

### 로케이션 — 유형이 곧 재고 상태

```sql
CREATE TABLE location (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  warehouse_id   BIGINT NOT NULL REFERENCES warehouse (id),
  code           VARCHAR(40) NOT NULL,
  location_type  VARCHAR(20) NOT NULL CHECK (location_type IN (
                   'RECEIVING', 'STORAGE', 'RETURN_HOLD', 'DAMAGED', 'TRANSIT',
                   'V_SUPPLIER', 'V_CUSTOMER', 'V_ADJUSTMENT')),
  is_virtual     BOOLEAN GENERATED ALWAYS AS
                   (location_type IN ('V_SUPPLIER', 'V_CUSTOMER', 'V_ADJUSTMENT')) STORED,
  is_sellable    BOOLEAN GENERATED ALWAYS AS (location_type = 'STORAGE') STORED,
  count_session_id BIGINT,               -- 진행 중인 실사 세션, NULL이면 잠금 없음 (FK는 count_session 뒤에서 추가)
  UNIQUE (warehouse_id, code),
  UNIQUE (id, warehouse_id)              -- 복합 외래키 참조용 (I9)
);
```

`is_virtual`과 `is_sellable`이 생성 컬럼이라 상태 판정이 한 곳에만 있다. 다만 "`STORAGE`만 판매 가능"이라는 정책이 DDL에 굳어 있어, 판매 가능한 로케이션 유형이 늘어나면 마이그레이션이 필요하다.

### 로트 — 미관리 SKU도 DEFAULT 로트 하나 (I11)

```sql
CREATE TABLE lot (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  sku_id       BIGINT NOT NULL REFERENCES sku (id),
  lot_managed  BOOLEAN NOT NULL,
  lot_no       VARCHAR(40) NOT NULL,
  expiry_date  DATE,
  UNIQUE (sku_id, lot_no),
  UNIQUE (id, sku_id),                   -- 복합 외래키 참조용 (I9)
  FOREIGN KEY (sku_id, lot_managed) REFERENCES sku (id, lot_managed),   -- I11
  CHECK (lot_managed OR lot_no = 'DEFAULT')                             -- I11
);
-- I11은 위 두 제약만으로 성립한다. 미관리 SKU의 로트는 lot_no가 DEFAULT여야 하고(CHECK),
-- DEFAULT는 SKU당 하나뿐이므로(UNIQUE), 결과적으로 로트가 하나로 고정된다.
```

`lot_managed`를 `sku`에서 복사해 복합 외래키로 묶는 것이 핵심이다. 값이 `lot` 쪽에 있어야 CHECK로 참조할 수 있고, 그래야 "로트 미관리 SKU는 DEFAULT 로트 하나뿐"이라는 규칙을 애플리케이션 관례가 아니라 DB 제약으로 만들 수 있다. 이 규칙이 깨지면 같은 재고가 두 잔액 행으로 갈라진다.

### 잔액 투영 — 잠금과 CHECK의 대상 (I1, I2, I9)

```sql
CREATE TABLE stock_balance (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  warehouse_id   BIGINT  NOT NULL,
  location_id    BIGINT  NOT NULL,
  sku_id         BIGINT  NOT NULL,
  lot_id         BIGINT  NOT NULL,
  on_hand_qty    INTEGER NOT NULL DEFAULT 0,
  allocated_qty  INTEGER NOT NULL DEFAULT 0,
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (location_id, sku_id, lot_id),
  FOREIGN KEY (location_id, warehouse_id) REFERENCES location (id, warehouse_id),
  FOREIGN KEY (lot_id, sku_id)            REFERENCES lot (id, sku_id),
  CHECK (on_hand_qty >= 0),                                      -- I1
  CHECK (allocated_qty >= 0 AND allocated_qty <= on_hand_qty)    -- I2
);
```

`warehouse_id`는 `location_id`로 유도할 수 있는 중복처럼 보이지만 의도된 비정규화다. 복합 외래키 `(location_id, warehouse_id)`가 "로케이션은 해당 창고 소속"을 강제하고, `idx_balance_wh_sku`의 선두 컬럼이기도 하다.

### 거래와 원장 — 추가만 가능 (I3, I4, I6)

```sql
CREATE TABLE inventory_txn (
  id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  idem_key         VARCHAR(100) NOT NULL UNIQUE
                     REFERENCES idempotency_record (idem_key),       -- I6
  txn_type         VARCHAR(20)  NOT NULL CHECK (txn_type IN (
                     'RECEIPT', 'MOVE', 'SHIPMENT', 'RETURN', 'ADJUSTMENT',
                     'TRANSFER_OUT', 'TRANSFER_IN', 'REVERSAL')),
  source_type      VARCHAR(20),                 -- PO, ORDER, COUNT, PROPOSAL ...
  source_ref       VARCHAR(100),                -- 발주번호, 주문번호, 실사 id ...
  reason_code      VARCHAR(30),
  actor_type       VARCHAR(10)  NOT NULL
                     CHECK (actor_type IN ('USER', 'SYSTEM')),     -- AI는 실행 주체가 될 수 없다
  actor_id         VARCHAR(100) NOT NULL,
  proposal_id      BIGINT,                      -- AI 제안에서 출발한 거래면 연결 (FK는 아래에서 추가)
  reverses_txn_id  BIGINT UNIQUE REFERENCES inventory_txn (id),    -- 같은 거래를 두 번 역분개할 수 없다
  occurred_at      TIMESTAMPTZ NOT NULL,        -- 현장에서 실제 일어난 시각
  recorded_at      TIMESTAMPTZ NOT NULL DEFAULT now(),  -- 시스템에 기록된 시각
  CHECK (txn_type <> 'ADJUSTMENT' OR reason_code IS NOT NULL),
  CHECK ((txn_type = 'REVERSAL') = (reverses_txn_id IS NOT NULL))
);

CREATE TABLE inventory_ledger_entry (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  txn_id         BIGINT  NOT NULL REFERENCES inventory_txn (id),
  warehouse_id   BIGINT  NOT NULL,
  location_id    BIGINT  NOT NULL,
  sku_id         BIGINT  NOT NULL,
  lot_id         BIGINT  NOT NULL,
  qty_delta      INTEGER NOT NULL CHECK (qty_delta <> 0),
  on_hand_after  INTEGER,                       -- 물리 로케이션만 기록, 가상 로케이션은 NULL
  FOREIGN KEY (location_id, warehouse_id) REFERENCES location (id, warehouse_id),
  FOREIGN KEY (lot_id, sku_id)            REFERENCES lot (id, sku_id)
);

-- I3: 거래의 (SKU, 로트)별 합계 0을 커밋 시점에 강제한다
CREATE FUNCTION assert_txn_zero_sum() RETURNS trigger AS $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM inventory_ledger_entry
    WHERE txn_id = NEW.txn_id
    GROUP BY sku_id, lot_id
    HAVING SUM(qty_delta) <> 0
  ) THEN
    RAISE EXCEPTION 'inventory_txn % is not zero-sum', NEW.txn_id;
  END IF;
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_ledger_zero_sum
  AFTER INSERT ON inventory_ledger_entry
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION assert_txn_zero_sum();

-- I4: 테이블 소유자는 migrator. 애플리케이션 계정에는 추가와 조회만 허용한다
GRANT SELECT, INSERT ON inventory_txn, inventory_ledger_entry TO app_rw;
```

`actor_type`이 `USER`와 `SYSTEM`뿐인 것이 AI 경계의 DB 강제다. AI는 제안만 하고 실행 주체가 될 수 없다. 지연 제약 트리거는 커밋 시점에 (SKU, 로트)별 합계 0을 확인하며, 애플리케이션 계정에 UPDATE·DELETE 권한을 주지 않는 것이 원장 불변의 마지막 장치다.
