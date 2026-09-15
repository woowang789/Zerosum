import { useState, type FormEvent } from 'react'
import { useMutation } from '@tanstack/react-query'
import { apiPost } from '../api/client'
import { useMe } from '../hooks/useMe'
import { ApiErrorMessage } from './shared'

interface PostingResultResponse {
  txnId: number
}

// 상대 줄 전용 가상 로케이션 — PostingController의 V_SUPPLIER와 같은 문자열이다. 여기서는 미리보기를
// 그리는 데만 쓴다. 실제로 이 줄을 채우는 건 서버다(클라이언트는 물리 로케이션 하나만 보낸다).
const V_SUPPLIER = 'V-SUPPLIER'

export function ReceiptScreen() {
  const { data: me } = useMe()
  const canWrite = me?.roles.some((r) => r === 'OPERATOR' || r === 'SUPERVISOR') ?? false

  if (!me) {
    return (
      <div className="screen">
        <header className="screen-header">
          <h1>입고</h1>
        </header>
        <p className="state-message">불러오는 중…</p>
      </div>
    )
  }

  if (!canWrite) {
    return (
      <div className="screen">
        <header className="screen-header">
          <h1>입고</h1>
        </header>
        {/* VIEWER에게는 폼 자체를 감춘다 — 화면을 정리하는 용도일 뿐이다. 감춰도 서버
            (PostingController#receive의 AccessGuard.requireAnyRole)가 VIEWER의 요청을 403으로 다시 막는다. */}
        <p className="state-message state-error">권한 없음 — 입고 등록은 OPERATOR 이상만 할 수 있다</p>
      </div>
    )
  }

  return <ReceiptForm warehouses={me.warehouses} />
}

function ReceiptForm({ warehouses }: { warehouses: string[] }) {
  const [warehouseCode, setWarehouseCode] = useState(warehouses[0] ?? '')
  const [poLineRef, setPoLineRef] = useState('')
  const [receiptSeq, setReceiptSeq] = useState('1')
  const [locationCode, setLocationCode] = useState('')
  const [skuCode, setSkuCode] = useState('')
  const [lotNo, setLotNo] = useState('')
  const [qty, setQty] = useState('')
  const [lastResult, setLastResult] = useState<PostingResultResponse | null>(null)

  const mutation = useMutation({
    mutationFn: () =>
      apiPost<PostingResultResponse>('/api/receipts', {
        poLineRef,
        receiptSeq: Number(receiptSeq),
        warehouseCode,
        locationCode,
        skuCode,
        lotNo,
        qty: Number(qty),
      }),
    onSuccess: (result) => {
      setLastResult(result)
      // 성공하면 폼을 비운다 — 창고 선택만 남긴다(같은 창고에 연달아 입고를 등록하는 경우가 흔하다).
      setPoLineRef('')
      setReceiptSeq('1')
      setLocationCode('')
      setSkuCode('')
      setLotNo('')
      setQty('')
    },
  })

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setLastResult(null)
    mutation.mutate()
  }

  const qtyNum = Number(qty) || 0
  const idemKey = `receipt:${poLineRef || '?'}:${receiptSeq || '?'}`
  const canSubmit =
    poLineRef.trim() !== '' &&
    receiptSeq.trim() !== '' &&
    locationCode.trim() !== '' &&
    skuCode.trim() !== '' &&
    lotNo.trim() !== '' &&
    qtyNum > 0

  return (
    <div className="screen">
      <header className="screen-header">
        <h1>입고</h1>
      </header>

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
            <span className="field-label">발주 줄(poLineRef)</span>
            <input type="text" value={poLineRef} onChange={(e) => setPoLineRef(e.target.value)} required />
          </label>
          <label className="field">
            <span className="field-label">입고 차수(receiptSeq)</span>
            <input
              type="number"
              min={1}
              className="mono"
              value={receiptSeq}
              onChange={(e) => setReceiptSeq(e.target.value)}
              required
            />
          </label>
          <label className="field">
            <span className="field-label">로케이션 (물리)</span>
            <input
              type="text"
              className="mono"
              value={locationCode}
              onChange={(e) => setLocationCode(e.target.value)}
              placeholder="예: RCV-01"
              required
            />
          </label>
          <label className="field">
            <span className="field-label">SKU</span>
            <input type="text" className="mono" value={skuCode} onChange={(e) => setSkuCode(e.target.value)} required />
          </label>
          <label className="field">
            <span className="field-label">로트</span>
            <input type="text" className="mono" value={lotNo} onChange={(e) => setLotNo(e.target.value)} required />
          </label>
          <label className="field">
            <span className="field-label">수량</span>
            <input type="number" min={1} className="mono" value={qty} onChange={(e) => setQty(e.target.value)} required />
          </label>
        </div>

        {/* 이 화면의 핵심 — 재고는 어디선가 생기지 않는다. 사용자는 물리 로케이션 한 줄만 입력하지만
            실제로는 가상 로케이션(V-SUPPLIER)에서 같은 수량이 빠지는 상대 줄이 함께 기록된다. 그걸
            숨기지 않고 미리 보여준다. */}
        <div className="receipt-preview">
          <h3>기록될 원장 (미리보기)</h3>
          <div className="receipt-preview-row">
            <span className="mono">
              {V_SUPPLIER} <span className="badge badge-virtual">가상</span>
            </span>
            <span className={`mono receipt-preview-qty${qtyNum > 0 ? ' qty-negative' : ''}`}>
              {qtyNum > 0 ? `-${qtyNum}` : '0'}
            </span>
          </div>
          <div className="receipt-preview-row">
            <span className="mono">{locationCode || '(로케이션)'}</span>
            <span className={`mono receipt-preview-qty${qtyNum > 0 ? ' qty-positive' : ''}`}>
              {qtyNum > 0 ? `+${qtyNum}` : '0'}
            </span>
          </div>
          <div className="receipt-preview-row receipt-preview-total">
            <span>합계</span>
            <span className="mono receipt-preview-qty">0</span>
          </div>
          <p className="idem-note">
            멱등 키: <span className="mono">{idemKey}</span> — 같은 발주 줄·차수로 다시 등록해도 새 거래가
            생기지 않고 처음 거래가 그대로 돌아온다. 그래서 같은 요청을 두 번 보내도 안전하다.
          </p>
        </div>

        {mutation.error && <ApiErrorMessage error={mutation.error} />}
        {lastResult && <div className="callout callout-info">기록됐다 — 거래 #{lastResult.txnId}</div>}

        <div className="detail-actions">
          <button type="submit" className="btn btn-primary" disabled={!canSubmit || mutation.isPending}>
            {mutation.isPending ? '등록 중…' : '입고 등록'}
          </button>
        </div>
      </form>
    </div>
  )
}
