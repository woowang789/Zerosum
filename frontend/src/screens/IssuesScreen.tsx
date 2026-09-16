import { useEffect, useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiGet, apiPost } from '../api/client'
import { AiNote, ApiErrorMessage, formatDateTime, useWarehouseGate } from './shared'

interface OpenIssueRow {
  issueId: number
  issueType: string
  severity: string
  status: string
  locationId: number | null
  locationCode: string | null
  warehouseCode: string
  skuId: number | null
  skuCode: string | null
  lotId: number | null
  lotNo: string | null
  countSessionId: number | null
  detail: string | null
  aiAnalysis: string | null
  detectedAt: string
  ackedAt: string | null
}

interface LedgerRow {
  ledgerEntryId: number
  txnId: number
  txnType: string
  reasonCode: string
  actorType: string
  actorId: string
  proposalId: number | null
  occurredAt: string
  locationCode: string
  skuCode: string
  lotNo: string
  qtyDelta: number
  onHandAfter: number | null
}

interface CountHistoryRow {
  countSessionId: number
  sessionStatus: string
  locationCode: string
  skuCode: string
  lotNo: string
  systemQty: number
  countedQty: number
  diffQty: number
  countedBy: string
  countedAt: string
}

// 상세 응답의 issue는 목록 행(OpenIssueRow)에 종결 감사 정보를 더한 모양이다 — 상세는 상태와 무관하게
// inventory_issue를 직접 읽으므로(IssueRepository#findById) 목록에는 없는 필드가 나온다.
interface IssueDetailRow extends OpenIssueRow {
  ackedBy: string | null
  resolvedBy: string | null
  resolvedAt: string | null
  resolutionNote: string | null
  resolvedTxnId: number | null
}

interface IssueDetailResponse {
  issue: IssueDetailRow
  ledger: LedgerRow[]
  countHistory: CountHistoryRow[]
}

const SEVERITY_LABEL: Record<string, string> = {
  CRITICAL: '심각',
  HIGH: '높음',
  MEDIUM: '보통',
  LOW: '낮음',
}

const STATUS_LABEL: Record<string, string> = {
  OPEN: '열림',
  ACKED: '인지됨',
  RESOLVED: '종결됨',
}

// inventory_issue.detail의 키(ReconciliationService가 채운다) → 화면 라벨. 모르는 키는 그대로 보여준다.
const DETAIL_KEY_LABEL: Record<string, string> = {
  onHandQty: '보유수량',
  ledgerQty: '원장 합계',
  balanceId: '잔액 ID',
  allocatedQty: '할당수량',
  activeQty: '활성 할당수량',
  ledgerEntryId: '원장 줄 ID',
  txnId: '거래 ID',
  onHandAfter: '처리 후 보유수량',
  expected: '기대값',
  flaggedSessionId: '표시된 실사 세션 ID',
  activeSessionId: '활성 실사 세션 ID',
}

export function IssuesScreen() {
  const { me, gate } = useWarehouseGate('정합 이슈')
  const isOperatorOrAbove = me?.roles.some((r) => r === 'OPERATOR' || r === 'SUPERVISOR') ?? false
  const isSupervisor = me?.roles.includes('SUPERVISOR') ?? false
  const [warehouse, setWarehouse] = useState<string | null>(null)
  const [selectedId, setSelectedId] = useState<number | null>(null)

  useEffect(() => {
    if (!warehouse && me && me.warehouses.length > 0) {
      setWarehouse(me.warehouses[0])
    }
  }, [me, warehouse])

  const listQuery = useQuery({
    queryKey: ['issues', warehouse],
    queryFn: () => apiGet<OpenIssueRow[]>(`/api/issues?warehouse=${encodeURIComponent(warehouse ?? '')}`),
    enabled: warehouse != null,
  })

  if (me === null) {
    return gate
  }

  return (
    <div className="screen">
      <header className="screen-header">
        <h1>정합 이슈</h1>
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
                <th>심각도</th>
                <th>상태</th>
                <th>위치</th>
                <th>SKU / 로트</th>
                <th>감지</th>
              </tr>
            </thead>
            <tbody>
              {listQuery.data.length === 0 && (
                <tr>
                  <td colSpan={7} className="state-message">
                    열려 있는 이슈가 없다
                  </td>
                </tr>
              )}
              {listQuery.data.map((row) => (
                <tr
                  key={row.issueId}
                  className={`row-clickable${row.issueId === selectedId ? ' is-selected' : ''}`}
                  onClick={() => setSelectedId(row.issueId)}
                >
                  <td className="mono">{row.issueId}</td>
                  <td className="mono">{row.issueType}</td>
                  <td>
                    <span className={`badge badge-severity-${row.severity.toLowerCase()}`}>
                      {SEVERITY_LABEL[row.severity] ?? row.severity}
                    </span>
                  </td>
                  <td>
                    <span className={`badge badge-status-${row.status.toLowerCase()}`}>
                      {STATUS_LABEL[row.status] ?? row.status}
                    </span>
                  </td>
                  <td className="mono">{row.locationCode ?? '-'}</td>
                  <td className="mono">
                    {row.skuCode ?? '-'} {row.lotNo ? `/ ${row.lotNo}` : ''}
                  </td>
                  <td className="mono">{formatDateTime(row.detectedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {selectedId != null && warehouse != null && (
        <IssueDetailPanel
          key={selectedId}
          issueId={selectedId}
          warehouse={warehouse}
          isOperatorOrAbove={isOperatorOrAbove}
          isSupervisor={isSupervisor}
        />
      )}
    </div>
  )
}

function IssueDetailPanel({
  issueId,
  warehouse,
  isOperatorOrAbove,
  isSupervisor,
}: {
  issueId: number
  warehouse: string
  isOperatorOrAbove: boolean
  isSupervisor: boolean
}) {
  const queryClient = useQueryClient()
  const [ackMessage, setAckMessage] = useState<string | null>(null)
  const [showResolveForm, setShowResolveForm] = useState(false)
  const [resolveNote, setResolveNote] = useState('')
  const [resolvedTxnId, setResolvedTxnId] = useState('')

  const detailQuery = useQuery({
    queryKey: ['issue', issueId],
    queryFn: () => apiGet<IssueDetailResponse>(`/api/issues/${issueId}`),
  })

  const ackMutation = useMutation({
    mutationFn: () => apiPost<void>(`/api/issues/${issueId}/ack`),
    onSuccess: () => {
      setAckMessage('이슈를 인지했다')
      queryClient.invalidateQueries({ queryKey: ['issues', warehouse] })
      queryClient.invalidateQueries({ queryKey: ['issue', issueId] })
    },
  })

  const resolveMutation = useMutation({
    mutationFn: () =>
      apiPost<void>(`/api/issues/${issueId}/resolve`, {
        note: resolveNote,
        resolvedTxnId: resolvedTxnId.trim() ? Number(resolvedTxnId) : null,
      }),
    onSuccess: () => {
      setShowResolveForm(false)
      // 목록은 v_open_issue 전체를 다시 읽으므로 종결된 이슈가 정상적으로 빠지고, 상세는 inventory_issue를
      // 직접 읽으므로(IssueRepository#findById) 종결 후에도 200으로 다시 불러와 종결 정보를 보여준다.
      queryClient.invalidateQueries({ queryKey: ['issues', warehouse] })
      queryClient.invalidateQueries({ queryKey: ['issue', issueId] })
    },
  })

  function submitResolve(e: FormEvent) {
    e.preventDefault()
    resolveMutation.mutate()
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

  const { issue, ledger, countHistory } = detailQuery.data
  const detailObj = parseJsonObject(issue.detail)
  const highlightLedgerEntryId = detailObj && typeof detailObj.ledgerEntryId === 'number' ? detailObj.ledgerEntryId : null
  const isChainBreak = issue.issueType === 'CHAIN_BREAK'

  return (
    <div className="detail-panel">
      <div className="detail-panel-header">
        <h2>
          이슈 #{issue.issueId} <span className="mono">{issue.issueType}</span>
        </h2>
        <span className={`badge badge-status-${issue.status.toLowerCase()}`}>
          {STATUS_LABEL[issue.status] ?? issue.status}
        </span>
      </div>

      <dl className="kv-list">
        <div>
          <dt>심각도</dt>
          <dd>
            <span className={`badge badge-severity-${issue.severity.toLowerCase()}`}>
              {SEVERITY_LABEL[issue.severity] ?? issue.severity}
            </span>
          </dd>
        </div>
        <div>
          <dt>위치</dt>
          <dd className="mono">{issue.locationCode ?? '-'}</dd>
        </div>
        <div>
          <dt>SKU / 로트</dt>
          <dd className="mono">
            {issue.skuCode ?? '-'} {issue.lotNo ? `/ ${issue.lotNo}` : ''}
          </dd>
        </div>
        <div>
          <dt>감지</dt>
          <dd className="mono">{formatDateTime(issue.detectedAt)}</dd>
        </div>
        {issue.ackedAt && (
          <div>
            <dt>인지</dt>
            <dd className="mono">
              {formatDateTime(issue.ackedAt)}
              {issue.ackedBy ? ` (${issue.ackedBy})` : ''}
            </dd>
          </div>
        )}
      </dl>

      {issue.status === 'RESOLVED' && (
        <div className="detail-section">
          <h3>종결 정보</h3>
          <dl className="kv-list">
            <div>
              <dt>종결자</dt>
              <dd className="mono">{issue.resolvedBy ?? '-'}</dd>
            </div>
            <div>
              <dt>종결 시각</dt>
              <dd className="mono">{issue.resolvedAt ? formatDateTime(issue.resolvedAt) : '-'}</dd>
            </div>
            <div>
              <dt>정정 거래</dt>
              <dd className="mono">{issue.resolvedTxnId ? `#${issue.resolvedTxnId}` : '거래 없음(사유만으로 종결)'}</dd>
            </div>
            <div>
              <dt>종결 사유</dt>
              <dd>{issue.resolutionNote ?? '-'}</dd>
            </div>
          </dl>
        </div>
      )}

      {detailObj && (
        <div className="detail-section">
          <h3>세부 내용</h3>
          <dl className="kv-list">
            {Object.entries(detailObj).map(([key, value]) => (
              <div key={key}>
                <dt>{DETAIL_KEY_LABEL[key] ?? key}</dt>
                <dd className="mono">{String(value)}</dd>
              </div>
            ))}
          </dl>
        </div>
      )}

      {issue.aiAnalysis && <AiNote label="AI 원인 분석" content={issue.aiAnalysis} />}

      {isChainBreak && (
        <div className="callout callout-info">
          CHAIN_BREAK은 이미 기록된 과거 원장을 고칠 수 없다(app_rw에 UPDATE 권한이 없다). 거래 없이
          사유만 남겨 종결하는 것이 정상이다.
        </div>
      )}

      <div className="detail-section">
        <h3>원장 이력</h3>
        <div className="table-panel">
          <table className="data-table">
            <thead>
              <tr>
                <th>줄 ID</th>
                <th>거래</th>
                <th>유형</th>
                <th>사유코드</th>
                <th>위치</th>
                <th>SKU</th>
                <th>로트</th>
                <th className="num">증감</th>
                <th className="num">처리후 보유</th>
                <th>시각</th>
              </tr>
            </thead>
            <tbody>
              {ledger.length === 0 && (
                <tr>
                  <td colSpan={10} className="state-message">
                    원장 이력이 없다
                  </td>
                </tr>
              )}
              {ledger.map((row) => (
                <tr
                  key={row.ledgerEntryId}
                  className={row.ledgerEntryId === highlightLedgerEntryId ? 'ledger-row-highlight' : undefined}
                >
                  <td className="mono">{row.ledgerEntryId}</td>
                  <td className="mono">#{row.txnId}</td>
                  <td className="mono">{row.txnType}</td>
                  <td className="mono">{row.reasonCode}</td>
                  <td className="mono">{row.locationCode}</td>
                  <td className="mono">{row.skuCode}</td>
                  <td className="mono">{row.lotNo}</td>
                  <td className="num mono">{row.qtyDelta > 0 ? `+${row.qtyDelta}` : row.qtyDelta}</td>
                  <td className="num mono">{row.onHandAfter ?? '-'}</td>
                  <td className="mono">{formatDateTime(row.occurredAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {highlightLedgerEntryId != null && (
          <p className="basis-note">강조된 줄이 이슈가 가리키는 지점이다(최초 불일치 지점).</p>
        )}
      </div>

      <div className="detail-section">
        <h3>실사 이력</h3>
        <div className="table-panel">
          <table className="data-table">
            <thead>
              <tr>
                <th>세션</th>
                <th>상태</th>
                <th>위치</th>
                <th>SKU</th>
                <th>로트</th>
                <th className="num">시스템수량</th>
                <th className="num">실사수량</th>
                <th className="num">차이</th>
                <th>실사자</th>
                <th>시각</th>
              </tr>
            </thead>
            <tbody>
              {countHistory.length === 0 && (
                <tr>
                  <td colSpan={10} className="state-message">
                    실사 이력이 없다
                  </td>
                </tr>
              )}
              {countHistory.map((row, i) => (
                <tr key={i}>
                  <td className="mono">#{row.countSessionId}</td>
                  <td className="mono">{row.sessionStatus}</td>
                  <td className="mono">{row.locationCode}</td>
                  <td className="mono">{row.skuCode}</td>
                  <td className="mono">{row.lotNo}</td>
                  <td className="num mono">{row.systemQty}</td>
                  <td className="num mono">{row.countedQty}</td>
                  <td className="num mono">{row.diffQty > 0 ? `+${row.diffQty}` : row.diffQty}</td>
                  <td className="mono">{row.countedBy}</td>
                  <td className="mono">{formatDateTime(row.countedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {ackMessage && <div className="callout callout-info">{ackMessage}</div>}
      {ackMutation.error && <ApiErrorMessage error={ackMutation.error} />}
      {resolveMutation.error && <ApiErrorMessage error={resolveMutation.error} />}

      {/* 인지는 OPERATOR 이상, 종결은 SUPERVISOR만 — 화면을 정리하는 용도일 뿐이다. 감춰도 서버
          (AccessGuard.requireAnyRole)가 다시 막는다. */}
      <div className="detail-actions">
        {isOperatorOrAbove && issue.status === 'OPEN' && (
          <button
            type="button"
            className="btn btn-secondary"
            disabled={ackMutation.isPending}
            onClick={() => ackMutation.mutate()}
          >
            {ackMutation.isPending ? '인지 처리 중…' : '인지'}
          </button>
        )}
        {isSupervisor && issue.status !== 'RESOLVED' && (
          <button type="button" className="btn btn-primary" onClick={() => setShowResolveForm((v) => !v)}>
            종결
          </button>
        )}
      </div>

      {isSupervisor && showResolveForm && (
        <form className="reject-form" onSubmit={submitResolve}>
          <label className="field">
            <span className="field-label">종결 사유</span>
            <textarea value={resolveNote} onChange={(e) => setResolveNote(e.target.value)} required rows={3} />
          </label>
          <label className="field">
            <span className="field-label">정정 거래 ID (선택 — 원장을 고치는 거래로 종결한 경우만)</span>
            <input
              type="number"
              className="mono"
              value={resolvedTxnId}
              onChange={(e) => setResolvedTxnId(e.target.value)}
              placeholder={isChainBreak ? '비워두면 사유만으로 종결된다' : ''}
            />
          </label>
          <button type="submit" className="btn btn-primary" disabled={resolveMutation.isPending || !resolveNote.trim()}>
            {resolveMutation.isPending ? '종결 처리 중…' : '종결 확정'}
          </button>
        </form>
      )}
    </div>
  )
}

function parseJsonObject(text: string | null): Record<string, unknown> | null {
  if (!text) {
    return null
  }
  try {
    const parsed = JSON.parse(text) as unknown
    return parsed && typeof parsed === 'object' ? (parsed as Record<string, unknown>) : null
  } catch {
    return null
  }
}
