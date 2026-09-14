package com.zerosum.inventory.repository;

import com.zerosum.inventory.domain.BasisRef;
import com.zerosum.inventory.domain.IssueException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * ai_proposer 계정 전용 저장소. action_proposal INSERT·PENDING 조회와 inventory_issue.ai_analysis 기록을
 * 한다 — V4 마이그레이션의 컬럼 단위 GRANT(action_proposal: id, proposal_type, command_payload, status,
 * created_at, expires_at / inventory_issue: SELECT(id, status), UPDATE(ai_analysis))가 실제로 할 수 있는
 * 일의 전부다. primary(app_rw) JdbcClient가 아니라 {@code aiProposerJdbcClient}를 주입받아야 DB 권한 경계가
 * 앱 코드의 규율이 아니라 계정 자체로 지켜진다.
 *
 * <p>{@link #insertCanonical}과 {@link #findPending}은 같은 정규화(payload CTE)를 공유한다 — 엔트리 배열
 * 순서와 숫자 표기(20 vs 20.0)를 없애는 로직이 두 군데서 따로 놀면, uq_proposal_pending이 보는 해시와
 * 재시도 뒤 조회하는 해시가 어긋나 재시도 흡수가 조용히 깨진다.
 */
@Repository
public class AiProposalRepository {

    // uq_proposal_pending(V1)이 쓰는 md5(command_payload::text)와 항상 같은 결과가 나오도록,
    // 엔트리 배열 순서(ORDER BY)와 숫자 표기(qty를 정수로 캐스트)를 정규화한 payload를 만든다.
    // insertCanonical·findPending 두 문장 모두 이 CTE 뒤에 이어 붙인다.
    //
    // qty 레코드 필드는 NUMERIC으로 받는다 — jsonb_to_recordset은 "20.0" 같은 소수 표기를 곧바로 INT로
    // 캐스트하면 "invalid input syntax for type integer"로 실패한다(실측). NUMERIC으로 받은 뒤
    // jsonb_build_object에서 ::INT로 한 번 더 캐스트해야 20과 20.0이 같은 정수로 정규화된다.
    private static final String NORMALIZED_PAYLOAD_CTE = """
            WITH payload AS (
              SELECT jsonb_build_object(
                       'txnType',    CAST(:payload AS JSONB) ->> 'txnType',
                       'reasonCode', CAST(:payload AS JSONB) ->> 'reasonCode',
                       'issueId',    CAST(:payload AS JSONB) ->  'issueId',
                       'entries',    jsonb_agg(jsonb_build_object('wh', e.wh, 'loc', e.loc, 'sku', e.sku,
                                                                  'lot', e.lot, 'qty', e.qty::INT)
                                               ORDER BY e.wh, e.loc, e.sku, e.lot, e.qty)) AS canonical
              FROM jsonb_to_recordset(CAST(:payload AS JSONB) -> 'entries')
                     AS e(wh TEXT, loc TEXT, sku TEXT, lot TEXT, qty NUMERIC)
            )
            """;

    private final JdbcClient jdbc;

    AiProposalRepository(@Qualifier("aiProposerJdbcClient") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 정규화한 payload로 제안을 INSERT하고, basis_snapshot은 서버가 직접 채운다 — balance 스코프는
     * v_balance_basis에서, warehouse_sku 스코프는 v_warehouse_sku_basis에서 읽는다(둘 다 V4가 ai_proposer에
     * SELECT를 줬다). uq_proposal_pending(같은 타입의 동일 PENDING 제안)과 충돌하면 0행 → 빈 OptionalLong.
     * 호출자는 {@link #findPending}으로 기존 제안의 id를 되찾는다.
     */
    public OptionalLong insertCanonical(String proposalType, String payloadJson, String rationale, String proposedBy,
            String agentMetaJson, List<BasisRef> basisRefs, Duration ttl) {
        Optional<Long> id = jdbc.sql(NORMALIZED_PAYLOAD_CTE + """
                , basis_refs AS (
                  SELECT * FROM jsonb_to_recordset(CAST(:basisRefs AS JSONB))
                         AS o(scope TEXT, wh TEXT, loc TEXT, sku TEXT, lot TEXT)
                ), balance_obs AS (
                  SELECT jsonb_build_object('scope', 'balance', 'balance_id', b.balance_id,
                                            'available_qty', b.available_qty) AS obs,
                         b.balance_id AS ord2
                  FROM basis_refs o
                  JOIN v_balance_basis b ON b.warehouse_code = o.wh AND b.location_code = o.loc
                                        AND b.sku_code = o.sku AND b.lot_no = o.lot
                  WHERE o.scope = 'balance'
                ), warehouse_sku_obs AS (
                  -- wh·sku는 코드로 받아 v_warehouse_sku_basis(V4)로 조인한다 — 그 뷰가 소유자 권한으로
                  -- warehouse·sku를 대신 조인해주므로 ai_proposer에 마스터 테이블 권한을 주지 않아도 된다.
                  SELECT jsonb_build_object('scope', 'warehouse_sku', 'warehouse_id', v.warehouse_id,
                                            'sku_id', v.sku_id, 'sellable_qty', v.sellable_qty) AS obs,
                         v.warehouse_id AS ord2, v.sku_id AS ord3
                  FROM (SELECT DISTINCT o.wh AS warehouse_code, o.sku AS sku_code
                        FROM basis_refs o WHERE o.scope = 'warehouse_sku') req
                  JOIN v_warehouse_sku_basis v ON v.warehouse_code = req.warehouse_code AND v.sku_code = req.sku_code
                ), basis AS (
                  -- 두 스코프를 하나의 배열로 합친다. ORDER BY로 순서를 고정해 재시도마다 basis_snapshot이
                  -- 흔들리지 않게 한다(해시 대상은 command_payload뿐이라 재시도 흡수에는 영향 없지만 진단에 좋다).
                  SELECT jsonb_build_object('captured_at', now(), 'observations',
                           COALESCE((
                             SELECT jsonb_agg(obs ORDER BY ord1, ord2, ord3) FROM (
                               SELECT obs, 0 AS ord1, ord2, 0::BIGINT AS ord3 FROM balance_obs
                               UNION ALL
                               SELECT obs, 1 AS ord1, ord2, ord3 FROM warehouse_sku_obs
                             ) combined
                           ), '[]'::JSONB)) AS snap
                )
                INSERT INTO action_proposal (proposal_type, command_payload, basis_snapshot, rationale,
                                             proposed_by, agent_meta, expires_at)
                SELECT :type, payload.canonical, basis.snap, :rationale, :proposedBy,
                       CAST(:agentMeta AS JSONB), now() + CAST(:ttl AS INTERVAL)
                FROM payload, basis
                ON CONFLICT (proposal_type, (md5(command_payload::text))) WHERE status = 'PENDING' DO NOTHING
                RETURNING id
                """)
                .param("payload", payloadJson)
                .param("basisRefs", toBasisRefsJson(basisRefs))
                .param("type", proposalType)
                .param("rationale", rationale)
                .param("proposedBy", proposedBy)
                .param("agentMeta", agentMetaJson)
                .param("ttl", ttl.toSeconds() + " seconds")
                .query(Long.class)
                .optional();
        return id.map(OptionalLong::of).orElseGet(OptionalLong::empty);
    }

    /** 같은 정규화를 거친 payload의 md5로 PENDING 제안을 찾는다. insertCanonical과 같은 CTE를 쓴다. */
    public OptionalLong findPending(String proposalType, String payloadJson) {
        Optional<Long> id = jdbc.sql(NORMALIZED_PAYLOAD_CTE + """
                SELECT ap.id FROM action_proposal ap, payload
                WHERE ap.proposal_type = :type AND ap.status = 'PENDING'
                  AND md5(ap.command_payload::text) = md5(payload.canonical::text)
                """)
                .param("payload", payloadJson)
                .param("type", proposalType)
                .query(Long.class)
                .optional();
        return id.map(OptionalLong::of).orElseGet(OptionalLong::empty);
    }

    /**
     * AI 원인 분석 결과 기록. 종결된(RESOLVED) 이슈에 덧쓰면 기록이 사후에 바뀐 것처럼 보이므로
     * OPEN·ACKED에서만 허용한다 — {@link ReconciliationRepository#acknowledge}·{@link
     * ReconciliationRepository#resolve}와 같은 패턴으로 조건부 UPDATE의 영향 행 수로 판정한다.
     * status·acked_*는 컬럼 단위 GRANT가 애초에 SELECT·UPDATE 어느 쪽도 주지 않아 SQL에 넣을 수조차
     * 없다(V4) — 여기서 status를 WHERE에 쓸 수 있는 것도 (id, status) 컬럼 SELECT를 받았기 때문이다.
     */
    public void writeIssueAnalysis(long issueId, String analysisJson) {
        int updated = jdbc.sql("""
                UPDATE inventory_issue SET ai_analysis = CAST(:json AS JSONB)
                WHERE id = :id AND status IN ('OPEN', 'ACKED')
                """)
                .param("json", analysisJson)
                .param("id", issueId)
                .update();
        if (updated != 1) {
            throw new IssueException("ISSUE_NOT_OPEN_OR_ACKED",
                    "이슈 %d에는 분석을 쓸 수 없다(존재하지 않거나 이미 RESOLVED다)".formatted(issueId));
        }
    }

    /**
     * 생성 단계 검증용 스냅샷. 정규화(NORMALIZED_PAYLOAD_CTE)와 같은 방식 — PostgreSQL이 JSONB로 파싱한
     * 결과 — 로 payload를 읽는다. 원본 JSON 텍스트에 정규식을 걸면, 정규화가 버리는 위치(엔트리 안쪽 등)에
     * 있는 값에 속아 검증과 저장 결과가 어긋난다. 테이블을 건드리지 않는 순수 JSONB 표현식이라 ai_proposer
     * 권한으로도 실행할 수 있다.
     */
    public record PayloadValidation(String txnType, String reasonCode, boolean hasEntries) {
    }

    /** txnType·reasonCode는 최상위 값만(정규화가 남기는 것과 동일), entries는 비어 있지 않은지만 본다. */
    public PayloadValidation validatePayload(String payloadJson) {
        return jdbc.sql("""
                SELECT CAST(:payload AS JSONB) ->> 'txnType' AS txn_type,
                       CAST(:payload AS JSONB) ->> 'reasonCode' AS reason_code,
                       jsonb_array_length(COALESCE(CAST(:payload AS JSONB) -> 'entries', '[]'::JSONB)) > 0
                           AS has_entries
                """)
                .param("payload", payloadJson)
                .query((rs, rowNum) -> new PayloadValidation(rs.getString("txn_type"), rs.getString("reason_code"),
                        rs.getBoolean("has_entries")))
                .single();
    }

    // basisRefs는 필드 다섯 개짜리 단순 구조라 Jackson 없이 직접 JSON 텍스트를 만든다
    // (payload 자체의 배열·숫자 정규화는 위 CTE의 jsonb_to_recordset이 담당한다).
    // warehouse_sku 스코프는 locationCode·lotNo가 널일 수 있어 quote 대신 quoteOrNull을 쓴다.
    private static String toBasisRefsJson(List<BasisRef> basisRefs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < basisRefs.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            BasisRef ref = basisRefs.get(i);
            sb.append("{\"scope\":").append(quote(ref.scope()))
                    .append(",\"wh\":").append(quote(ref.warehouseCode()))
                    .append(",\"loc\":").append(quoteOrNull(ref.locationCode()))
                    .append(",\"sku\":").append(quote(ref.skuCode()))
                    .append(",\"lot\":").append(quoteOrNull(ref.lotNo()))
                    .append('}');
        }
        return sb.append(']').toString();
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String quoteOrNull(String value) {
        return value == null ? "null" : quote(value);
    }
}
