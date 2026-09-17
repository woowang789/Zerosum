package com.zerosum.inventory.repository;

import com.zerosum.inventory.domain.BalanceObservation;
import com.zerosum.inventory.domain.ProposalException;
import com.zerosum.inventory.domain.WarehouseSkuObservation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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

        BasisObservations obs = parseBasisObservations(row.basisSnapshot());

        return new LockedProposal(row.id(), row.proposalType(), row.status(), row.expired(), obs.balance(),
                obs.warehouseSku(), row.issueId());
    }

    public record BasisObservations(List<BalanceObservation> balance,
            List<WarehouseSkuObservation> warehouseSku) {
    }

    /**
     * 대기 제안 목록 — idx_proposal_status_created(V4)가 노리는 유일한 조회 경로다. 승인 트랜잭션 밖에서
     * 불리므로(화면이 부른다) MANDATORY를 붙이지 않는다 — SQL 한 문장이라 별도 트랜잭션 없이도 원자적이다.
     *
     * <p>창고 필터가 까다롭다: action_proposal에는 창고 컬럼이 없고 창고는 command_payload의
     * entries[].wh 안에 있다. 제안 생성(ProposalCreationService#validate)이 이미 모든 entries가 같은
     * 창고이도록 강제하므로, 첫 엔트리(entries -> 0)의 wh만 봐도 전체를 본 것과 같다. 정규식이 아니라
     * JSONB -> / ->> 연산자로 읽는다.
     */
    public List<PendingProposalRow> listPending(String warehouseCode, int limit) {
        return jdbc.sql("""
                SELECT id, proposal_type, rationale, created_at, expires_at
                FROM action_proposal
                WHERE status = 'PENDING'
                  AND expires_at > now()
                  AND command_payload -> 'entries' -> 0 ->> 'wh' = :warehouseCode
                ORDER BY created_at DESC
                LIMIT :limit
                """)
                .param("warehouseCode", warehouseCode)
                .param("limit", limit)
                .query((rs, rowNum) -> new PendingProposalRow(rs.getLong("id"), rs.getString("proposal_type"),
                        rs.getString("rationale"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant()))
                .list();
    }

    public record PendingProposalRow(long id, String proposalType, String rationale, Instant createdAt,
            Instant expiresAt) {
    }

    /**
     * 제안 상세 — 승인 화면이 보여줄 것 전부. 헤더와 엔트리 목록을 쿼리 두 개로 나눠 읽으므로
     * (lockForUpdate()와 같은 모양) 승인 트랜잭션 밖에서도 하나의 스냅샷으로 보이도록 읽기 전용
     * 트랜잭션으로 감싼다.
     */
    @Transactional(readOnly = true)
    public Optional<ProposalDetail> detail(long proposalId) {
        record ProposalHeader(long id, String proposalType, String rationale, String proposedBy, String agentMeta,
                String status, Instant createdAt, Instant expiresAt, String decisionNote,
                Long issueId) {
        }

        Optional<ProposalHeader> header = jdbc.sql("""
                SELECT id, proposal_type, rationale, proposed_by, agent_meta::TEXT AS agent_meta, status,
                       created_at, expires_at, decision_note, (command_payload ->> 'issueId')::BIGINT AS issue_id
                FROM action_proposal
                WHERE id = :id
                """)
                .param("id", proposalId)
                .query((rs, rowNum) -> new ProposalHeader(rs.getLong("id"), rs.getString("proposal_type"),
                        rs.getString("rationale"), rs.getString("proposed_by"), rs.getString("agent_meta"),
                        rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant(), rs.getString("decision_note"),
                        (Long) rs.getObject("issue_id")))
                .optional();

        return header.map(h -> new ProposalDetail(h.id(), h.proposalType(), entriesOf(proposalId), h.rationale(),
                h.proposedBy(), h.agentMeta(), h.status(), h.createdAt(), h.expiresAt(), h.decisionNote(),
                h.issueId()));
    }

    public record ProposalDetail(long id, String proposalType, List<PayloadLine> entries, String rationale,
            String proposedBy, String agentMeta, String status, Instant createdAt,
            Instant expiresAt, String decisionNote, Long issueId) {
    }

    /** command_payload의 entries — payloadLines()와 같은 SQL이지만 MANDATORY 전파 없이 detail()에서만 쓴다. */
    private List<PayloadLine> entriesOf(long proposalId) {
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
     * basis_snapshot 관측값 — lockForUpdate()와 같은 파싱이지만 FOR UPDATE 없이 읽기만 한다. 승인 화면의
     * 근거 대조(ProposalBasisReviewService)가 승인 트랜잭션 밖에서 쓴다.
     */
    public BasisObservations basisObservationsOf(long proposalId) {
        String basisSnapshot = jdbc
                .sql("SELECT basis_snapshot::TEXT AS basis_snapshot FROM action_proposal WHERE id = :id")
                .param("id", proposalId)
                .query(String.class)
                .single();
        return parseBasisObservations(basisSnapshot);
    }

    /**
     * basis_snapshot(JSONB 텍스트)에서 scope별 관측값을 갈라 읽는다. lockForUpdate()·basisObservationsOf()가
     * 공유한다 — 전자는 FOR UPDATE로 잠근 행에서, 후자는 승인 화면 미리보기용으로 잠그지 않은 행에서
     * 읽지만 basis_snapshot을 관측값으로 바꾸는 파싱 자체는 같다.
     */
    private BasisObservations parseBasisObservations(String basisSnapshotJson) {
        // 식별자나 수량이 빠진 항목은 관측이 아니다 — 버린다. NULL을 그대로 읽으면 rs.getLong·getInt가
        // 0으로 접어 WarehouseSkuObservation(0, 0, 0) 같은 "아무것도 가리키지 않는 관측"이 만들어지고,
        // 그것이 현재값 0과 0 대 0으로 대조에 통과한다. ai_proposer는 action_proposal에 테이블 단위
        // INSERT 권한이 있으므로(V2:47) {"scope":"warehouse_sku"} 한 줄만 넣어 재검증 전체를 무력화할 수
        // 있었다. 여기서 버리면 그런 제안은 관측 0건이 되어 승인 시점 ③-2에 걸린다.
        List<BalanceObservation> balance = jdbc.sql("""
                SELECT (o ->> 'balance_id')::BIGINT AS balance_id, (o ->> 'available_qty')::INT AS available_qty
                FROM jsonb_array_elements(CAST(:basis AS JSONB) -> 'observations') o
                WHERE o ->> 'scope' = 'balance'
                  AND o ->> 'balance_id' IS NOT NULL AND o ->> 'available_qty' IS NOT NULL
                """)
                .param("basis", basisSnapshotJson)
                .query((rs, rowNum) -> new BalanceObservation(rs.getLong("balance_id"),
                        rs.getInt("available_qty")))
                .list();

        List<WarehouseSkuObservation> warehouseSku = jdbc.sql("""
                SELECT (o ->> 'warehouse_id')::BIGINT AS warehouse_id, (o ->> 'sku_id')::BIGINT AS sku_id,
                       (o ->> 'sellable_qty')::INT AS sellable_qty
                FROM jsonb_array_elements(CAST(:basis AS JSONB) -> 'observations') o
                WHERE o ->> 'scope' = 'warehouse_sku'
                  AND o ->> 'warehouse_id' IS NOT NULL AND o ->> 'sku_id' IS NOT NULL
                  AND o ->> 'sellable_qty' IS NOT NULL
                """)
                .param("basis", basisSnapshotJson)
                .query((rs, rowNum) -> new WarehouseSkuObservation(rs.getLong("warehouse_id"),
                        rs.getLong("sku_id"), rs.getInt("sellable_qty")))
                .list();

        return new BasisObservations(balance, warehouseSku);
    }

    /**
     * balance_id 목록의 현재 available_qty(v_balance_basis, 잠금 없음) — 근거 대조 화면(미리보기)용.
     * 잔액 행을 잠그지 않으므로 승인 트랜잭션(BasisRecheck.balanceObservationsHold가 FOR UPDATE 아래서
     * 재검증하는 것)과 다른 스냅샷일 수 있다 — 그래서 화면은 미리보기고 최종 판단은 승인이 한다.
     * 행을 찾지 못한 balance_id는(잔액 행은 지워지지 않으므로 실무에서는 일어나지 않는다) 결과에서
     * 빠진다 — AllocationRepository#consumedQtyByBalance와 같은 {@code IN (:ids)} 패턴이라, "없으면
     * 0으로 본다"는 호출자(ProposalBasisReviewService)가 맵 조회 기본값으로 처리한다.
     */
    public List<BalanceObservation> currentBalances(List<Long> balanceIds) {
        if (balanceIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT balance_id, available_qty FROM v_balance_basis WHERE balance_id IN (:ids)")
                .param("ids", balanceIds)
                .query((rs, rowNum) -> new BalanceObservation(rs.getLong("balance_id"), rs.getInt("available_qty")))
                .list();
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
        return queryCurrentWarehouseSku(observed);
    }

    /**
     * currentWarehouseSku()와 같은 쿼리 — 애초에 잠그지 않는 조회라(잠글 수 없는 관측값이다) 안전하게
     * MANDATORY 없이도 노출한다. 근거 대조 화면(ProposalBasisReviewService)이 승인 트랜잭션 밖에서 쓴다.
     */
    public List<WarehouseSkuObservation> currentWarehouseSkuPreview(List<WarehouseSkuObservation> observed) {
        return queryCurrentWarehouseSku(observed);
    }

    private List<WarehouseSkuObservation> queryCurrentWarehouseSku(List<WarehouseSkuObservation> observed) {
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

    /**
     * PENDING → REJECTED. decision_note에 거부 사유를 남긴다. markExecuted·markStale·markExpired와 같은
     * 패턴(조건부 UPDATE + 영향 행 수 판정)이고, decided_at도 함께 채운다 —
     * {@code CHECK ((status = 'PENDING') = (decided_at IS NULL))}가 빠뜨리는 것을 허용하지 않는다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markRejected(long proposalId, String rejectedBy, String note) {
        int updated = jdbc.sql("""
                UPDATE action_proposal
                SET status = 'REJECTED', decided_by = :rejectedBy, decided_at = now(), decision_note = :note
                WHERE id = :id AND status = 'PENDING'
                """)
                .param("rejectedBy", rejectedBy)
                .param("note", note)
                .param("id", proposalId)
                .update();
        requireExactlyOne(updated, proposalId, "REJECTED");
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
