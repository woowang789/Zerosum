package com.zerosum.inventory.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code count_session} 행과 로케이션 실사 표시({@code location.count_session_id}) 접근, 그리고 실사 커맨드
 * 4종(COUNT_START·COUNT_SUBMIT·COUNT_RESOLVE·COUNT_ABANDON)의 멱등 키 선점·재현.
 * db/04-harness.sql의 tst_count_start·tst_count_submit·tst_count_resolve·tst_count_abandon과 대응한다.
 *
 * <p>멱등 키 처리는 AllocationRepository와 같은 패턴이다 — IdempotencyRepository(posting 패키지)는
 * PostingOutcome 전용이라 이 도메인에는 맞지 않아, 선점(INSERT ... ON CONFLICT DO NOTHING)과 결과 재현을
 * 여기서도 직접 구현한다. 커맨드 종류별로 결과 모양이 달라(세션 id, 상태, 정정 거래 id) 메서드를 나눴다.
 * 여기서는 SQL 실행만 하고, 상태 검증(COUNT_ALREADY_OPEN 등 도메인 규칙)은 CountSessionService가 맡는다.
 */
@Repository
public class CountSessionRepository {

    private final JdbcClient jdbc;

    CountSessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ── 멱등 키 선점과 해시 비교 ─────────────────────────────────────────────────────

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean tryClaimIdempotencyKey(String idemKey, String commandType, String requestHash) {
        int inserted = jdbc.sql("""
                INSERT INTO idempotency_record (idem_key, command_type, request_hash)
                VALUES (:idemKey, :commandType, :requestHash)
                ON CONFLICT (idem_key) DO NOTHING
                """)
                .param("idemKey", idemKey)
                .param("commandType", commandType)
                .param("requestHash", requestHash)
                .update();
        return inserted > 0;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String storedRequestHash(String idemKey) {
        return jdbc.sql("SELECT request_hash FROM idempotency_record WHERE idem_key = :idemKey")
                .param("idemKey", idemKey)
                .query(String.class)
                .single();
    }

    // ── COUNT_START 결과 재현/기록 ───────────────────────────────────────────────────

    @Transactional(propagation = Propagation.MANDATORY)
    public long replayStartedSessionId(String idemKey) {
        return jdbc.sql("SELECT (result ->> 'sessionId')::BIGINT FROM idempotency_record WHERE idem_key = :idemKey")
                .param("idemKey", idemKey)
                .query(Long.class)
                .single();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void completeStarted(String idemKey, long sessionId) {
        jdbc.sql("""
                UPDATE idempotency_record SET result = jsonb_build_object('sessionId', :sessionId)
                WHERE idem_key = :idemKey
                """)
                .param("sessionId", sessionId)
                .param("idemKey", idemKey)
                .update();
    }

    // ── COUNT_SUBMIT 결과 재현/기록 ──────────────────────────────────────────────────

    public record SubmitReplay(String status, Long resolutionTxnId) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public SubmitReplay replaySubmitResult(String idemKey) {
        return jdbc.sql("""
                SELECT result ->> 'status' AS status, (result ->> 'resolutionTxnId')::BIGINT AS resolution_txn_id
                FROM idempotency_record WHERE idem_key = :idemKey
                """)
                .param("idemKey", idemKey)
                .query((rs, rowNum) -> new SubmitReplay(
                        rs.getString("status"), (Long) rs.getObject("resolution_txn_id")))
                .single();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void completeSubmitReview(String idemKey) {
        jdbc.sql("""
                UPDATE idempotency_record SET result = jsonb_build_object('status', 'REVIEW')
                WHERE idem_key = :idemKey
                """)
                .param("idemKey", idemKey)
                .update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void completeSubmitConfirmed(String idemKey, Long resolutionTxnId) {
        // resolutionTxnId가 null일 수 있어 jsonb_build_object(...) 안의 자리표시자에 ::BIGINT로 타입을 명시한다 —
        // 값이 널이면 Postgres가 가변 인자 함수 안에서 파라미터 타입을 추론하지 못해
        // "could not determine data type of parameter" 오류가 난다 (일반 UPDATE의 타입이 정해진 컬럼과 다르다).
        jdbc.sql("""
                UPDATE idempotency_record
                SET result = jsonb_build_object('status', 'CONFIRMED', 'resolutionTxnId', :resolutionTxnId::BIGINT)
                WHERE idem_key = :idemKey
                """)
                .param("resolutionTxnId", resolutionTxnId)
                .param("idemKey", idemKey)
                .update();
    }

    // ── COUNT_RESOLVE 결과 재현/기록 (제출의 자동 정정 경로도 이 메서드를 함께 쓴다) ────────

    public record ResolvedReplay(Long resolutionTxnId) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ResolvedReplay replayResolvedResult(String idemKey) {
        return jdbc.sql("""
                SELECT (result ->> 'resolutionTxnId')::BIGINT AS resolution_txn_id
                FROM idempotency_record WHERE idem_key = :idemKey
                """)
                .param("idemKey", idemKey)
                .query((rs, rowNum) -> new ResolvedReplay((Long) rs.getObject("resolution_txn_id")))
                .single();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void completeResolved(String idemKey, Long resolutionTxnId) {
        // completeSubmitConfirmed와 같은 이유로 ::BIGINT 캐스트가 필요하다 (resolutionTxnId가 null일 수 있다).
        jdbc.sql("""
                UPDATE idempotency_record SET result = jsonb_build_object('resolutionTxnId', :resolutionTxnId::BIGINT)
                WHERE idem_key = :idemKey
                """)
                .param("resolutionTxnId", resolutionTxnId)
                .param("idemKey", idemKey)
                .update();
    }

    // ── COUNT_ABANDON 결과 기록 (재현은 존재 여부만 보면 되므로 별도 조회가 필요 없다) ───────

    @Transactional(propagation = Propagation.MANDATORY)
    public void completeAbandoned(String idemKey) {
        jdbc.sql("""
                UPDATE idempotency_record SET result = jsonb_build_object('status', 'ABANDONED')
                WHERE idem_key = :idemKey
                """)
                .param("idemKey", idemKey)
                .update();
    }

    // ── 세션 생명주기 ────────────────────────────────────────────────────────────────

    @Transactional(propagation = Propagation.MANDATORY)
    public long insertSession(long locationId, String startedBy) {
        return jdbc.sql("""
                INSERT INTO count_session (location_id, started_by) VALUES (:locationId, :startedBy)
                RETURNING id
                """)
                .param("locationId", locationId)
                .param("startedBy", startedBy)
                .query(Long.class)
                .single();
    }

    /**
     * 로케이션에 실사 표시를 건다. {@code WHERE id = :locationId AND count_session_id IS NULL}의 영향 행 수를
     * 그대로 돌려준다 — 0이면 이미 다른 실사가 진행 중이라는 뜻이고, 판정은 서비스가 한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int markLocationCounting(long locationId, long sessionId) {
        return jdbc.sql("""
                UPDATE location SET count_session_id = :sessionId WHERE id = :locationId AND count_session_id IS NULL
                """)
                .param("sessionId", sessionId)
                .param("locationId", locationId)
                .update();
    }

    public record LockedSession(long id, long locationId, String status, boolean submitted) {
    }

    /** count_session을 FOR UPDATE로 잠근다 (docs/05-count-session.md 잠금 순서: count_session → location → stock_balance). */
    @Transactional(propagation = Propagation.MANDATORY)
    public LockedSession lockForUpdate(long sessionId) {
        return jdbc.sql("""
                SELECT id, location_id, status, submitted_at IS NOT NULL AS submitted
                FROM count_session WHERE id = :id FOR UPDATE
                """)
                .param("id", sessionId)
                .query((rs, rowNum) -> new LockedSession(
                        rs.getLong("id"), rs.getLong("location_id"), rs.getString("status"), rs.getBoolean("submitted")))
                .single();
    }

    /**
     * 실사 표시를 지운다. 이 UPDATE 자체가 로케이션 행을 배타 잠금으로 쥐므로 진행 중인 포스팅의 FOR SHARE가
     * 끝날 때까지 기다린 뒤 처리되고, 커밋 전에는 다른 포스팅이 끼어들 수 없다 (05-count-session.md 정정 절차).
     * {@code AND count_session_id = :sessionId} 조건은 I10을 앱에서도 한 번 더 지키기 위한 것이다 — 영향 행
     * 수가 1이 아니면 세션 잠금(FOR UPDATE) 아래에서는 있을 수 없는 상태이므로 서비스가 버그로 취급한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int clearLocationFlag(long locationId, long sessionId) {
        return jdbc.sql("""
                UPDATE location SET count_session_id = NULL WHERE id = :locationId AND count_session_id = :sessionId
                """)
                .param("locationId", locationId)
                .param("sessionId", sessionId)
                .update();
    }

    /** 제출은 됐지만 차이가 오차 이내라 같은 트랜잭션에서 바로 정정될 예정 — 상태는 OPEN 그대로, submitted_at만 굳힌다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markSubmittedStillOpen(long sessionId) {
        jdbc.sql("UPDATE count_session SET status = 'OPEN', submitted_at = now() WHERE id = :id")
                .param("id", sessionId)
                .update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markReview(long sessionId) {
        jdbc.sql("UPDATE count_session SET status = 'REVIEW', submitted_at = now() WHERE id = :id")
                .param("id", sessionId)
                .update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void confirm(long sessionId, Long resolutionTxnId, String closedBy) {
        jdbc.sql("""
                UPDATE count_session
                SET status = 'CONFIRMED', closed_by = :closedBy, closed_at = now(), resolution_txn_id = :resolutionTxnId
                WHERE id = :id
                """)
                .param("closedBy", closedBy)
                .param("resolutionTxnId", resolutionTxnId)
                .param("id", sessionId)
                .update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void abandon(long sessionId, String closedBy) {
        jdbc.sql("""
                UPDATE count_session SET status = 'ABANDONED', closed_by = :closedBy, closed_at = now() WHERE id = :id
                """)
                .param("closedBy", closedBy)
                .param("id", sessionId)
                .update();
    }

    public record WarehouseLocationCode(String warehouseCode, String locationCode) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public WarehouseLocationCode codesForLocation(long locationId) {
        return jdbc.sql("""
                SELECT w.code AS wh_code, l.code AS loc_code
                FROM location l JOIN warehouse w ON w.id = l.warehouse_id WHERE l.id = :id
                """)
                .param("id", locationId)
                .query((rs, rowNum) -> new WarehouseLocationCode(rs.getString("wh_code"), rs.getString("loc_code")))
                .single();
    }
}
