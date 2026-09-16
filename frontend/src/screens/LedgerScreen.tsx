import { useEffect, useState, type FormEvent } from 'react'
import { useQuery } from '@tanstack/react-query'
import { apiGet } from '../api/client'
import { ApiErrorMessage, formatDateTime, useWarehouseGate } from './shared'

// LedgerQueryRepository.LedgerRow(:web)를 그대로 받는다 — v_ledger 뷰의 모든 컬럼이다.
interface LedgerRow {
  ledgerEntryId: number
  txnId: number
  txnType: string
  sourceType: string
  sourceRef: string
  reasonCode: string
  actorType: string
  actorId: string
  proposalId: number | null
  occurredAt: string
  locationCode: string
  isVirtual: boolean
  skuCode: string
  lotNo: string
  qtyDelta: number
  onHandAfter: number | null
}

interface Filters {
  sku: string
  location: string
  from: string
  to: string
}

const EMPTY_FILTERS: Filters = { sku: '', location: '', from: '', to: '' }

function buildLedgerUrl(warehouse: string, filters: Filters): string {
  const params = new URLSearchParams({ warehouse })
  if (filters.sku) params.set('sku', filters.sku)
  if (filters.location) params.set('location', filters.location)
  // LedgerController는 from·to를 Instant.parse로 읽는다(ISO-8601 순간, 예: 2026-09-01T00:00:00Z).
  // 날짜만 고르게 하고 여기서 하루의 시작·끝(UTC)으로 채운다.
  if (filters.from) params.set('from', `${filters.from}T00:00:00Z`)
  if (filters.to) params.set('to', `${filters.to}T23:59:59Z`)
  return `/api/ledger?${params.toString()}`
}

export function LedgerScreen() {
  const { me, gate } = useWarehouseGate('원장 조회')
  const [warehouse, setWarehouse] = useState<string | null>(null)
  const [draft, setDraft] = useState<Filters>(EMPTY_FILTERS)
  const [applied, setApplied] = useState<Filters>(EMPTY_FILTERS)

  useEffect(() => {
    if (!warehouse && me && me.warehouses.length > 0) {
      setWarehouse(me.warehouses[0])
    }
  }, [me, warehouse])

  const { data, error, isLoading } = useQuery({
    queryKey: ['ledger', warehouse, applied],
    queryFn: () => apiGet<LedgerRow[]>(buildLedgerUrl(warehouse ?? '', applied)),
    enabled: warehouse != null,
  })

  function handleSearch(e: FormEvent) {
    e.preventDefault()
    setApplied(draft)
  }

  // 서버는 ledger_entry_id 오름차순(오래된 순)으로 최대 500건을 준다(LedgerQueryRepository#find) —
  // 화면은 시간 역순으로 보여줘야 하므로 여기서 뒤집는다. 필터에 걸리는 행이 500건을 넘으면 서버가
  // 이미 가장 오래된 500건만 준 뒤라 최신 행이 빠질 수 있다 — :web을 고치지 않는 이번 조각에서는
  // 손댈 수 없는 한계다.
  const rows = data ? [...data].reverse() : null

  // 다른 여섯 화면과 같은 게이트. 읽기 전용이라고 예외가 되지 않는다 — 조회 쿼리도
  // enabled: warehouse != null이라, /api/me가 실패하면 여기도 영원한 빈 화면이 된다.
  if (me === null) {
    return gate
  }

  return (
    <div className="screen">
      <header className="screen-header">
        <h1>원장 조회</h1>
      </header>

      <form className="stock-filters" onSubmit={handleSearch}>
        <label className="field field-inline">
          <span className="field-label">창고</span>
          <select value={warehouse ?? ''} onChange={(e) => setWarehouse(e.target.value)}>
            {(me?.warehouses ?? []).map((code) => (
              <option key={code} value={code}>
                {code}
              </option>
            ))}
          </select>
        </label>
        <label className="field field-inline">
          <span className="field-label">SKU</span>
          <input
            type="text"
            value={draft.sku}
            onChange={(e) => setDraft((f) => ({ ...f, sku: e.target.value }))}
            placeholder="비우면 전체"
          />
        </label>
        <label className="field field-inline">
          <span className="field-label">로케이션</span>
          <input
            type="text"
            value={draft.location}
            onChange={(e) => setDraft((f) => ({ ...f, location: e.target.value }))}
            placeholder="비우면 전체"
          />
        </label>
        <label className="field field-inline">
          <span className="field-label">시작일</span>
          <input type="date" value={draft.from} onChange={(e) => setDraft((f) => ({ ...f, from: e.target.value }))} />
        </label>
        <label className="field field-inline">
          <span className="field-label">종료일</span>
          <input type="date" value={draft.to} onChange={(e) => setDraft((f) => ({ ...f, to: e.target.value }))} />
        </label>
        <button type="submit" className="btn btn-secondary">
          조회
        </button>
      </form>

      {isLoading && <p className="state-message">불러오는 중…</p>}
      {error && <ApiErrorMessage error={error} />}

      {rows && !error && (
        <div className="table-panel">
          <table className="data-table">
            <thead>
              <tr>
                <th>줄 ID</th>
                <th>거래</th>
                <th>유형</th>
                <th>위치</th>
                <th>SKU</th>
                <th>로트</th>
                <th className="num">증감</th>
                <th className="num">처리후 보유</th>
                <th>행위자</th>
                <th>시각</th>
              </tr>
            </thead>
            <tbody>
              {rows.length === 0 && (
                <tr>
                  <td colSpan={10} className="state-message">
                    원장 이력이 없다
                  </td>
                </tr>
              )}
              {rows.map((row) => (
                <tr key={row.ledgerEntryId}>
                  <td className="mono">{row.ledgerEntryId}</td>
                  <td className="mono">#{row.txnId}</td>
                  <td className="mono">{row.txnType}</td>
                  <td>
                    <span className="badge-row">
                      <span className="mono">{row.locationCode}</span>
                      {/* 가상 로케이션 줄 — 복식부기의 반대쪽이다(물리 줄과 짝을 이뤄 합이 0이 된다). */}
                      {row.isVirtual && <span className="badge badge-virtual">가상</span>}
                    </span>
                  </td>
                  <td className="mono">{row.skuCode}</td>
                  <td className="mono">{row.lotNo}</td>
                  <td
                    className={`num mono${row.qtyDelta < 0 ? ' qty-negative' : row.qtyDelta > 0 ? ' qty-positive' : ''}`}
                  >
                    {row.qtyDelta > 0 ? `+${row.qtyDelta}` : row.qtyDelta}
                  </td>
                  <td className="num mono">{row.onHandAfter ?? '-'}</td>
                  <td>
                    {row.actorType === 'USER' ? <span className="mono">{row.actorId}</span> : <span>시스템</span>}
                    {/* AI 제안이 사람 승인으로 실행된 거래라는 걸 원장에서 추적할 수 있어야 한다. */}
                    {row.proposalId != null && (
                      <div className="ledger-proposal-note">제안 #{row.proposalId}에서 실행됨</div>
                    )}
                  </td>
                  <td className="mono">{formatDateTime(row.occurredAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
