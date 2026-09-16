import { useEffect, useState, type FormEvent } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { apiGet, apiPost } from '../api/client'
import { ApiErrorMessage, useWarehouseGate } from './shared'

interface StartResponse {
  sessionId: number
}

// StockRepository.AvailableStockRow(:web)의 부분집합 — 실사는 로케이션당 전산 수량(보유)만 있으면 된다.
interface StockPickRow {
  skuCode: string
  lotNo: string
  locationCode: string
  onHandQty: number
}

// CountSubmitOutcome(코어의 sealed interface Confirmed|ReviewRequired)을 그대로 받는다. Confirmed는
// {resolutionTxnId}(차이가 없었으면 null)를 갖고, ReviewRequired는 컴포넌트가 없는 빈 객체({})다 — 그래서
// resolutionTxnId 키가 있는지로 구분한다(Confirmed는 값이 null이어도 키 자체는 항상 내려온다).
interface SubmitOutcome {
  resolutionTxnId?: number | null
}

function isConfirmed(outcome: SubmitOutcome): boolean {
  return 'resolutionTxnId' in outcome
}

interface ResolveResponse {
  resolutionTxnId: number | null
}

interface CountLine {
  key: string
  skuCode: string
  lotNo: string
  systemQty: number
  countedQty: string
}

type SessionPhase = 'open' | 'review' | 'confirmed' | 'abandoned'

interface Session {
  id: number
  warehouseCode: string
  locationCode: string
}

export function CountScreen() {
  const { me, gate } = useWarehouseGate('실사')
  const canWrite = me?.roles.some((r) => r === 'OPERATOR' || r === 'SUPERVISOR') ?? false
  const isSupervisor = me?.roles.includes('SUPERVISOR') ?? false

  if (me === null) {
    return gate
  }

  if (!canWrite) {
    return (
      <div className="screen">
        <header className="screen-header">
          <h1>실사</h1>
        </header>
        {/* VIEWER에게는 폼 자체를 감춘다 — 서버(AccessGuard.requireAnyRole)가 어차피 다시 막는다. */}
        <p className="state-message state-error">권한 없음 — 실사 세션 시작은 OPERATOR 이상만 할 수 있다</p>
      </div>
    )
  }

  return <CountWorkspace warehouses={me.warehouses} isSupervisor={isSupervisor} />
}

function CountWorkspace({ warehouses, isSupervisor }: { warehouses: string[]; isSupervisor: boolean }) {
  const [session, setSession] = useState<Session | null>(null)

  return (
    <div className="screen">
      <header className="screen-header">
        <h1>실사</h1>
      </header>

      {!session && <StartForm warehouses={warehouses} onStarted={setSession} />}

      {session && (
        <CountSession key={session.id} session={session} isSupervisor={isSupervisor} onReset={() => setSession(null)} />
      )}
    </div>
  )
}

function StartForm({ warehouses, onStarted }: { warehouses: string[]; onStarted: (session: Session) => void }) {
  const [warehouseCode, setWarehouseCode] = useState(warehouses[0] ?? '')
  const [locationCode, setLocationCode] = useState('')

  const mutation = useMutation({
    mutationFn: () => apiPost<StartResponse>('/api/counts', { warehouseCode, locationCode }),
    onSuccess: (result) => onStarted({ id: result.sessionId, warehouseCode, locationCode }),
  })

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    mutation.mutate()
  }

  const canSubmit = locationCode.trim() !== ''

  return (
    <form className="receipt-form" onSubmit={handleSubmit}>
      <div className="receipt-form-grid">
        <label className="field">
          <span className="field-label">창고</span>
          <select value={warehouseCode} onChange={(e) => setWarehouseCode(e.target.value)}>
            {warehouses.map((code) => (
              <option key={code} value={code}>
                {code}
              </option>
            ))}
          </select>
        </label>
        <label className="field">
          <span className="field-label">로케이션</span>
          <input
            type="text"
            className="mono"
            value={locationCode}
            onChange={(e) => setLocationCode(e.target.value)}
            placeholder="예: A-01-01-1"
            required
          />
        </label>
      </div>

      <p className="basis-note">
        세션을 시작하면 이 로케이션이 잠긴다 — 세션이 끝날 때까지(확정 또는 포기) 이 로케이션의
        입고·출고·이동이 전부 거절된다.
      </p>

      {mutation.error && <ApiErrorMessage error={mutation.error} />}

      <div className="detail-actions">
        <button type="submit" className="btn btn-primary" disabled={!canSubmit || mutation.isPending}>
          {mutation.isPending ? '시작 중…' : '실사 세션 시작'}
        </button>
      </div>
    </form>
  )
}

function CountSession({
  session,
  isSupervisor,
  onReset,
}: {
  session: Session
  isSupervisor: boolean
  onReset: () => void
}) {
  const [phase, setPhase] = useState<SessionPhase>('open')
  const [lines, setLines] = useState<CountLine[] | null>(null)
  const [outcome, setOutcome] = useState<SubmitOutcome | null>(null)
  const [resolveResult, setResolveResult] = useState<ResolveResponse | null>(null)
  const [newSku, setNewSku] = useState('')
  const [newLot, setNewLot] = useState('')

  // 키에 세션 id가 들어간다. 창고 코드만 쓰던 동안에는 같은 창고에서 새 실사를 시작할 때 react-query가
  // 앞 세션의 응답을 캐시에서 즉시 돌려줬고(기본 gcTime 5분), 아래 useEffect가 그 낡은 수량으로 입력 줄을
  // 만들어 버렸다 — 뒤늦게 도착하는 새 응답은 lines가 이미 채워져 있어 반영되지 않는다. 화면에는 차이가
  // 0으로 그려지니 "숫자가 맞다"고 보고 그대로 제출하게 되는데, 서버는 자기 스냅샷(system_qty)과 대조하므로
  // 차이가 생기고 오차 안이면 같은 트랜잭션에서 조정 거래까지 나간다. 즉 거짓 실사가 원장에 남는다.
  //
  // 세션 id는 DB의 IDENTITY라 재사용되지 않으므로, 새 세션은 반드시 캐시가 비어 있는 키가 된다 —
  // staleTime·gcTime을 건드릴 필요가 없다(있는 데이터를 어떻게 다룰지가 아니라, 애초에 없는 키를 쓴다).
  const stockQuery = useQuery({
    queryKey: ['count-stock', session.id, session.warehouseCode],
    queryFn: () => apiGet<StockPickRow[]>(`/api/stock?warehouse=${encodeURIComponent(session.warehouseCode)}`),
  })

  // 재고 조회가 끝나면 이 로케이션의 행만 걸러 실사 입력 줄을 만든다 — 딱 한 번만(사용자가 입력을 시작한
  // 뒤에는 조회가 다시 돌아도 입력값을 덮어쓰지 않는다). GET /api/stock에는 location 필터가 없어(sku만
  // 있다) 창고 전체를 받아 이 화면에서 로케이션으로 거른다.
  useEffect(() => {
    if (lines === null && stockQuery.data) {
      const rows = stockQuery.data.filter((r) => r.locationCode === session.locationCode)
      setLines(
        rows.map((r) => ({
          key: `${r.skuCode}::${r.lotNo}`,
          skuCode: r.skuCode,
          lotNo: r.lotNo,
          systemQty: r.onHandQty,
          countedQty: String(r.onHandQty),
        })),
      )
    }
  }, [stockQuery.data, lines, session.locationCode])

  const submitMutation = useMutation({
    mutationFn: () =>
      apiPost<SubmitOutcome>(`/api/counts/${session.id}/submit`, {
        lines: (lines ?? []).map((l) => ({ skuCode: l.skuCode, lotNo: l.lotNo, countedQty: Number(l.countedQty) || 0 })),
      }),
    onSuccess: (result) => {
      setOutcome(result)
      setPhase(isConfirmed(result) ? 'confirmed' : 'review')
    },
  })

  const resolveMutation = useMutation({
    mutationFn: () => apiPost<ResolveResponse>(`/api/counts/${session.id}/resolve`),
    onSuccess: (result) => {
      setResolveResult(result)
      setPhase('confirmed')
    },
  })

  const abandonMutation = useMutation({
    mutationFn: () => apiPost<void>(`/api/counts/${session.id}/abandon`),
    onSuccess: () => setPhase('abandoned'),
  })

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    submitMutation.mutate()
  }

  function updateCounted(key: string, value: string) {
    setLines((prev) => (prev ? prev.map((l) => (l.key === key ? { ...l, countedQty: value } : l)) : prev))
  }

  function addLine() {
    if (!newSku.trim() || !newLot.trim()) {
      return
    }
    setLines((prev) => [
      ...(prev ?? []),
      { key: `new:${newSku}::${newLot}::${Date.now()}`, skuCode: newSku.trim(), lotNo: newLot.trim(), systemQty: 0, countedQty: '' },
    ])
    setNewSku('')
    setNewLot('')
  }

  const locked = phase === 'open' || phase === 'review'
  const submitted = phase !== 'open'

  return (
    <div>
      {/* 잠금 알림 — 이 화면의 핵심. 세션이 끝날 때까지(확정 또는 포기) 이 로케이션은 다른 거래를 전부 거절한다
          (CountSessionService#start의 markLocationCounting / PostingService#post의 requireNoActiveCountSession). */}
      <div className={`callout ${locked ? 'callout-caution' : 'callout-info'}`}>
        {locked ? (
          <>
            로케이션 <strong className="mono">{session.locationCode}</strong>이 잠겼다 — 세션이 끝날 때까지 이
            로케이션의 입고·출고·이동은 거절된다.
          </>
        ) : (
          <>
            로케이션 <strong className="mono">{session.locationCode}</strong> 잠금이 풀렸다.
          </>
        )}
      </div>

      <dl className="kv-list">
        <div>
          <dt>세션</dt>
          <dd className="mono">#{session.id}</dd>
        </div>
        <div>
          <dt>창고</dt>
          <dd className="mono">{session.warehouseCode}</dd>
        </div>
        <div>
          <dt>로케이션</dt>
          <dd className="mono">{session.locationCode}</dd>
        </div>
      </dl>

      {stockQuery.isLoading && <p className="state-message">전산 수량을 불러오는 중…</p>}
      {stockQuery.error && <ApiErrorMessage error={stockQuery.error} />}

      {lines && (
        <form className="receipt-form" onSubmit={handleSubmit}>
          <div className="table-panel">
            <table className="data-table">
              <thead>
                <tr>
                  <th>SKU</th>
                  <th>로트</th>
                  <th className="num">전산 수량</th>
                  <th className="num">실사 수량</th>
                  <th className="num">차이</th>
                </tr>
              </thead>
              <tbody>
                {lines.length === 0 && (
                  <tr>
                    <td colSpan={5} className="state-message">
                      이 로케이션에 전산 재고가 없다 — 실제로 있는 게 없으면 그대로 제출하고, 있으면 아래에서
                      줄을 추가하라
                    </td>
                  </tr>
                )}
                {lines.map((l) => {
                  const counted = Number(l.countedQty)
                  const hasValue = l.countedQty.trim() !== '' && !Number.isNaN(counted)
                  const diff = hasValue ? counted - l.systemQty : null
                  return (
                    <tr key={l.key}>
                      <td className="mono">{l.skuCode}</td>
                      <td className="mono">{l.lotNo}</td>
                      <td className="num mono">{l.systemQty}</td>
                      <td className="num">
                        <input
                          type="number"
                          min={0}
                          className="mono line-qty-input"
                          value={l.countedQty}
                          onChange={(e) => updateCounted(l.key, e.target.value)}
                          disabled={submitted}
                          required
                        />
                      </td>
                      <td className={`num mono${diff && diff < 0 ? ' qty-negative' : diff && diff > 0 ? ' qty-positive' : ''}`}>
                        {diff === null ? '-' : diff > 0 ? `+${diff}` : diff}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>

          {!submitted && (
            <div className="receipt-form-grid">
              <label className="field">
                <span className="field-label">추가 SKU(전산에 없는 실물)</span>
                <input type="text" className="mono" value={newSku} onChange={(e) => setNewSku(e.target.value)} />
              </label>
              <label className="field">
                <span className="field-label">추가 로트</span>
                <input type="text" className="mono" value={newLot} onChange={(e) => setNewLot(e.target.value)} />
              </label>
              <div className="detail-actions">
                <button type="button" className="btn btn-secondary" onClick={addLine}>
                  전산에 없는 줄 추가
                </button>
              </div>
            </div>
          )}

          <p className="basis-note">
            허용 오차(수량 오차와 비율 오차를 둘 다 만족해야 확정된다)는 서버 설정값이라 이 화면에는 정확한
            수치가 없다 — 오차를 넘는 줄이 하나라도 있으면 확정되지 않고 검토(REVIEW)로 넘어간다. 실제
            판정은 제출 후 아래 결과로 알 수 있다.
          </p>

          {submitMutation.error && <ApiErrorMessage error={submitMutation.error} />}

          {!submitted && (
            <div className="detail-actions">
              <button type="submit" className="btn btn-primary" disabled={submitMutation.isPending}>
                {submitMutation.isPending ? '제출 중…' : '실사 제출'}
              </button>
              <button
                type="button"
                className="btn btn-danger"
                disabled={abandonMutation.isPending}
                onClick={() => abandonMutation.mutate()}
              >
                {abandonMutation.isPending ? '포기 처리 중…' : '실사 포기'}
              </button>
            </div>
          )}
        </form>
      )}

      {phase === 'review' && (
        <div className="detail-section">
          <div className="callout callout-caution">
            허용 오차를 넘는 차이가 있어 자동으로 확정되지 않았다(REVIEW) — SUPERVISOR의 정정 승인이 필요하다.
          </div>
          {resolveMutation.error && <ApiErrorMessage error={resolveMutation.error} />}
          {abandonMutation.error && <ApiErrorMessage error={abandonMutation.error} />}
          <div className="detail-actions">
            {isSupervisor ? (
              <button
                type="button"
                className="btn btn-primary"
                disabled={resolveMutation.isPending}
                onClick={() => resolveMutation.mutate()}
              >
                {resolveMutation.isPending ? '정정 승인 중…' : '정정 승인'}
              </button>
            ) : (
              <p className="state-message">정정 승인은 SUPERVISOR만 할 수 있다.</p>
            )}
            <button
              type="button"
              className="btn btn-danger"
              disabled={abandonMutation.isPending}
              onClick={() => abandonMutation.mutate()}
            >
              {abandonMutation.isPending ? '포기 처리 중…' : '실사 포기'}
            </button>
          </div>
        </div>
      )}

      {phase === 'confirmed' && (
        <div className="callout callout-info">
          실사가 확정됐다
          {resolveResult && resolveResult.resolutionTxnId != null && ` — 정정 거래 #${resolveResult.resolutionTxnId}`}
          {!resolveResult &&
            outcome &&
            (outcome.resolutionTxnId != null ? ` — 정정 거래 #${outcome.resolutionTxnId}` : ' — 차이가 없었다')}
          .
        </div>
      )}

      {phase === 'abandoned' && <div className="callout callout-caution">실사를 포기했다 — 로케이션 잠금이 풀렸다.</div>}

      {(phase === 'confirmed' || phase === 'abandoned') && (
        <div className="detail-actions">
          <button type="button" className="btn btn-secondary" onClick={onReset}>
            새 실사 시작
          </button>
        </div>
      )}
    </div>
  )
}
