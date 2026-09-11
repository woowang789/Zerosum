-- ── 검증 전용 하네스 ─────────────────────────────────────────
-- 04-write-path.md의 포스팅·할당 흐름과 05-count-session.md의 실사 흐름을
-- SQL로 재현한다. 운영 코드가 아니라, 스키마가 그 흐름을 지탱하는지 확인하기 위한 것이다.
-- 애플리케이션 계층 검사(가용 부족, 합계 0, 실사 중 거절)도 여기서 재현하므로
-- DB 제약이 최후 방어선으로 남는지 함께 볼 수 있다.

CREATE TABLE tst_result (
  seq      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  case_id  TEXT NOT NULL,
  title    TEXT NOT NULL,
  expect   TEXT NOT NULL,          -- 'OK' 또는 기대하는 오류 메시지 조각
  outcome  TEXT NOT NULL,          -- PASS / FAIL
  detail   TEXT,
  ran_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
GRANT SELECT, INSERT ON tst_result TO app_admin, app_rw;

-- 커맨드의 코드(문자열)를 id로 해석한다. 실제 API 경계가 하는 일과 같다.
CREATE FUNCTION tst_resolve(p_entries JSONB)
RETURNS TABLE (warehouse_id BIGINT, location_id BIGINT, is_virtual BOOLEAN,
               sku_id BIGINT, lot_id BIGINT, qty INT, loc_code TEXT, sku_code TEXT, lot_no TEXT)
LANGUAGE sql STABLE AS $$
  SELECT w.id, l.id, l.is_virtual, s.id, lo.id, e.qty, l.code, s.code, lo.lot_no
  FROM jsonb_to_recordset(p_entries) AS e(wh TEXT, loc TEXT, sku TEXT, lot TEXT, qty INT)
  JOIN warehouse w ON w.code = e.wh
  JOIN location  l ON l.warehouse_id = w.id AND l.code = e.loc
  JOIN sku       s ON s.code = e.sku
  JOIN lot      lo ON lo.sku_id = s.id AND lo.lot_no = e.lot;
$$;

-- ── 포스팅 서비스 재현 ───────────────────────────────────────
CREATE FUNCTION tst_post(
  p_idem_key        TEXT,
  p_txn_type        TEXT,
  p_actor_id        TEXT,
  p_entries         JSONB,
  p_source_type     TEXT        DEFAULT NULL,
  p_source_ref      TEXT        DEFAULT NULL,
  p_reason_code     TEXT        DEFAULT NULL,
  p_consume_alloc   BIGINT[]    DEFAULT '{}',
  p_reverses_txn_id BIGINT      DEFAULT NULL,
  p_proposal_id     BIGINT      DEFAULT NULL,
  p_actor_type      TEXT        DEFAULT 'USER',
  p_occurred_at     TIMESTAMPTZ DEFAULT now()
) RETURNS BIGINT
LANGUAGE plpgsql AS $$
DECLARE
  v_hash   CHAR(64);
  v_prev   idempotency_record;
  v_txn_id BIGINT;
  v_r      RECORD;
  v_bal    stock_balance;
  v_used   INT;
  v_after  INT;
  v_locked TEXT;
  v_n      INT;
BEGIN
  v_hash := encode(sha256(convert_to(
              p_txn_type || '|' || p_entries::text || '|' || coalesce(p_reason_code,'') || '|' ||
              coalesce(p_source_ref,'') || '|' || coalesce(p_consume_alloc::text,''), 'UTF8')), 'hex');

  -- ① 멱등 키 선점
  INSERT INTO idempotency_record (idem_key, command_type, request_hash)
  VALUES (p_idem_key, p_txn_type, v_hash)
  ON CONFLICT (idem_key) DO NOTHING;

  IF NOT FOUND THEN
    SELECT * INTO v_prev FROM idempotency_record WHERE idem_key = p_idem_key;
    IF v_prev.request_hash <> v_hash THEN
      RAISE EXCEPTION 'IDEM_CONFLICT_409: 같은 멱등 키에 다른 요청 본문 (%)', p_idem_key;
    END IF;
    RETURN (v_prev.result ->> 'txnId')::BIGINT;    -- 저장된 결과 그대로 반환
  END IF;

  -- ② 커맨드 검증
  IF (SELECT count(*) FROM tst_resolve(p_entries)) <> jsonb_array_length(p_entries) THEN
    RAISE EXCEPTION 'UNKNOWN_CODE: 창고·로케이션·SKU·로트 코드 중 해석되지 않은 것이 있다';
  END IF;
  IF EXISTS (SELECT 1 FROM tst_resolve(p_entries) GROUP BY sku_id, lot_id HAVING sum(qty) <> 0) THEN
    RAISE EXCEPTION 'NOT_ZERO_SUM: (SKU, 로트)별 수량 합이 0이 아니다';
  END IF;
  IF p_txn_type = 'ADJUSTMENT' AND p_reason_code IS NULL THEN
    RAISE EXCEPTION 'REASON_REQUIRED: 조정 거래에는 사유 코드가 필요하다';
  END IF;

  -- ③ 잠금: location FOR SHARE → stock_balance FOR UPDATE, 각각 id 오름차순
  FOR v_r IN SELECT DISTINCT location_id FROM tst_resolve(p_entries) WHERE NOT is_virtual ORDER BY 1 LOOP
    PERFORM 1 FROM location WHERE id = v_r.location_id FOR SHARE;
  END LOOP;

  SELECT string_agg(l.code, ', ') INTO v_locked
  FROM location l
  WHERE l.id IN (SELECT location_id FROM tst_resolve(p_entries) WHERE NOT is_virtual)
    AND l.count_session_id IS NOT NULL;
  IF v_locked IS NOT NULL THEN
    RAISE EXCEPTION 'COUNT_IN_PROGRESS: 실사 진행 중인 로케이션 (%)', v_locked;
  END IF;

  INSERT INTO stock_balance (warehouse_id, location_id, sku_id, lot_id)
  SELECT warehouse_id, location_id, sku_id, lot_id FROM tst_resolve(p_entries)
  WHERE NOT is_virtual AND qty > 0
  ORDER BY location_id, sku_id, lot_id
  ON CONFLICT (location_id, sku_id, lot_id) DO NOTHING;

  FOR v_r IN SELECT b.id FROM stock_balance b
             JOIN tst_resolve(p_entries) r USING (location_id, sku_id, lot_id)
             WHERE NOT r.is_virtual ORDER BY b.id LOOP
    PERFORM 1 FROM stock_balance WHERE id = v_r.id FOR UPDATE;
  END LOOP;

  -- ④ 거래 헤더
  INSERT INTO inventory_txn (idem_key, txn_type, source_type, source_ref, reason_code,
                             actor_type, actor_id, proposal_id, reverses_txn_id, occurred_at)
  VALUES (p_idem_key, p_txn_type, p_source_type, p_source_ref, p_reason_code,
          p_actor_type, p_actor_id, p_proposal_id, p_reverses_txn_id, p_occurred_at)
  RETURNING id INTO v_txn_id;

  -- ⑤ 적용 + 원장 기록
  FOR v_r IN SELECT * FROM tst_resolve(p_entries) ORDER BY location_id, sku_id, lot_id LOOP
    IF v_r.is_virtual THEN
      v_after := NULL;                                  -- 가상 로케이션은 잔액 행이 없다
    ELSE
      SELECT * INTO v_bal FROM stock_balance
       WHERE location_id = v_r.location_id AND sku_id = v_r.sku_id AND lot_id = v_r.lot_id;
      IF NOT FOUND THEN
        RAISE EXCEPTION 'NO_STOCK: %/%/% 잔액 행이 없다', v_r.loc_code, v_r.sku_code, v_r.lot_no;
      END IF;

      SELECT COALESCE(sum(a.qty), 0) INTO v_used FROM allocation a
       WHERE a.id = ANY(p_consume_alloc) AND a.balance_id = v_bal.id AND a.status = 'ACTIVE';

      IF v_r.qty < 0 AND (v_bal.on_hand_qty - v_bal.allocated_qty + v_used) < -v_r.qty THEN
        RAISE EXCEPTION 'INSUFFICIENT_STOCK: %/%/% 가용 %, 요청 %',
          v_r.loc_code, v_r.sku_code, v_r.lot_no, v_bal.on_hand_qty - v_bal.allocated_qty + v_used, -v_r.qty;
      END IF;

      UPDATE stock_balance
         SET on_hand_qty   = on_hand_qty + v_r.qty,
             allocated_qty = allocated_qty - v_used,
             updated_at    = now()
       WHERE id = v_bal.id
      RETURNING on_hand_qty INTO v_after;
    END IF;

    INSERT INTO inventory_ledger_entry (txn_id, warehouse_id, location_id, sku_id, lot_id, qty_delta, on_hand_after)
    VALUES (v_txn_id, v_r.warehouse_id, v_r.location_id, v_r.sku_id, v_r.lot_id, v_r.qty, v_after);
  END LOOP;

  -- ⑥ 할당 소진
  IF coalesce(array_length(p_consume_alloc, 1), 0) > 0 THEN
    UPDATE allocation SET status = 'CONSUMED', consumed_txn_id = v_txn_id, closed_at = now()
     WHERE id = ANY(p_consume_alloc) AND status = 'ACTIVE';
    GET DIAGNOSTICS v_n = ROW_COUNT;
    IF v_n <> array_length(p_consume_alloc, 1) THEN
      RAISE EXCEPTION 'ALLOC_NOT_ACTIVE: 소진 대상 % 건 중 % 건만 ACTIVE였다',
        array_length(p_consume_alloc, 1), v_n;
    END IF;
  END IF;

  -- ⑦ 아웃박스 + 멱등 결과 기록
  INSERT INTO outbox_event (event_type, partition_key, payload)
  VALUES ('StockPosted',
          (SELECT min(sku_id)::TEXT FROM tst_resolve(p_entries)),
          jsonb_build_object('txnId', v_txn_id, 'txnType', p_txn_type,
                             'sourceRef', p_source_ref, 'entries', p_entries));

  UPDATE idempotency_record SET result = jsonb_build_object('txnId', v_txn_id) WHERE idem_key = p_idem_key;
  RETURN v_txn_id;
END;
$$;
-- ── 할당 흐름 재현 (FEFO) ────────────────────────────────────
-- p_allow_in_count는 기본값을 두지 않는다. 채널 노출 정책과 짝을 맞춰야 하므로
-- 호출자가 매번 명시하게 한다 (기본값이 있으면 노출만 바뀐 날 조용히 주문이 실패한다).
CREATE FUNCTION tst_allocate(
  p_idem_key TEXT, p_order_line TEXT, p_wh TEXT, p_sku TEXT, p_qty INT,
  p_allow_in_count BOOLEAN
) RETURNS BIGINT[]
LANGUAGE plpgsql AS $$
DECLARE
  v_hash CHAR(64); v_prev idempotency_record; v_r RECORD;
  v_left INT := p_qty; v_take INT; v_ids BIGINT[] := '{}'; v_id BIGINT;
BEGIN
  v_hash := encode(sha256(convert_to(p_order_line||'|'||p_wh||'|'||p_sku||'|'||p_qty::text, 'UTF8')), 'hex');
  INSERT INTO idempotency_record (idem_key, command_type, request_hash)
  VALUES (p_idem_key, 'ALLOCATE', v_hash) ON CONFLICT (idem_key) DO NOTHING;
  IF NOT FOUND THEN
    SELECT * INTO v_prev FROM idempotency_record WHERE idem_key = p_idem_key;
    IF v_prev.request_hash <> v_hash THEN
      RAISE EXCEPTION 'IDEM_CONFLICT_409: 같은 멱등 키에 다른 요청 본문 (%)', p_idem_key;
    END IF;
    RETURN ARRAY(SELECT jsonb_array_elements_text(v_prev.result->'allocationIds')::BIGINT);
  END IF;

  -- 후보를 id 순서로 잠근다 (할당은 실재고를 바꾸지 않으므로 location은 잠그지 않는다)
  FOR v_r IN
    SELECT b.id FROM stock_balance b
    JOIN location l ON l.id = b.location_id AND l.is_sellable
    JOIN warehouse w ON w.id = b.warehouse_id
    JOIN sku s ON s.id = b.sku_id
    WHERE w.code = p_wh AND s.code = p_sku
      AND (p_allow_in_count OR l.count_session_id IS NULL)
    ORDER BY b.id
  LOOP
    PERFORM 1 FROM stock_balance WHERE id = v_r.id FOR UPDATE;
  END LOOP;

  -- 잠근 뒤 FEFO 정렬: 유통기한 빠른 순, 기한 없는 로트는 뒤로, 동률은 id
  FOR v_r IN
    SELECT b.id, b.on_hand_qty - b.allocated_qty AS avail
    FROM stock_balance b
    JOIN location l ON l.id = b.location_id AND l.is_sellable
    JOIN warehouse w ON w.id = b.warehouse_id
    JOIN sku s ON s.id = b.sku_id
    JOIN lot lo ON lo.id = b.lot_id
    WHERE w.code = p_wh AND s.code = p_sku
      AND (p_allow_in_count OR l.count_session_id IS NULL)
      AND b.on_hand_qty - b.allocated_qty > 0
    ORDER BY lo.expiry_date ASC NULLS LAST, b.id
  LOOP
    EXIT WHEN v_left <= 0;
    v_take := least(v_left, v_r.avail);
    UPDATE stock_balance SET allocated_qty = allocated_qty + v_take, updated_at = now() WHERE id = v_r.id;
    INSERT INTO allocation (idem_key, order_line_ref, balance_id, qty)
    VALUES (p_idem_key, p_order_line, v_r.id, v_take) RETURNING id INTO v_id;
    v_ids := v_ids || v_id;
    v_left := v_left - v_take;
  END LOOP;

  IF v_left > 0 THEN
    RAISE EXCEPTION 'INSUFFICIENT_STOCK: % 할당 요청 %, 가용 부족 %', p_sku, p_qty, v_left;
  END IF;

  INSERT INTO outbox_event (event_type, partition_key, payload)
  VALUES ('StockAllocated', (SELECT id::TEXT FROM sku WHERE code = p_sku),
          jsonb_build_object('orderLineRef', p_order_line, 'qty', p_qty, 'allocationIds', to_jsonb(v_ids)));
  UPDATE idempotency_record SET result = jsonb_build_object('allocationIds', to_jsonb(v_ids))
   WHERE idem_key = p_idem_key;
  RETURN v_ids;
END;
$$;

CREATE FUNCTION tst_release_alloc(p_idem_key TEXT, p_alloc_ids BIGINT[]) RETURNS INT
LANGUAGE plpgsql AS $$
DECLARE v_r RECORD; v_n INT := 0; v_hash CHAR(64);
BEGIN
  v_hash := encode(sha256(convert_to(p_alloc_ids::text, 'UTF8')), 'hex');
  INSERT INTO idempotency_record (idem_key, command_type, request_hash)
  VALUES (p_idem_key, 'RELEASE_ALLOC', v_hash) ON CONFLICT (idem_key) DO NOTHING;
  IF NOT FOUND THEN RETURN 0; END IF;

  FOR v_r IN SELECT DISTINCT balance_id FROM allocation WHERE id = ANY(p_alloc_ids) ORDER BY 1 LOOP
    PERFORM 1 FROM stock_balance WHERE id = v_r.balance_id FOR UPDATE;
  END LOOP;
  FOR v_r IN SELECT id, balance_id, qty FROM allocation WHERE id = ANY(p_alloc_ids) ORDER BY id LOOP
    UPDATE allocation SET status = 'RELEASED', closed_at = now() WHERE id = v_r.id AND status = 'ACTIVE';
    IF NOT FOUND THEN RAISE EXCEPTION 'ALLOC_NOT_ACTIVE: 할당 %는 이미 닫혔다', v_r.id; END IF;
    UPDATE stock_balance SET allocated_qty = allocated_qty - v_r.qty, updated_at = now() WHERE id = v_r.balance_id;
    v_n := v_n + 1;
  END LOOP;
  UPDATE idempotency_record SET result = jsonb_build_object('released', v_n) WHERE idem_key = p_idem_key;
  RETURN v_n;
END;
$$;

-- ── 검증 러너 ────────────────────────────────────────────────
CREATE FUNCTION tst_run(p_case TEXT, p_title TEXT, p_sql TEXT, p_expect TEXT DEFAULT 'OK',
                        p_immediate BOOLEAN DEFAULT FALSE)
RETURNS TEXT
LANGUAGE plpgsql AS $$
DECLARE v_msg TEXT; v_con TEXT; v_state TEXT; v_full TEXT; v_out TEXT; v_detail TEXT;
BEGIN
  BEGIN
    IF p_immediate THEN SET CONSTRAINTS ALL IMMEDIATE; END IF;   -- 지연 제약을 즉시 검사로
    EXECUTE p_sql;
    IF p_immediate THEN SET CONSTRAINTS ALL DEFERRED; END IF;
    IF p_expect = 'OK' THEN v_out := 'PASS';
    ELSE v_out := 'FAIL'; v_detail := format('오류를 기대했으나 성공했다 (기대: %s)', p_expect); END IF;
  EXCEPTION WHEN OTHERS THEN
    GET STACKED DIAGNOSTICS v_msg = MESSAGE_TEXT, v_con = CONSTRAINT_NAME, v_state = RETURNED_SQLSTATE;
    v_full := coalesce(v_state,'') || ' ' || coalesce(v_con,'') || ' ' || coalesce(v_msg,'');
    IF p_expect = 'OK' THEN v_out := 'FAIL'; v_detail := v_full;
    ELSIF position(p_expect IN v_full) > 0 THEN v_out := 'PASS'; v_detail := v_full;
    ELSE v_out := 'FAIL'; v_detail := format('다른 오류 (기대 %s / 실제 %s)', p_expect, v_full); END IF;
  END;
  INSERT INTO tst_result (case_id, title, expect, outcome, detail) VALUES (p_case, p_title, p_expect, v_out, v_detail);
  RETURN v_out || coalesce(' — ' || v_detail, '');
END;
$$;
-- ── 실사 세션 재현 ───────────────────────────────────────────
CREATE FUNCTION tst_count_start(p_idem_key TEXT, p_wh TEXT, p_loc TEXT, p_user TEXT) RETURNS BIGINT
LANGUAGE plpgsql AS $$
DECLARE v_loc BIGINT; v_sid BIGINT;
BEGIN
  INSERT INTO idempotency_record (idem_key, command_type, request_hash)
  VALUES (p_idem_key, 'COUNT_START', encode(sha256(convert_to(p_wh||'|'||p_loc,'UTF8')),'hex'))
  ON CONFLICT (idem_key) DO NOTHING;
  IF NOT FOUND THEN RAISE EXCEPTION 'IDEM_REPLAY: 이미 처리된 키 (%)', p_idem_key; END IF;

  SELECT l.id INTO v_loc FROM location l JOIN warehouse w ON w.id=l.warehouse_id
   WHERE w.code=p_wh AND l.code=p_loc;

  INSERT INTO count_session (location_id, started_by) VALUES (v_loc, p_user) RETURNING id INTO v_sid;

  -- 진행 중인 포스팅의 FOR SHARE가 끝날 때까지 기다린 뒤 표시된다
  UPDATE location SET count_session_id = v_sid WHERE id = v_loc AND count_session_id IS NULL;
  IF NOT FOUND THEN RAISE EXCEPTION 'COUNT_ALREADY_OPEN: %에 이미 진행 중인 실사가 있다', p_loc; END IF;

  UPDATE idempotency_record SET result = jsonb_build_object('sessionId', v_sid) WHERE idem_key = p_idem_key;
  RETURN v_sid;
END;
$$;

-- 제출: 라인별 실재고를 system_qty로 굳히고, 허용 오차 이내면 정정까지 끝낸다
-- 허용 오차도 기본값을 두지 않는다. 운영 설정에서 주입할 값이다.
CREATE FUNCTION tst_count_submit(p_idem_key TEXT, p_session BIGINT, p_lines JSONB, p_user TEXT,
                                 p_tol_qty INT, p_tol_pct NUMERIC) RETURNS TEXT
LANGUAGE plpgsql AS $$
DECLARE v_s count_session; v_over INT; v_diff INT;
BEGIN
  INSERT INTO idempotency_record (idem_key, command_type, request_hash)
  VALUES (p_idem_key, 'COUNT_SUBMIT', encode(sha256(convert_to(p_session::text||p_lines::text,'UTF8')),'hex'))
  ON CONFLICT (idem_key) DO NOTHING;
  IF NOT FOUND THEN RAISE EXCEPTION 'IDEM_REPLAY: 이미 처리된 키 (%)', p_idem_key; END IF;

  SELECT * INTO v_s FROM count_session WHERE id = p_session FOR UPDATE;
  IF v_s.status <> 'OPEN' THEN RAISE EXCEPTION 'COUNT_NOT_OPEN: 세션 % 상태 %', p_session, v_s.status; END IF;

  -- 센 라인과 시스템 재고를 FULL JOIN: 라인에 없는 시스템 재고는 0개로 센 것으로 본다
  WITH counted AS (
    SELECT s.id AS sku_id, lo.id AS lot_id, e.qty
    FROM jsonb_to_recordset(p_lines) AS e(sku TEXT, lot TEXT, qty INT)
    JOIN sku s ON s.code = e.sku JOIN lot lo ON lo.sku_id = s.id AND lo.lot_no = e.lot
  ), sys AS (
    SELECT sku_id, lot_id, on_hand_qty FROM stock_balance WHERE location_id = v_s.location_id
  )
  INSERT INTO count_result (count_session_id, location_id, sku_id, lot_id, system_qty, counted_qty, counted_by, counted_at)
  SELECT p_session, v_s.location_id, coalesce(c.sku_id, sy.sku_id), coalesce(c.lot_id, sy.lot_id),
         coalesce(sy.on_hand_qty, 0), coalesce(c.qty, 0), p_user, now()
  FROM counted c FULL JOIN sys sy ON sy.sku_id = c.sku_id AND sy.lot_id = c.lot_id;

  SELECT count(*) INTO v_over FROM count_result
   WHERE count_session_id = p_session
     AND (abs(counted_qty - system_qty) > p_tol_qty
          OR abs(counted_qty - system_qty)::NUMERIC > system_qty * p_tol_pct);
  SELECT count(*) INTO v_diff FROM count_result
   WHERE count_session_id = p_session AND counted_qty <> system_qty;

  IF v_over > 0 THEN
    UPDATE count_session SET status = 'REVIEW', submitted_at = now() WHERE id = p_session;
    INSERT INTO inventory_issue (issue_type, severity, location_id, sku_id, lot_id, detail)
    SELECT 'COUNT_VARIANCE', 'MEDIUM', cr.location_id, cr.sku_id, cr.lot_id,
           jsonb_build_object('countSessionId', p_session, 'systemQty', cr.system_qty,
                              'countedQty', cr.counted_qty, 'diff', cr.counted_qty - cr.system_qty)
    FROM count_result cr WHERE cr.count_session_id = p_session AND cr.counted_qty <> cr.system_qty;
    UPDATE idempotency_record SET result = jsonb_build_object('status','REVIEW') WHERE idem_key = p_idem_key;
    RETURN 'REVIEW';
  END IF;

  UPDATE count_session SET status = 'OPEN', submitted_at = now() WHERE id = p_session;
  IF v_diff > 0 THEN
    PERFORM tst_count_resolve(p_idem_key || ':resolve', p_session, p_user);
  ELSE
    UPDATE location SET count_session_id = NULL WHERE id = v_s.location_id;
    UPDATE count_session SET status = 'CONFIRMED', closed_by = p_user, closed_at = now() WHERE id = p_session;
  END IF;
  UPDATE idempotency_record SET result = jsonb_build_object('status','CONFIRMED') WHERE idem_key = p_idem_key;
  RETURN 'CONFIRMED';
END;
$$;

-- 정정: 세션 → 로케이션 순서로 잠그고, 표시를 먼저 지운 뒤 같은 트랜잭션에서 조정을 포스팅한다
CREATE FUNCTION tst_count_resolve(p_idem_key TEXT, p_session BIGINT, p_user TEXT) RETURNS BIGINT
LANGUAGE plpgsql AS $$
DECLARE v_s count_session; v_entries JSONB; v_txn BIGINT; v_wh TEXT; v_loc TEXT;
BEGIN
  SELECT * INTO v_s FROM count_session WHERE id = p_session FOR UPDATE;
  IF v_s.status NOT IN ('OPEN','REVIEW') OR v_s.submitted_at IS NULL THEN
    RAISE EXCEPTION 'COUNT_NOT_SUBMITTED: 세션 % 상태 %', p_session, v_s.status;
  END IF;

  SELECT w.code, l.code INTO v_wh, v_loc
  FROM location l JOIN warehouse w ON w.id = l.warehouse_id WHERE l.id = v_s.location_id;

  PERFORM 1 FROM location WHERE id = v_s.location_id FOR SHARE;
  UPDATE location SET count_session_id = NULL WHERE id = v_s.location_id;   -- 표시를 먼저 지운다

  SELECT jsonb_agg(x) INTO v_entries FROM (
    SELECT jsonb_build_object('wh', v_wh, 'loc', v_loc, 'sku', s.code, 'lot', lo.lot_no,
                              'qty', cr.counted_qty - cr.system_qty) AS x
    FROM count_result cr JOIN sku s ON s.id = cr.sku_id JOIN lot lo ON lo.id = cr.lot_id
    WHERE cr.count_session_id = p_session AND cr.counted_qty <> cr.system_qty
    UNION ALL
    SELECT jsonb_build_object('wh', v_wh, 'loc', 'V-ADJUST', 'sku', s.code, 'lot', lo.lot_no,
                              'qty', cr.system_qty - cr.counted_qty)
    FROM count_result cr JOIN sku s ON s.id = cr.sku_id JOIN lot lo ON lo.id = cr.lot_id
    WHERE cr.count_session_id = p_session AND cr.counted_qty <> cr.system_qty
  ) t;

  IF v_entries IS NULL THEN
    UPDATE count_session SET status='CONFIRMED', closed_by=p_user, closed_at=now() WHERE id = p_session;
    RETURN NULL;
  END IF;

  v_txn := tst_post(p_idem_key, 'ADJUSTMENT', p_user, v_entries,
                    'COUNT', p_session::TEXT, 'COUNT_VARIANCE');
  UPDATE count_session SET status='CONFIRMED', closed_by=p_user, closed_at=now(), resolution_txn_id=v_txn
   WHERE id = p_session;
  UPDATE inventory_issue SET status='RESOLVED', resolved_txn_id=v_txn
   WHERE issue_type='COUNT_VARIANCE' AND detail->>'countSessionId' = p_session::TEXT AND status='OPEN';
  RETURN v_txn;
END;
$$;

CREATE FUNCTION tst_count_abandon(p_idem_key TEXT, p_session BIGINT, p_user TEXT) RETURNS VOID
LANGUAGE plpgsql AS $$
DECLARE v_s count_session;
BEGIN
  SELECT * INTO v_s FROM count_session WHERE id = p_session FOR UPDATE;
  IF v_s.status NOT IN ('OPEN','REVIEW') THEN RAISE EXCEPTION 'COUNT_CLOSED: 세션 % 상태 %', p_session, v_s.status; END IF;
  UPDATE location SET count_session_id = NULL WHERE id = v_s.location_id;
  UPDATE count_session SET status='ABANDONED', closed_by=p_user, closed_at=now() WHERE id = p_session;
END;
$$;

-- ── AI 제안 ──────────────────────────────────────────────────
-- basis_snapshot은 LLM이 채우지 않는다. 에이전트는 근거의 종류만 지정하고 서버가 DB에서 직접 조회한다.
CREATE FUNCTION tst_create_proposal(p_type TEXT, p_payload JSONB, p_rationale TEXT, p_by TEXT,
                                    p_basis JSONB DEFAULT '[]', p_ttl INTERVAL DEFAULT '1 hour') RETURNS BIGINT
LANGUAGE plpgsql AS $$
DECLARE v_snap JSONB; v_id BIGINT;
BEGIN
  SELECT jsonb_build_object('captured_at', now(), 'observations',
           coalesce(jsonb_agg(jsonb_build_object('scope','balance','balance_id', b.id,
                                                 'available_qty', b.on_hand_qty - b.allocated_qty)), '[]'::JSONB))
    INTO v_snap
  FROM jsonb_to_recordset(p_basis) AS o(wh TEXT, loc TEXT, sku TEXT, lot TEXT)
  JOIN warehouse w ON w.code = o.wh
  JOIN location  l ON l.warehouse_id = w.id AND l.code = o.loc
  JOIN sku       s ON s.code = o.sku
  JOIN lot      lo ON lo.sku_id = s.id AND lo.lot_no = o.lot
  JOIN stock_balance b ON b.location_id = l.id AND b.sku_id = s.id AND b.lot_id = lo.id;

  INSERT INTO action_proposal (proposal_type, command_payload, basis_snapshot, rationale,
                               proposed_by, agent_meta, expires_at)
  VALUES (p_type, p_payload, v_snap, p_rationale, p_by,
          jsonb_build_object('model','claude-opus-5','promptVersion','v3'), now() + p_ttl)
  RETURNING id INTO v_id;
  RETURN v_id;
END;
$$;

CREATE FUNCTION tst_approve_proposal(p_id BIGINT, p_approver TEXT, p_tol NUMERIC DEFAULT 0.10) RETURNS TEXT
LANGUAGE plpgsql AS $$
DECLARE v_p action_proposal; v_txn BIGINT; v_bad INT;
BEGIN
  SELECT * INTO v_p FROM action_proposal WHERE id = p_id FOR UPDATE;   -- 승인 연타를 직렬화
  IF v_p.status <> 'PENDING' THEN RETURN 'ALREADY_DECIDED:' || v_p.status; END IF;
  IF v_p.expires_at <= now() THEN
    UPDATE action_proposal SET status='EXPIRED', decided_by=p_approver, decided_at=now() WHERE id = p_id;
    RETURN 'EXPIRED';
  END IF;

  -- basis 재검증: 제안 시점과 지금의 가용 수량이 허용 오차 이상 벌어졌으면 STALE
  SELECT count(*) INTO v_bad
  FROM jsonb_to_recordset(v_p.basis_snapshot->'observations') AS o(balance_id BIGINT, available_qty INT)
  JOIN stock_balance b ON b.id = o.balance_id
  WHERE abs((b.on_hand_qty - b.allocated_qty) - o.available_qty) > greatest(o.available_qty * p_tol, 0);

  IF v_bad > 0 THEN
    UPDATE action_proposal SET status='STALE', decided_by=p_approver, decided_at=now() WHERE id = p_id;
    RETURN 'STALE';                                  -- 예외가 아니라 결과로 반환해야 이 커밋이 살아남는다
  END IF;

  v_txn := tst_post('proposal:' || p_id, v_p.command_payload->>'txnType', p_approver,
                    v_p.command_payload->'entries', 'PROPOSAL', 'proposal:' || p_id,
                    v_p.command_payload->>'reasonCode', '{}', NULL, p_id);
  UPDATE action_proposal SET status='EXECUTED', decided_by=p_approver, decided_at=now(), executed_txn_id=v_txn
   WHERE id = p_id;
  RETURN 'EXECUTED:' || v_txn;
END;
$$;
-- ── 조회 헬퍼 (유스케이스를 읽기 쉽게) ───────────────────────
CREATE FUNCTION tst_loc(p_wh TEXT, p_loc TEXT) RETURNS BIGINT LANGUAGE sql STABLE AS $$
  SELECT l.id FROM location l JOIN warehouse w ON w.id = l.warehouse_id
   WHERE w.code = p_wh AND l.code = p_loc; $$;

CREATE FUNCTION tst_session(p_wh TEXT, p_loc TEXT) RETURNS BIGINT LANGUAGE sql STABLE AS $$
  SELECT count_session_id FROM location WHERE id = tst_loc(p_wh, p_loc); $$;

CREATE FUNCTION tst_qty(p_wh TEXT, p_loc TEXT, p_sku TEXT, p_lot TEXT DEFAULT 'DEFAULT') RETURNS INT
LANGUAGE sql STABLE AS $$
  SELECT coalesce(b.on_hand_qty, 0) FROM stock_balance b
   JOIN sku s ON s.id = b.sku_id JOIN lot lo ON lo.id = b.lot_id
   WHERE b.location_id = tst_loc(p_wh, p_loc) AND s.code = p_sku AND lo.lot_no = p_lot; $$;

CREATE FUNCTION tst_avail(p_wh TEXT, p_loc TEXT, p_sku TEXT, p_lot TEXT DEFAULT 'DEFAULT') RETURNS INT
LANGUAGE sql STABLE AS $$
  SELECT coalesce(b.on_hand_qty - b.allocated_qty, 0) FROM stock_balance b
   JOIN sku s ON s.id = b.sku_id JOIN lot lo ON lo.id = b.lot_id
   WHERE b.location_id = tst_loc(p_wh, p_loc) AND s.code = p_sku AND lo.lot_no = p_lot; $$;

CREATE FUNCTION tst_alloc_ids(p_order_line TEXT) RETURNS BIGINT[] LANGUAGE sql STABLE AS $$
  SELECT coalesce(array_agg(id ORDER BY id), '{}') FROM allocation
   WHERE order_line_ref = p_order_line AND status = 'ACTIVE'; $$;

-- 상태 단언: 기대값과 실제값을 비교해 결과를 남긴다
CREATE FUNCTION tst_assert(p_case TEXT, p_title TEXT, p_actual ANYELEMENT, p_expected ANYELEMENT) RETURNS TEXT
LANGUAGE plpgsql AS $$
DECLARE v_out TEXT; v_detail TEXT;
BEGIN
  IF p_actual IS NOT DISTINCT FROM p_expected THEN
    v_out := 'PASS'; v_detail := format('실제 %s', p_actual);
  ELSE
    v_out := 'FAIL'; v_detail := format('기대 %s / 실제 %s', p_expected, p_actual);
  END IF;
  INSERT INTO tst_result (case_id, title, expect, outcome, detail)
  VALUES (p_case, p_title, format('= %s', p_expected), v_out, v_detail);
  RETURN v_out || ' — ' || v_detail;
END;
$$;
-- ── 정합 검증 배치 (06-events-reconciliation.md의 ①~⑤) ──────
CREATE FUNCTION tst_recon() RETURNS TABLE (chk TEXT, cnt BIGINT) LANGUAGE sql STABLE AS $$
  SELECT '① 잔액 투영 (I4)', count(*) FROM (
    WITH ledger_sum AS (
      SELECT e.location_id, e.sku_id, e.lot_id, SUM(e.qty_delta) AS ledger_qty
      FROM inventory_ledger_entry e JOIN location l ON l.id = e.location_id
      WHERE NOT l.is_virtual GROUP BY e.location_id, e.sku_id, e.lot_id)
    SELECT location_id FROM stock_balance b
    FULL JOIN ledger_sum s USING (location_id, sku_id, lot_id)
    WHERE COALESCE(b.on_hand_qty, 0) <> COALESCE(s.ledger_qty, 0)) x
  UNION ALL
  SELECT '② 할당 (I5)', count(*) FROM (
    SELECT b.id FROM stock_balance b
    LEFT JOIN allocation a ON a.balance_id = b.id AND a.status = 'ACTIVE'
    GROUP BY b.id, b.allocated_qty HAVING b.allocated_qty <> COALESCE(SUM(a.qty), 0)) x
  UNION ALL
  SELECT '③ 원장 체인 (I7)', count(*) FROM (
    SELECT id FROM (
      SELECT e.*, COALESCE(LAG(e.on_hand_after) OVER w, 0) + e.qty_delta AS expected
      FROM inventory_ledger_entry e WHERE e.on_hand_after IS NOT NULL
      WINDOW w AS (PARTITION BY e.location_id, e.sku_id, e.lot_id ORDER BY e.id)) t
    WHERE on_hand_after <> expected) x
  UNION ALL
  SELECT '④ 가상 로케이션 잔액 행', count(*) FROM (
    SELECT b.id FROM stock_balance b JOIN location l ON l.id = b.location_id WHERE l.is_virtual) x
  UNION ALL
  SELECT '⑤ 실사 표시 (I10)', count(*) FROM (
    SELECT l.id FROM location l
    LEFT JOIN count_session s ON s.location_id = l.id AND s.status IN ('OPEN','REVIEW')
    WHERE l.count_session_id IS DISTINCT FROM s.id) x;
$$;

-- 체인이 처음 끊긴 지점: AI 원인 분석의 출발점
CREATE FUNCTION tst_chain_break() RETURNS TABLE (ledger_entry_id BIGINT, txn_id BIGINT, on_hand_after INT, expected INT)
LANGUAGE sql STABLE AS $$
  SELECT id, txn_id, on_hand_after, expected FROM (
    SELECT e.*, COALESCE(LAG(e.on_hand_after) OVER w, 0) + e.qty_delta AS expected
    FROM inventory_ledger_entry e WHERE e.on_hand_after IS NOT NULL
    WINDOW w AS (PARTITION BY e.location_id, e.sku_id, e.lot_id ORDER BY e.id)) t
  WHERE on_hand_after <> expected ORDER BY id LIMIT 1;
$$;
GRANT SELECT, INSERT ON tst_result TO ai_ro, ai_proposer;
