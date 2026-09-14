package com.zerosum.inventory.repository;

import com.zerosum.inventory.domain.BalanceSnapshot;
import com.zerosum.inventory.domain.IdempotencyConflictException;
import com.zerosum.inventory.domain.Posted;
import com.zerosum.inventory.domain.PostingOutcome;
import com.zerosum.inventory.domain.PreconditionFailed;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 멱등 키 선점과 결과 기록. db/04-harness.sql의 tst_post ①·⑦에 대응한다.
 * 실수로 트랜잭션 밖에서 호출되면 즉시 예외가 나도록 MANDATORY로 건다 (docs/04-write-path.md 구현 메모).
 */
@Repository
public class IdempotencyRepository {

    private final JdbcClient jdbc;

    IdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 키를 선점한다. 새로 선점했으면(=최초 실행) 빈 Optional을 돌려주고 호출자는 포스팅을 계속 진행한다.
     * 이미 처리된 키면 저장된 결과를 재현해 돌려준다. request_hash가 다르면 409에 해당하는 예외를 던진다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PostingOutcome> claim(String idemKey, String commandType, String requestHash) {
        int inserted = jdbc.sql("""
                INSERT INTO idempotency_record (idem_key, command_type, request_hash)
                VALUES (:idemKey, :commandType, :requestHash)
                ON CONFLICT (idem_key) DO NOTHING
                """)
                .param("idemKey", idemKey)
                .param("commandType", commandType)
                .param("requestHash", requestHash)
                .update();

        if (inserted > 0) {
            return Optional.empty();
        }

        record StoredRow(String requestHash, String txnId, String status) {
        }

        StoredRow row = jdbc.sql("""
                SELECT request_hash, result ->> 'txnId' AS txn_id, result ->> 'status' AS status
                FROM idempotency_record WHERE idem_key = :idemKey
                """)
                .param("idemKey", idemKey)
                .query((rs, rowNum) -> new StoredRow(
                        rs.getString("request_hash"), rs.getString("txn_id"), rs.getString("status")))
                .single();

        if (!row.requestHash().equals(requestHash)) {
            throw new IdempotencyConflictException(idemKey);
        }
        if (row.txnId() != null) {
            return Optional.of(new Posted(Long.parseLong(row.txnId())));
        }
        // PRECONDITION_FAILED 재현: 1단계는 선행 조건을 쓰지 않아 이 경로를 실제로 타지 않는다.
        return Optional.of(new PreconditionFailed(BalanceSnapshot.empty()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void completePosted(String idemKey, long txnId) {
        jdbc.sql("UPDATE idempotency_record SET result = jsonb_build_object('txnId', :txnId) WHERE idem_key = :idemKey")
                .param("txnId", txnId)
                .param("idemKey", idemKey)
                .update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void completePreconditionFailed(String idemKey) {
        jdbc.sql("""
                UPDATE idempotency_record SET result = jsonb_build_object('status', 'PRECONDITION_FAILED')
                WHERE idem_key = :idemKey
                """)
                .param("idemKey", idemKey)
                .update();
    }
}
