import { useState, type FormEvent } from 'react'
import { useMutation } from '@tanstack/react-query'
import { apiDelete, apiPost } from '../api/client'
import { useMe } from '../hooks/useMe'
import { ApiErrorMessage } from './shared'

// AllocationController.AllocationLine(:web) — 이 할당이 예약한 잔액 행. FEFO가 고른 순서 그대로 온다.
interface AllocationLineResponse {
  allocationId: number
  locationCode: string
  skuCode: string
  lotNo: string
  qty: number
}

interface AllocateResponse {
  allocationIds: number[]
  lines: AllocationLineResponse[]
}

interface PostingResultResponse {
  txnId: number
}

interface AllocationRecord {
  key: string
  orderLineRef: string
  warehouseCode: string
  skuCode: string
  qty: number
  allocationIds: number[]
  // 서버(FEFO)가 이 할당에서 예약한 물리 줄 — 출고 확정 때 그대로 쓴다(추측 금지, ORPHAN_CONSUME 방지).
  lines: ShipmentLine[]
  status: 'active' | 'released' | 'consumed'
}

interface ShipmentLine {
  key: string
  locationCode: string
  skuCode: string
  lotNo: string
  qty: string
}

const ALLOC_STATUS_LABEL: Record<AllocationRecord['status'], string> = {
  active: '활성',
  released: '해제됨',
  consumed: '출고로 소진됨',
}

const V_CUSTOMER = 'V-CUSTOMER'

export function ShipmentScreen() {
  const { data: me } = useMe()
  const canWrite = me?.roles.some((r) => r === 'OPERATOR' || r === 'SUPERVISOR') ?? false

  if (!me) {
    return (
      <div className="screen">
        <header className="screen-header">
          <h1>출고</h1>
        </header>
        <p className="state-message">불러오는 중…</p>
      </div>
    )
  }

  if (!canWrite) {
    return (
      <div className="screen">
        <header className="screen-header">
          <h1>출고</h1>
        </header>
        {/* VIEWER에게는 폼 자체를 감춘다 — 서버(AccessGuard.requireAnyRole)가 어차피 다시 막는다. */}
        <p className="state-message state-error">권한 없음 — 할당·출고는 OPERATOR 이상만 할 수 있다</p>
      </div>
    )
  }

  return <ShipmentWorkspace warehouses={me.warehouses} />
}

function ShipmentWorkspace({ warehouses }: { warehouses: string[] }) {
  const [allocations, setAllocations] = useState<AllocationRecord[]>([])

  function handleAllocated(record: AllocationRecord) {
    setAllocations((prev) => [record, ...prev])
  }

  function handleReleased(key: string) {
    setAllocations((prev) => prev.map((a) => (a.key === key ? { ...a, status: 'released' } : a)))
  }

  function handleConsumed(keys: string[]) {
    setAllocations((prev) => prev.map((a) => (keys.includes(a.key) ? { ...a, status: 'consumed' } : a)))
  }

  return (
    <div className="screen">
      <header className="screen-header">
        <h1>출고</h1>
      </header>

      <div className="callout callout-info">
        출고는 두 단계다 — <strong>① 할당</strong>이 먼저고 <strong>② 출고 확정</strong>이 나중이다. 로트는
        사람이 고르지 않는다: 할당 요청에는 SKU·수량만 넣고, 유통기한이 빠른 로트부터(FEFO) 서버가 자동으로
        예약한다. 할당은 재고를 움직이지 않는다 — 보유(on-hand)는 그대로고 가용(available)만 줄어든다.
        실제 재고 이동과 원장 기록은 출고 확정 때 일어난다.
      </div>

      <div className="detail-section">
        <h3>1단계 — 할당 요청</h3>
        <AllocationForm warehouses={warehouses} onAllocated={handleAllocated} />
      </div>

      <div className="detail-section">
        <h3>이번 세션에서 요청한 할당</h3>
        <p className="basis-note">
          할당 목록을 조회하는 API가 없어(GET이 없다), 이 화면에서 만든 할당만 보여준다 — 새로고침하면
          목록이 비워진다(서버의 할당 자체는 남아 있다).
        </p>
        <AllocationList allocations={allocations} onReleased={handleReleased} />
      </div>

      <ShipmentPanel warehouses={warehouses} allocations={allocations} onConsumed={handleConsumed} />
    </div>
  )
}

function AllocationForm({
  warehouses,
  onAllocated,
}: {
  warehouses: string[]
  onAllocated: (record: AllocationRecord) => void
}) {
  const [warehouseCode, setWarehouseCode] = useState(warehouses[0] ?? '')
  const [orderLineRef, setOrderLineRef] = useState('')
  const [skuCode, setSkuCode] = useState('')
  const [qty, setQty] = useState('')
  const [allowInCount, setAllowInCount] = useState(false)

  const mutation = useMutation({
    mutationFn: () =>
      apiPost<AllocateResponse>('/api/allocations', {
        orderLineRef,
        warehouseCode,
        skuCode,
        qty: Number(qty),
        allowInCount,
      }),
    onSuccess: (result) => {
      onAllocated({
        key: `${Date.now()}:${orderLineRef}`,
        orderLineRef,
        warehouseCode,
        skuCode,
        qty: Number(qty),
        allocationIds: result.allocationIds,
        lines: result.lines.map((l) => ({
          key: `${l.allocationId}:${l.locationCode}:${l.skuCode}:${l.lotNo}`,
          locationCode: l.locationCode,
          skuCode: l.skuCode,
          lotNo: l.lotNo,
          qty: String(l.qty),
        })),
        status: 'active',
      })
      // 성공하면 폼을 비운다 — 창고·allowInCount는 남긴다(연달아 같은 창고에 할당하는 경우가 흔하다).
      setOrderLineRef('')
      setSkuCode('')
      setQty('')
    },
  })

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    mutation.mutate()
  }

  const canSubmit = orderLineRef.trim() !== '' && skuCode.trim() !== '' && Number(qty) > 0

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
          <span className="field-label">주문번호(orderLineRef)</span>
          <input type="text" value={orderLineRef} onChange={(e) => setOrderLineRef(e.target.value)} required />
        </label>
        <label className="field">
          <span className="field-label">SKU</span>
          <input type="text" className="mono" value={skuCode} onChange={(e) => setSkuCode(e.target.value)} required />
        </label>
        <label className="field">
          <span className="field-label">수량</span>
          <input type="number" min={1} className="mono" value={qty} onChange={(e) => setQty(e.target.value)} required />
        </label>
      </div>

      <label className="checkbox-field">
        <input type="checkbox" checked={allowInCount} onChange={(e) => setAllowInCount(e.target.checked)} />
        <span>실사 중인 로케이션 재고도 후보에 넣는다(allowInCount)</span>
      </label>
      <p className="idem-note">
        체크하지 않으면 지금 실사가 진행 중인 로케이션의 재고는 할당 후보에서 빠진다. 체크하면 그 재고도
        가져갈 수 있다 — 실사 결과가 확정되기 전에 그 재고가 팔려나갈 수 있다는 뜻이다.
      </p>

      {mutation.error && <ApiErrorMessage error={mutation.error} />}

      <div className="detail-actions">
        <button type="submit" className="btn btn-primary" disabled={!canSubmit || mutation.isPending}>
          {mutation.isPending ? '할당 요청 중…' : '할당 요청'}
        </button>
      </div>
    </form>
  )
}

function AllocationList({
  allocations,
  onReleased,
}: {
  allocations: AllocationRecord[]
  onReleased: (key: string) => void
}) {
  if (allocations.length === 0) {
    return <p className="state-message">이번 세션에서 요청한 할당이 없다</p>
  }
  return (
    <div className="table-panel">
      <table className="data-table">
        <thead>
          <tr>
            <th>주문번호</th>
            <th>창고</th>
            <th>SKU</th>
            <th className="num">수량</th>
            <th>할당 ID</th>
            <th>상태</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          {allocations.map((a) => (
            <AllocationRow key={a.key} allocation={a} onReleased={onReleased} />
          ))}
        </tbody>
      </table>
    </div>
  )
}

function AllocationRow({
  allocation,
  onReleased,
}: {
  allocation: AllocationRecord
  onReleased: (key: string) => void
}) {
  const releaseMutation = useMutation({
    mutationFn: () => apiDelete<void>('/api/allocations', { allocationIds: allocation.allocationIds }),
    onSuccess: () => onReleased(allocation.key),
  })

  // 활성일 때는 유효(초록)와 같은 색으로, 해제됐으면 가상 로케이션과 같은 회색으로, 소진됐으면 실사중
  // 배지와 같은 강조색으로 — 새 색을 만들지 않고 기존 배지 색을 의미별로 재사용한다.
  const badgeClass =
    allocation.status === 'active' ? 'badge-valid' : allocation.status === 'consumed' ? 'badge-incount' : 'badge-virtual'

  return (
    <tr>
      <td className="mono">{allocation.orderLineRef}</td>
      <td className="mono">{allocation.warehouseCode}</td>
      <td className="mono">{allocation.skuCode}</td>
      <td className="num mono">{allocation.qty}</td>
      <td className="mono">{allocation.allocationIds.join(', ')}</td>
      <td>
        <span className={`badge ${badgeClass}`}>{ALLOC_STATUS_LABEL[allocation.status]}</span>
      </td>
      <td>
        {allocation.status === 'active' && (
          <button
            type="button"
            className="btn btn-danger"
            disabled={releaseMutation.isPending}
            onClick={() => releaseMutation.mutate()}
          >
            {releaseMutation.isPending ? '해제 중…' : '할당 해제'}
          </button>
        )}
        {releaseMutation.error && <ApiErrorMessage error={releaseMutation.error} />}
      </td>
    </tr>
  )
}

function ShipmentPanel({
  warehouses,
  allocations,
  onConsumed,
}: {
  warehouses: string[]
  allocations: AllocationRecord[]
  onConsumed: (keys: string[]) => void
}) {
  const [warehouseCode, setWarehouseCode] = useState(warehouses[0] ?? '')
  const [orderLineRef, setOrderLineRef] = useState('')
  const [shipmentSeq, setShipmentSeq] = useState('1')
  const [selectedAllocKeys, setSelectedAllocKeys] = useState<Set<string>>(new Set())
  const [lastResult, setLastResult] = useState<PostingResultResponse | null>(null)

  const activeAllocations = allocations.filter((a) => a.status === 'active' && a.warehouseCode === warehouseCode)

  function toggleAlloc(key: string) {
    setSelectedAllocKeys((prev) => {
      const next = new Set(prev)
      if (next.has(key)) {
        next.delete(key)
      } else {
        next.add(key)
      }
      return next
    })
  }

  const selectedAllocations = allocations.filter((a) => selectedAllocKeys.has(a.key))
  const consumeAllocationIds = selectedAllocations.flatMap((a) => a.allocationIds)
  // 출고 줄은 사용자가 입력하지 않는다 — 소진할 할당을 고르면 그 할당이 예약한 물리 줄(서버가 FEFO로
  // 고른 로케이션·로트·수량)을 그대로 쓴다. 그래서 물리 줄이 할당과 어긋날 일이 없다(ORPHAN_CONSUME 방지).
  const parsedLines = selectedAllocations
    .flatMap((a) => a.lines)
    .map((l) => ({ ...l, qtyNum: Number(l.qty) || 0 }))
    .filter((l) => l.qtyNum > 0)

  const mutation = useMutation({
    mutationFn: () =>
      apiPost<PostingResultResponse>('/api/shipments', {
        orderLineRef,
        shipmentSeq: Number(shipmentSeq),
        warehouseCode,
        lines: parsedLines.map((l) => ({ locationCode: l.locationCode, skuCode: l.skuCode, lotNo: l.lotNo, qty: l.qtyNum })),
        consumeAllocationIds,
      }),
    onSuccess: (result) => {
      setLastResult(result)
      onConsumed([...selectedAllocKeys])
      setSelectedAllocKeys(new Set())
      setOrderLineRef('')
    },
  })

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setLastResult(null)
    mutation.mutate()
  }

  // 미리보기: 물리 줄은 입력한 그대로(음수), V-CUSTOMER 상대 줄은 (SKU,로트)별로 합쳐 하나씩 —
  // PostingController#ship(:web)과 같은 방식으로 그린다.
  const customerTotals = new Map<string, number>()
  for (const l of parsedLines) {
    const key = `${l.skuCode}::${l.lotNo}`
    customerTotals.set(key, (customerTotals.get(key) ?? 0) + l.qtyNum)
  }
  const idemKey = `shipment:${orderLineRef || '?'}:${shipmentSeq || '?'}`
  const canSubmit = orderLineRef.trim() !== '' && parsedLines.length > 0 && consumeAllocationIds.length > 0

  return (
    <div className="detail-section">
      <h3>2단계 — 출고 확정</h3>
      <p className="basis-note">
        출고 줄은 직접 입력하지 않는다 — 아래에서 소진할 할당을 고르면, 그 할당이 예약한 물리
        로케이션·로트·수량(서버가 FEFO로 고른 결과)이 그대로 출고 줄로 채워진다.
      </p>

      <form className="receipt-form" onSubmit={handleSubmit}>
        <div className="receipt-form-grid">
          <label className="field">
            <span className="field-label">창고</span>
            <select
              value={warehouseCode}
              onChange={(e) => {
                setWarehouseCode(e.target.value)
                setSelectedAllocKeys(new Set())
              }}
            >
              {warehouses.map((code) => (
                <option key={code} value={code}>
                  {code}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span className="field-label">주문번호(orderLineRef)</span>
            <input type="text" value={orderLineRef} onChange={(e) => setOrderLineRef(e.target.value)} required />
          </label>
          <label className="field">
            <span className="field-label">출고 차수(shipmentSeq)</span>
            <input
              type="number"
              min={1}
              className="mono"
              value={shipmentSeq}
              onChange={(e) => setShipmentSeq(e.target.value)}
              required
            />
          </label>
        </div>

        <div>
          <span className="field-label">소진할 할당(consumeAllocationIds)</span>
          {activeAllocations.length === 0 ? (
            <p className="state-message">이 창고에 활성 할당이 없다 — 먼저 위에서 할당을 요청하라</p>
          ) : (
            <ul className="alloc-pick-list">
              {activeAllocations.map((a) => (
                <li key={a.key}>
                  <label className="checkbox-field">
                    <input type="checkbox" checked={selectedAllocKeys.has(a.key)} onChange={() => toggleAlloc(a.key)} />
                    <span className="mono">
                      {a.orderLineRef} · {a.skuCode} · {a.qty}개 · ID {a.allocationIds.join(',')}
                    </span>
                  </label>
                </li>
              ))}
            </ul>
          )}
        </div>

        {parsedLines.length > 0 && (
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
                {parsedLines.map((l) => (
                  <tr key={l.key}>
                    <td className="mono">{l.locationCode}</td>
                    <td className="mono">{l.skuCode}</td>
                    <td className="mono">{l.lotNo}</td>
                    <td className="num mono">{l.qtyNum}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {/* 이 화면의 핵심 — 출고 확정 때 실제로 기록될 원장이다. 물리 줄들이 음수로 빠지고, V-CUSTOMER가
            (SKU,로트)별 합계만큼 양수로 채워져 전체 합은 항상 0이다. */}
        {parsedLines.length > 0 && (
          <div className="receipt-preview">
            <h3>기록될 원장 (미리보기)</h3>
            {parsedLines.map((l) => (
              <div className="receipt-preview-row" key={l.key}>
                <span className="mono">
                  {l.locationCode} · {l.skuCode} · {l.lotNo}
                </span>
                <span className="mono receipt-preview-qty qty-negative">-{l.qtyNum}</span>
              </div>
            ))}
            {[...customerTotals.entries()].map(([key, total]) => {
              const [skuCode, lotNo] = key.split('::')
              return (
                <div className="receipt-preview-row" key={key}>
                  <span className="mono">
                    {V_CUSTOMER} · {skuCode} · {lotNo} <span className="badge badge-virtual">가상</span>
                  </span>
                  <span className="mono receipt-preview-qty qty-positive">+{total}</span>
                </div>
              )
            })}
            <div className="receipt-preview-row receipt-preview-total">
              <span>합계</span>
              <span className="mono receipt-preview-qty">0</span>
            </div>
            <p className="idem-note">
              멱등 키: <span className="mono">{idemKey}</span> — 같은 주문·출고차수로 다시 보내도 새 거래가
              생기지 않는다.
            </p>
          </div>
        )}

        {mutation.error && <ApiErrorMessage error={mutation.error} />}
        {lastResult && <div className="callout callout-info">출고가 확정됐다 — 거래 #{lastResult.txnId}</div>}

        <div className="detail-actions">
          <button type="submit" className="btn btn-primary" disabled={!canSubmit || mutation.isPending}>
            {mutation.isPending ? '확정 중…' : '출고 확정'}
          </button>
        </div>
      </form>
    </div>
  )
}
