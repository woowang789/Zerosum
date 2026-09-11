-- 재고관리 코어 스키마 (정본)
--
-- 이 파일이 스키마의 유일한 정본이다. docs/02-data-model.md는 설계 의도를 설명하고
-- 핵심 부분만 발췌하며, 전체 정의는 여기에 있다.
--
-- 실행: db/run.sh (검증) 또는 Flyway 마이그레이션
-- 불변식 I1~I11은 docs/01-principles.md 참고

-- ── 마스터 ─────────────────────────────────────────────────────
CREATE TABLE warehouse (
  id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code  VARCHAR(20)  NOT NULL UNIQUE,
  name  VARCHAR(100) NOT NULL
);

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

CREATE TABLE sku (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code            VARCHAR(40)  NOT NULL UNIQUE,
  name            VARCHAR(200) NOT NULL,
  lot_managed     BOOLEAN NOT NULL DEFAULT FALSE,
  UNIQUE (id, lot_managed)               -- 복합 외래키 참조용 (lot이 이 값을 따라간다)
);

-- 로트 미관리 SKU도 lot_no = 'DEFAULT' 로트를 하나 가진다.
-- 재고 키에 NULL이 섞이면 UNIQUE가 중복 잔액 행을 막지 못하기 때문이다.
-- lot_managed는 sku에서 복사해 복합 외래키로 묶는다. 값을 여기 두어야
-- "미관리 SKU는 DEFAULT 로트 하나뿐"을 CHECK와 부분 유니크 인덱스로 강제할 수 있다.
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

-- ── 잔액 투영 ─────────────────────────────────────────────────
-- 물리 로케이션만 행을 가진다 (가상 로케이션 행 금지는 앱 + 06-events-reconciliation.md ④로 확인)
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
CREATE INDEX idx_balance_wh_sku ON stock_balance (warehouse_id, sku_id);

-- ── 멱등성 ───────────────────────────────────────────────────
CREATE TABLE idempotency_record (
  idem_key      VARCHAR(100) PRIMARY KEY,
  command_type  VARCHAR(30)  NOT NULL,
  request_hash  CHAR(64)     NOT NULL,   -- 같은 키에 다른 본문이 오면 409
  result        JSONB,                   -- 같은 트랜잭션 안에서 채워진다
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ── 거래와 원장 ───────────────────────────────────────────────
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

CREATE INDEX idx_txn_source ON inventory_txn (source_type, source_ref);

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
CREATE INDEX idx_ledger_txn ON inventory_ledger_entry (txn_id);
CREATE INDEX idx_ledger_key ON inventory_ledger_entry (location_id, sku_id, lot_id, id);
CREATE INDEX idx_ledger_sku ON inventory_ledger_entry (sku_id, id);

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

-- ── 할당 ─────────────────────────────────────────────────────
CREATE TABLE allocation (
  id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  idem_key         VARCHAR(100) NOT NULL REFERENCES idempotency_record (idem_key),
  order_line_ref   VARCHAR(100) NOT NULL,
  balance_id       BIGINT  NOT NULL REFERENCES stock_balance (id),
  qty              INTEGER NOT NULL CHECK (qty > 0),
  status           VARCHAR(10) NOT NULL DEFAULT 'ACTIVE'
                     CHECK (status IN ('ACTIVE', 'CONSUMED', 'RELEASED')),
  consumed_txn_id  BIGINT REFERENCES inventory_txn (id),
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  closed_at        TIMESTAMPTZ,
  CHECK ((status = 'CONSUMED') = (consumed_txn_id IS NOT NULL)),
  CHECK ((status = 'ACTIVE')   = (closed_at IS NULL))
);
CREATE INDEX idx_alloc_active ON allocation (balance_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_alloc_order  ON allocation (order_line_ref);

-- ── 이벤트 발행 ──────────────────────────────────────────────
CREATE TABLE outbox_event (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  event_type     VARCHAR(50)  NOT NULL,    -- StockPosted, StockAllocated, IssueDetected ...
  partition_key  VARCHAR(100) NOT NULL,    -- Kafka 키 (예: sku_id)
  payload        JSONB        NOT NULL,
  created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
  published_at   TIMESTAMPTZ
);
CREATE INDEX idx_outbox_unpublished ON outbox_event (id) WHERE published_at IS NULL;

-- ── AI 지원 ──────────────────────────────────────────────────
CREATE TABLE action_proposal (
  id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  proposal_type    VARCHAR(30) NOT NULL,     -- MOVE, TRANSFER, ADJUSTMENT, RECEIPT_DRAFT ...
  command_payload  JSONB NOT NULL,           -- 승인 시 그대로 포스팅 서비스에 들어갈 커맨드
  basis_snapshot   JSONB NOT NULL,           -- 서버가 제안 저장 시 DB에서 직접 조회한 근거 수치 (07-ai-integration.md)
  rationale        TEXT  NOT NULL,           -- 사람이 읽을 근거 요약
  proposed_by      VARCHAR(100) NOT NULL,    -- 'agent:rebalancer' 같은 에이전트 식별자
  agent_meta       JSONB,                    -- 모델명, 프롬프트 버전 (AI 품질 평가용)
  status           VARCHAR(10) NOT NULL DEFAULT 'PENDING'
                     CHECK (status IN ('PENDING', 'EXECUTED', 'REJECTED', 'EXPIRED', 'STALE')),
  decided_by       VARCHAR(100),
  decided_at       TIMESTAMPTZ,
  expires_at       TIMESTAMPTZ NOT NULL,
  executed_txn_id  BIGINT UNIQUE REFERENCES inventory_txn (id),  -- I8
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK ((status = 'EXECUTED') = (executed_txn_id IS NOT NULL)),
  CHECK ((status = 'PENDING')  = (decided_at IS NULL))
);
-- 에이전트가 재시도로 같은 제안을 여러 번 넣어도 대기 중인 제안은 하나만 남는다
CREATE UNIQUE INDEX uq_proposal_pending
  ON action_proposal (proposal_type, (md5(command_payload::text)))
  WHERE status = 'PENDING';

ALTER TABLE inventory_txn
  ADD FOREIGN KEY (proposal_id) REFERENCES action_proposal (id);

CREATE TABLE inventory_issue (
  id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  issue_type       VARCHAR(30) NOT NULL,     -- PROJECTION_MISMATCH, ALLOCATION_MISMATCH, CHAIN_BREAK, COUNT_VARIANCE ...
  severity         VARCHAR(10) NOT NULL CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
  location_id      BIGINT REFERENCES location (id),
  sku_id           BIGINT REFERENCES sku (id),
  lot_id           BIGINT REFERENCES lot (id),
  detail           JSONB NOT NULL,           -- 기대값, 실제값, 최초 불일치 원장 id
  ai_analysis      JSONB,                    -- 원인 후보와 근거 txn id
  status           VARCHAR(10) NOT NULL DEFAULT 'OPEN'
                     CHECK (status IN ('OPEN', 'ACKED', 'RESOLVED')),
  resolved_txn_id  BIGINT REFERENCES inventory_txn (id),
  detected_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE count_session (
  id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  location_id        BIGINT NOT NULL REFERENCES location (id),
  status             VARCHAR(10) NOT NULL DEFAULT 'OPEN'
                       CHECK (status IN ('OPEN', 'REVIEW', 'CONFIRMED', 'ABANDONED')),
  started_by         VARCHAR(100) NOT NULL,
  started_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
  submitted_at       TIMESTAMPTZ,
  closed_by          VARCHAR(100),
  closed_at          TIMESTAMPTZ,
  resolution_txn_id  BIGINT REFERENCES inventory_txn (id),   -- 차이를 정정한 거래 (조정 또는 이동)
  UNIQUE (id, location_id),                                  -- 복합 외래키 참조용
  CHECK (status NOT IN ('REVIEW', 'CONFIRMED') OR submitted_at IS NOT NULL),
  CHECK ((status IN ('CONFIRMED', 'ABANDONED')) = (closed_at IS NOT NULL)),
  CHECK (status <> 'ABANDONED' OR resolution_txn_id IS NULL)
);
-- I10: 로케이션당 진행 중인 실사는 하나
CREATE UNIQUE INDEX uq_count_session_active ON count_session (location_id)
  WHERE status IN ('OPEN', 'REVIEW');

-- I10: 로케이션 표시는 반드시 그 로케이션의 세션을 가리킨다
ALTER TABLE location
  ADD FOREIGN KEY (count_session_id, id) REFERENCES count_session (id, location_id);

CREATE TABLE count_result (
  id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  count_session_id  BIGINT  NOT NULL,
  location_id       BIGINT  NOT NULL,
  sku_id            BIGINT  NOT NULL,
  lot_id            BIGINT  NOT NULL,
  system_qty        INTEGER NOT NULL,     -- 제출 시점의 실재고 (세션 표시 이후 바뀔 수 없음, 05-count-session.md)
  counted_qty       INTEGER NOT NULL CHECK (counted_qty >= 0),
  counted_by        VARCHAR(100) NOT NULL,
  counted_at        TIMESTAMPTZ  NOT NULL,
  UNIQUE (count_session_id, sku_id, lot_id),
  FOREIGN KEY (count_session_id, location_id) REFERENCES count_session (id, location_id),
  FOREIGN KEY (lot_id, sku_id)                REFERENCES lot (id, sku_id)
);
