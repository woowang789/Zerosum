import { useEffect, useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiGet, apiPost } from '../api/client'
import { AiNote, ApiErrorMessage, ExpiryCountdown, formatDateTime, useWarehouseGate, validBadge } from './shared'

interface PendingProposalRow {
  id: number
  proposalType: string
  rationale: string
  createdAt: string
  expiresAt: string
}

interface PayloadLine {
  warehouseCode: string
  locationCode: string
  skuCode: string
  lotNo: string
  qty: number
}

interface ProposalDetail {
  id: number
  proposalType: string
  entries: PayloadLine[]
  rationale: string
  proposedBy: string
  agentMeta: string | null
  status: string
  createdAt: string
  expiresAt: string
  decisionNote: string | null
  issueId: number | null
}

interface Comparison {
  observed: number
  current: number
  diff: number
  allowedDiff: number
  valid: boolean
}

interface BalanceReview {
  balanceId: number
  comparison: Comparison
}

interface WarehouseSkuReview {
  warehouseId: number
  skuId: number
  comparison: Comparison
}

interface BasisReview {
  balance: BalanceReview[]
  warehouseSku: WarehouseSkuReview[]
}

interface ProposalDetailResponse {
  proposal: ProposalDetail
  basisReview: BasisReview
}

// ApprovalOutcome은 코어의 sealed interface(Executed|AlreadyDecided|Stale|ProposalExpired)를 그대로
// JSON으로 내려준다. Stale은 {proposalId}, ProposalExpired도 {proposalId} — 두 타입의 JSON 모양이
// 같아서 필드만으로는 구분할 수 없다. 승인 뒤 상세를 다시 불러오면(아래 invalidateQueries) proposal.status가
// 진짜 결과(STALE 또는 EXPIRED)를 보여주므로, 여기서는 확정하지 못하는 경우 안내문으로 대신하고 상세의
// 상태 배지로 확인하게 한다.
interface ApprovalOutcome {
  proposalId: number
  txnId?: number
  status?: string
}

const STATUS_LABEL: Record<string, string> = {
  PENDING: '대기',
  EXECUTED: '실행됨',
  REJECTED: '거부됨',
  STALE: '기준 이탈로 종료',
  EXPIRED: '만료됨',
}

function describeApprovalOutcome(outcome: ApprovalOutcome): string {
  if (outcome.txnId !== undefined) {
    return `실행됐다 (거래 #${outcome.txnId})`
  }
  if (outcome.status !== undefined) {
    return `이미 결정이 난 제안이었다 (현재 상태: ${STATUS_LABEL[outcome.status] ?? outcome.status})`
  }
  return '실행되지 않았다 — 근거가 허용 오차를 벗어났거나(STALE) 유효 기간이 지나(EXPIRED) 자동으로 닫혔다. 아래 상태 배지에서 확정된 결과를 확인하라.'
}

export function ProposalsScreen() {
  const { me, gate } = useWarehouseGate('제안 승인')
  const isSupervisor = me?.roles.includes('SUPERVISOR') ?? false
  const [warehouse, setWarehouse] = useState<string | null>(null)
  const [selectedId, setSelectedId] = useState<number | null>(null)

  useEffect(() => {
    if (!warehouse && me && me.warehouses.length > 0) {
      setWarehouse(me.warehouses[0])
    }
  }, [me, warehouse])

  const listQuery = useQuery({
    queryKey: ['proposals', warehouse],
    queryFn: () => apiGet<PendingProposalRow[]>(`/api/proposals?warehouse=${encodeURIComponent(warehouse ?? '')}`),
    enabled: warehouse != null,
  })

  if (me === null) {
    return gate
  }

  return (
    <div className="screen">
      <header className="screen-header">
        <h1>제안 승인</h1>
      </header>

      <div className="stock-filters">
        <label className="field field-inline">
          <span className="field-label">창고</span>
          <select
            value={warehouse ?? ''}
            onChange={(e) => {
              setWarehouse(e.target.value)
              setSelectedId(null)
            }}
          >
            {(me?.warehouses ?? []).map((code) => (
              <option key={code} value={code}>
                {code}
              </option>
            ))}
          </select>
        </label>
      </div>

      {listQuery.isLoading && <p className="state-message">불러오는 중…</p>}
      {listQuery.error && <ApiErrorMessage error={listQuery.error} />}

      {listQuery.data && (
        <div className="table-panel">
          <table className="data-table">
            <thead>
              <tr>
                <th>ID</th>
                <th>유형</th>
                <th>사유(AI 작성)</th>
                <th>생성</th>
                <th>만료까지</th>
              </tr>
            </thead>
            <tbody>
              {listQuery.data.length === 0 && (
                <tr>
                  <td colSpan={5} className="state-message">
                    대기 중인 제안이 없다
                  </td>
                </tr>
              )}
              {listQuery.data.map((row) => (
                <tr
                  key={row.id}
                  className={`row-clickable${row.id === selectedId ? ' is-selected' : ''}`}
                  onClick={() => setSelectedId(row.id)}
                >
                  <td className="mono">{row.id}</td>
                  <td className="mono">{row.proposalType}</td>
                  <td className="rationale-cell">{row.rationale}</td>
                  <td className="mono">{formatDateTime(row.createdAt)}</td>
                  <td>
                    <ExpiryCountdown expiresAt={row.expiresAt} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {selectedId != null && warehouse != null && (
        <ProposalDetailPanel key={selectedId} proposalId={selectedId} warehouse={warehouse} isSupervisor={isSupervisor} />
      )}
    </div>
  )
}

function ProposalDetailPanel({
  proposalId,
  warehouse,
  isSupervisor,
}: {
  proposalId: number
  warehouse: string
  isSupervisor: boolean
}) {
  const queryClient = useQueryClient()
  const [outcomeMessage, setOutcomeMessage] = useState<string | null>(null)
  const [showRejectForm, setShowRejectForm] = useState(false)
  const [rejectNote, setRejectNote] = useState('')

  const detailQuery = useQuery({
    queryKey: ['proposal', proposalId],
    queryFn: () => apiGet<ProposalDetailResponse>(`/api/proposals/${proposalId}`),
  })

  function invalidate() {
    queryClient.invalidateQueries({ queryKey: ['proposals', warehouse] })
    queryClient.invalidateQueries({ queryKey: ['proposal', proposalId] })
  }

  const approveMutation = useMutation({
    mutationFn: () => apiPost<ApprovalOutcome>(`/api/proposals/${proposalId}/approve`),
    onSuccess: (outcome) => {
      setOutcomeMessage(describeApprovalOutcome(outcome))
      invalidate()
    },
  })

  const rejectMutation = useMutation({
    mutationFn: () => apiPost<void>(`/api/proposals/${proposalId}/reject`, { note: rejectNote }),
    onSuccess: () => {
      setShowRejectForm(false)
      setOutcomeMessage('제안을 거부했다')
      invalidate()
    },
  })

  function submitReject(e: FormEvent) {
    e.preventDefault()
    rejectMutation.mutate()
  }

  if (detailQuery.isLoading) {
    return <p className="state-message">상세를 불러오는 중…</p>
  }
  if (detailQuery.error) {
    return <ApiErrorMessage error={detailQuery.error} />
  }
  if (!detailQuery.data) {
    return null
  }

  const { proposal, basisReview } = detailQuery.data
  const allValid =
    basisReview.balance.every((r) => r.comparison.valid) && basisReview.warehouseSku.every((r) => r.comparison.valid)
  const isPending = proposal.status === 'PENDING'
  const isExpired = new Date(proposal.expiresAt).getTime() <= Date.now()

  return (
    <div className="detail-panel">
      <div className="detail-panel-header">
        <h2>
          제안 #{proposal.id} <span className="mono">{proposal.proposalType}</span>
        </h2>
        <span className={`badge badge-status-${proposal.status.toLowerCase()}`}>
          {STATUS_LABEL[proposal.status] ?? proposal.status}
        </span>
      </div>

      <dl className="kv-list">
        <div>
          <dt>제안자</dt>
          <dd className="mono">{proposal.proposedBy}</dd>
        </div>
        <div>
          <dt>생성</dt>
          <dd className="mono">{formatDateTime(proposal.createdAt)}</dd>
        </div>
        <div>
          <dt>만료까지</dt>
          <dd>
            <ExpiryCountdown expiresAt={proposal.expiresAt} />
          </dd>
        </div>
        {proposal.issueId != null && (
          <div>
            <dt>연결된 이슈</dt>
            <dd className="mono">#{proposal.issueId}</dd>
          </div>
        )}
        {proposal.decisionNote && (
          <div>
            <dt>결정 사유</dt>
            <dd>{proposal.decisionNote}</dd>
          </div>
        )}
      </dl>

      <AiNote label="제안 사유(rationale)" content={proposal.rationale} />

      {proposal.agentMeta && (
        <div className="detail-section">
          <h3>agent_meta</h3>
          <pre className="mono agent-meta-body">{prettyJson(proposal.agentMeta)}</pre>
        </div>
      )}

      <div className="detail-section">
        <h3>커맨드 엔트리</h3>
        <div className="table-panel">
          <table className="data-table">
            <thead>
              <tr>
                <th>로케이션</th>
                <th>SKU</th>
                <th>로트</th>
                <th className="num">수량</th>
              </tr>
            </thead>
            <tbody>
              {proposal.entries.map((entry, i) => (
                <tr key={i}>
                  <td className="mono">{entry.locationCode}</td>
                  <td className="mono">{entry.skuCode}</td>
                  <td className="mono">{entry.lotNo}</td>
                  <td className="num mono">{entry.qty > 0 ? `+${entry.qty}` : entry.qty}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <div className="detail-section">
        <h3>근거 대조</h3>
        <p className="basis-note">
          제안이 만들어질 때 관측한 값(기준값)과 지금 값(현재값)을 비교한다. 허용 범위를 벗어난 항목이
          있으면 승인해도 실행되지 않고 <strong>STALE</strong>로 자동 종료된다.
        </p>

        {!allValid && isPending && (
          <div className="callout callout-caution">
            근거 중 일부가 허용 오차를 벗어났다. 지금 승인해도 실행되지 않고 STALE로 자동 종료된다.
          </div>
        )}
        {isExpired && isPending && (
          <div className="callout callout-caution">이미 유효 기간이 지났다. 승인하면 EXPIRED로 종료된다.</div>
        )}

        {basisReview.balance.length === 0 && basisReview.warehouseSku.length === 0 ? (
          <p className="state-message">대조할 근거 관측값이 없다</p>
        ) : (
          <div className="table-panel">
            <table className="data-table basis-table">
              <thead>
                <tr>
                  <th>대상</th>
                  <th className="num">기준값</th>
                  <th className="num">현재값</th>
                  <th className="num">차이</th>
                  <th className="num">허용범위</th>
                  <th>유효</th>
                </tr>
              </thead>
              <tbody>
                {basisReview.balance.map((r) => (
                  <ComparisonRow key={`balance-${r.balanceId}`} label={`잔액 #${r.balanceId}`} comparison={r.comparison} />
                ))}
                {basisReview.warehouseSku.map((r) => (
                  <ComparisonRow
                    key={`wh-sku-${r.warehouseId}-${r.skuId}`}
                    label={`창고 ${r.warehouseId} · SKU ${r.skuId}`}
                    comparison={r.comparison}
                  />
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {outcomeMessage && <div className="callout callout-info">{outcomeMessage}</div>}
      {approveMutation.error && <ApiErrorMessage error={approveMutation.error} />}
      {rejectMutation.error && <ApiErrorMessage error={rejectMutation.error} />}

      {/* 승인·거부 버튼은 SUPERVISOR에게만 보인다 — 화면을 정리하는 용도일 뿐이다. 감춰도 서버
          (AccessGuard.requireAnyRole)가 SUPERVISOR가 아니면 어차피 403으로 막는다. */}
      {isSupervisor && isPending && (
        <div className="detail-actions">
          <button
            type="button"
            className="btn btn-primary"
            disabled={approveMutation.isPending}
            onClick={() => approveMutation.mutate()}
          >
            {approveMutation.isPending ? '승인 처리 중…' : '승인'}
          </button>
          <button type="button" className="btn btn-danger" onClick={() => setShowRejectForm((v) => !v)}>
            거부
          </button>
        </div>
      )}

      {isSupervisor && showRejectForm && (
        <form className="reject-form" onSubmit={submitReject}>
          <label className="field">
            <span className="field-label">거부 사유</span>
            <textarea
              value={rejectNote}
              onChange={(e) => setRejectNote(e.target.value)}
              required
              rows={3}
            />
          </label>
          <button type="submit" className="btn btn-danger" disabled={rejectMutation.isPending || !rejectNote.trim()}>
            {rejectMutation.isPending ? '거부 처리 중…' : '거부 확정'}
          </button>
        </form>
      )}
    </div>
  )
}

function ComparisonRow({ label, comparison }: { label: string; comparison: Comparison }) {
  return (
    <tr>
      <td className="mono">{label}</td>
      <td className="num mono">{comparison.observed}</td>
      <td className="num mono">{comparison.current}</td>
      <td className="num mono">{comparison.diff}</td>
      <td className="num mono">±{comparison.allowedDiff.toFixed(1)}</td>
      <td>{validBadge(comparison.valid)}</td>
    </tr>
  )
}

function prettyJson(text: string): string {
  try {
    return JSON.stringify(JSON.parse(text), null, 2)
  } catch {
    return text
  }
}
