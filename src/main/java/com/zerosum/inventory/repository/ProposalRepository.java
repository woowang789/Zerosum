package com.zerosum.inventory.repository;

import com.zerosum.inventory.domain.BalanceObservation;
import com.zerosum.inventory.domain.ProposalException;
import com.zerosum.inventory.domain.WarehouseSkuObservation;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * app_rw(primary) 전용 저장소. 승인 트랜잭션(ProposalApprovalService#approve) 안에서만 불려야 하므로
 * 전 메서드가 {@code @Transactional(propagation = MANDATORY)}다 — 승인 트랜잭션 밖에서 불리면 즉시 예외가
 * 나서, 잠금 없이 상태를 읽고 바꾸는 사고를 컴파일이 아니라 런타임에서라도 막는다 (docs/04:121 각주).
 */
@Repository
public class ProposalRepository {

    public record LockedProposal(long id, String proposalType, String status, boolean expired,
            List<BalanceObservation> balanceObservations,
            List<WarehouseSkuObservation> warehouseSkuObservations, Long issueId) {
    }

    public record PayloadLine(String warehouseCode, String locationCode, String skuCode, String lotNo, int qty) {
    }

    private final JdbcClient jdbc;

    ProposalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 제안 행을 잠근다 — 승인 연타·동시 승인을 직렬화한다 (db/04-harness.sql tst_approve_proposal 첫 줄).
     * basis_snapshot의 관측값은 scope별로 갈라 돌려준다: balance 스코프는 잔액 잠금 직후 재검증에,
     * warehouse_sku 스코프는 포스팅 전 신선도 검사에 쓰인다 (docs/07-ai-integration.md).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LockedProposal lockForUpdate(long proposalId) {
        record LockedRow(long id, String proposalType, String status, boolean expired, Long issueId,
                String basisSnapshot) {
        }

        LockedRow row = jdbc.sql("""
                SELECT id, proposal_type, status, (expires_at <= now()) AS expired,
                       (command_payload ->> 'issueId')::BIGINT AS issue_id,
                       basis_snapshot::TEXT AS basis_snapshot
                FROM action_proposal
                WHERE id = :id
                FOR UPDATE
                """)
                .param("id", proposalId)
                .query((rs, rowNum) -> new LockedRow(rs.getLong("id"), rs.getString("proposal_type"),
                        rs.getString("status"), rs.getBoolean("expired"), (Long) rs.getObject("issue_id"),
                        rs.getString("basis_snapshot")))
                .single();

        List<BalanceObservation> balanceObservations = jdbc.sql("""
                SELECT (o ->> 'balance_id')::BIGINT AS balance_id, (o ->> 'available_qty')::INT AS available_qty
                FROM jsonb_array_elements(CAST(:basis AS JSONB) -> 'observations') o
                WHERE o ->> 'scope' = 'balance'
                """)
                .param("basis", row.basisSnapshot())
                .query((rs, rowNum) -> new BalanceObservation(rs.getLong("balance_id"),
                        rs.getInt("available_qty")))
                .list();

        List<WarehouseSkuObservation> warehouseSkuObservations = jdbc.sql("""
                SELECT (o ->> 'warehouse_id')::BIGINT AS warehouse_id, (o ->> 'sku_id')::BIGINT AS sku_id,
                       (o ->> 'sellable_qty')::INT AS sellable_qty
                FROM jsonb_array_elements(CAST(:basis AS JSONB) -> 'observations') o
                WHERE o ->> 'scope' = 'warehouse_sku'
                """)
                .param("basis", row.basisSnapshot())
                .query((rs, rowNum) -> new WarehouseSkuObservation(rs.getLong("warehouse_id"),
                        rs.getLong("sku_id"), rs.getInt("sellable_qty")))
                .list();

        return new LockedProposal(row.id(), row.proposalType(), row.status(), row.expired(), balanceObservations,
                warehouseSkuObservations, row.issueId());
    }

    /** command_payload의 entries를 물리 줄 순서(정규화 순서)대로 돌려준다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<PayloadLine> payloadLines(long proposalId) {
        return jdbc.sql("""
                SELECT e.wh, e.loc, e.sku, e.lot, e.qty
                FROM action_proposal p,
                     jsonb_to_recordset(p.command_payload -> 'entries') AS e(wh TEXT, loc TEXT, sku TEXT, lot TEXT, qty INT)
                WHERE p.id = :id
                """)
                .param("id", proposalId)
                .query((rs, rowNum) -> new PayloadLine(rs.getString("wh"), rs.getString("loc"), rs.getString("sku"),
                        rs.getString("lot"), rs.getInt("qty")))
                .list();
    }

    /**
     * command_payload 최상위 reasonCode. ADJUSTMENT가 아니면 널이다 — {@code .single()}은 결과 행이 있어도
     * 값이 널이면 TypeMismatchDataAccessException을 던지므로(널을 허용하지 않는 단언), {@code .optional()}로
     * 받아 널을 그대로 통과시킨다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String reasonCodeOf(long proposalId) {
        return jdbc.sql("SELECT command_payload ->> 'reasonCode' AS reason_code FROM action_proposal WHERE id = :id")
                .param("id", proposalId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    /** PENDING → EXECUTED. 영향 행이 1이 아니면(이미 다른 결정이 났으면) 예외 — 승인 잠금 아래라 정상 경로에서는 항상 1이다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markExecuted(long proposalId, String approver, long txnId) {
        int updated = jdbc.sql("""
                UPDATE action_proposal
                SET status = 'EXECUTED', decided_by = :approver, decided_at = now(), executed_txn_id = :txnId
                WHERE id = :id AND status = 'PENDING'
                """)
                .param("approver", approver)
                .param("txnId", txnId)
                .param("id", proposalId)
                .update();
        requireExactlyOne(updated, proposalId, "EXECUTED");
    }

    /** PENDING → STALE. basis 재검증 실패(포스팅 전) 또는 PreconditionFailed(잔액 잠금 후) 양쪽에서 부른다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markStale(long proposalId, String approver) {
        int updated = jdbc.sql("""
                UPDATE action_proposal SET status = 'STALE', decided_by = :approver, decided_at = now()
                WHERE id = :id AND status = 'PENDING'
                """)
                .param("approver", approver)
                .param("id", proposalId)
                .update();
        requireExactlyOne(updated, proposalId, "STALE");
    }

    /** PENDING → EXPIRED. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markExpired(long proposalId, String approver) {
        int updated = jdbc.sql("""
                UPDATE action_proposal SET status = 'EXPIRED', decided_by = :approver, decided_at = now()
                WHERE id = :id AND status = 'PENDING'
                """)
                .param("approver", approver)
                .param("id", proposalId)
                .update();
        requireExactlyOne(updated, proposalId, "EXPIRED");
    }

    private void requireExactlyOne(int updated, long proposalId, String targetStatus) {
        if (updated != 1) {
            throw new ProposalException("PROPOSAL_NOT_PENDING",
                    "제안 %d는 PENDING 상태가 아니어서 %s로 바꿀 수 없다".formatted(proposalId, targetStatus));
        }
    }

    /**
     * 잠글 수 없는 관측값의 현재 값. v_sellable_stock(V2, app_rw에 이미 GRANT됨)에서 창고·SKU 조합별로
     * 다시 읽는다. 물리 STORAGE 재고가 전혀 없는 조합은 뷰에 행 자체가 없으므로 0으로 본다
     * (SellableStockRepository#find와 같은 규칙).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<WarehouseSkuObservation> currentWarehouseSku(
            List<WarehouseSkuObservation> observed) {
        if (observed.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT req.warehouse_id, req.sku_id, COALESCE(v.sellable_qty, 0) AS sellable_qty
                FROM jsonb_to_recordset(CAST(:pairs AS JSONB)) AS req(warehouse_id BIGINT, sku_id BIGINT)
                LEFT JOIN v_sellable_stock v ON v.warehouse_id = req.warehouse_id AND v.sku_id = req.sku_id
                """)
                .param("pairs", toPairsJson(observed))
                .query((rs, rowNum) -> new WarehouseSkuObservation(rs.getLong("warehouse_id"),
                        rs.getLong("sku_id"), rs.getInt("sellable_qty")))
                .list();
    }

    // AiProposalRepository#toBasisRefsJson과 같은 이유로 Jackson 없이 직접 짠다 — 필드 두 개짜리 단순 구조다.
    private static String toPairsJson(List<WarehouseSkuObservation> observed) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < observed.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            WarehouseSkuObservation o = observed.get(i);
            sb.append("{\"warehouse_id\":").append(o.warehouseId()).append(",\"sku_id\":").append(o.skuId())
                    .append('}');
        }
        return sb.append(']').toString();
    }
}
