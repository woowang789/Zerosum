package com.zerosum.inventory.repository;

import com.zerosum.inventory.posting.PostingCommand;
import java.sql.Timestamp;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 거래 헤더 기록. proposal_id는 1단계 범위 밖(AI 제안)이라 항상 NULL이다. */
@Repository
public class InventoryTxnRepository {

    private final JdbcClient jdbc;

    InventoryTxnRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long insert(PostingCommand cmd) {
        return jdbc.sql("""
                INSERT INTO inventory_txn
                    (idem_key, txn_type, source_type, source_ref, reason_code,
                     actor_type, actor_id, proposal_id, reverses_txn_id, occurred_at)
                VALUES (:idemKey, :txnType, :sourceType, :sourceRef, :reasonCode,
                        :actorType, :actorId, NULL, :reversesTxnId, :occurredAt)
                RETURNING id
                """)
                .param("idemKey", cmd.idemKey())
                .param("txnType", cmd.txnType())
                .param("sourceType", cmd.sourceType())
                .param("sourceRef", cmd.sourceRef())
                .param("reasonCode", cmd.reasonCode())
                .param("actorType", cmd.actorType())
                .param("actorId", cmd.actorId())
                .param("reversesTxnId", cmd.reversesTxnId())
                .param("occurredAt", Timestamp.from(cmd.occurredAt()))
                .query(Long.class)
                .single();
    }
}
