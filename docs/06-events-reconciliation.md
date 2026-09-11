# 이벤트 발행과 정합 검증

## 이벤트 발행

재고 변화는 트랜잭셔널 아웃박스로 내보낸다. 포스팅과 같은 트랜잭션에서 `outbox_event`를 기록하고, 별도 릴레이가 이를 발행한다. DB 커밋과 Kafka 발행을 따로 하면 둘 중 하나만 성공하는 순간 AI 기능이 보는 재고와 실제 재고가 어긋난다.

```sql
-- 릴레이 한 사이클 (처음엔 단일 인스턴스)
SELECT id, event_type, partition_key, payload
FROM outbox_event
WHERE published_at IS NULL
ORDER BY id
LIMIT 100;

-- Kafka 발행 (key = partition_key) 후 브로커 확인을 받은 id만 갱신
UPDATE outbox_event SET published_at = now() WHERE id = ANY(:publishedIds);
```

발행 후 `published_at`을 갱신하기 전에 릴레이가 죽으면 같은 이벤트가 다시 발행되므로 전달 보장은 최소 한 번이다. 소비자는 이벤트 id로 중복을 걸러야 한다.

"마지막으로 읽은 id 이후"를 커서로 쓰는 방식은 피한다. id는 INSERT 시점에 정해지지만 커밋은 그보다 늦을 수 있어서, 작은 id가 나중에 커밋되면 커서가 이미 지나간 뒤라 영영 건너뛴다. `published_at IS NULL` 조건으로 조회하면 이 문제가 없다. 릴레이를 여러 대로 늘릴 때는 `FOR UPDATE SKIP LOCKED`를 추가한다.

처음부터 Kafka를 둘 필요는 없다. 릴레이가 같은 애플리케이션 안의 소비자(이상 탐지 등)를 직접 호출하는 것으로 시작하고, 나중에 발행 대상만 Kafka로 바꾸면 된다.

## 정합 검증 배치

```sql
-- ① 잔액 투영 검증 (I4): 잔액과 원장 합계가 다른 키
WITH ledger_sum AS (
  SELECT e.location_id, e.sku_id, e.lot_id, SUM(e.qty_delta) AS ledger_qty
  FROM inventory_ledger_entry e
  JOIN location l ON l.id = e.location_id
  WHERE NOT l.is_virtual
  GROUP BY e.location_id, e.sku_id, e.lot_id
)
SELECT location_id, sku_id, lot_id, b.on_hand_qty, s.ledger_qty
FROM stock_balance b
FULL JOIN ledger_sum s USING (location_id, sku_id, lot_id)
WHERE COALESCE(b.on_hand_qty, 0) <> COALESCE(s.ledger_qty, 0);

-- ② 할당 검증 (I5): 할당량과 ACTIVE 할당 합계가 다른 잔액 행
SELECT b.id AS balance_id, b.allocated_qty, COALESCE(SUM(a.qty), 0) AS active_qty
FROM stock_balance b
LEFT JOIN allocation a ON a.balance_id = b.id AND a.status = 'ACTIVE'
GROUP BY b.id, b.allocated_qty
HAVING b.allocated_qty <> COALESCE(SUM(a.qty), 0);

-- ③ 원장 체인 검증 (I7): 직전 잔량 + 변동 ≠ 기록된 잔량인 지점
SELECT id AS ledger_entry_id, txn_id, location_id, sku_id, lot_id, on_hand_after, expected
FROM (
  SELECT e.*,
         COALESCE(LAG(e.on_hand_after) OVER w, 0) + e.qty_delta AS expected
  FROM inventory_ledger_entry e
  WHERE e.on_hand_after IS NOT NULL
  WINDOW w AS (PARTITION BY e.location_id, e.sku_id, e.lot_id ORDER BY e.id)
) t
WHERE on_hand_after <> expected
ORDER BY id;

-- ④ 가상 로케이션에 잔액 행이 생겼는지
SELECT b.id
FROM stock_balance b
JOIN location l ON l.id = b.location_id
WHERE l.is_virtual;

-- ⑤ 실사 표시 검증 (I10): 로케이션 표시와 진행 중인 세션이 어긋난 경우
SELECT l.id AS location_id, l.count_session_id, s.id AS active_session_id
FROM location l
LEFT JOIN count_session s
  ON s.location_id = l.id AND s.status IN ('OPEN', 'REVIEW')
WHERE l.count_session_id IS DISTINCT FROM s.id;
```

각 쿼리는 단일 SQL이라 하나의 스냅샷에서 실행된다. 그래서 운영 중에 돌려도 쓰기 도중의 중간 상태 때문에 거짓 불일치가 나오지 않고, 읽기 전용 레플리카에서 실행해도 된다.

③이 가능한 이유는 같은 잔액 키의 원장이 항상 그 잔액 행의 잠금 아래에서 기록되기 때문이다. 같은 키 안에서는 id 순서가 곧 실제 적용 순서이므로, 체인이 처음 끊긴 행이 틀어짐이 시작된 거래를 정확히 가리킨다. 이 거래 id가 AI 원인 분석의 출발점이 된다.

불일치가 나오면 자동으로 보정하지 않는다. `inventory_issue`에 기록하고 알림을 보낸 뒤, 원인을 확인하고 조정 거래로만 바로잡는다. 자동 보정은 버그의 흔적을 지워서 같은 문제가 반복되게 만든다.

포트폴리오 규모에서는 전체 스캔으로 충분하다. 데이터가 커지면 최근 기록분을 대상으로 한 증분 검증을 자주 돌리고, 전체 검증은 주기를 늘린다.
