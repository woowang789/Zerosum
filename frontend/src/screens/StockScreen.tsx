import { useState, type FormEvent } from 'react'
import { useQuery } from '@tanstack/react-query'
import { apiGet, ApiError } from '../api/client'

type LocationType = 'STORAGE' | 'RECEIVING' | 'RETURN_HOLD' | 'DAMAGED' | 'TRANSIT'

interface StockRow {
  warehouseCode: string
  skuCode: string
  skuName: string
  lotNo: string
  expiryDate: string | null
  locationCode: string
  locationType: LocationType
  onHandQty: number
  allocatedQty: number
  availableQty: number
  inCount: boolean
}

// 로그인한 사용자의 창고 목록을 내려주는 API가 없다(/api/me 같은 것이 없음) — 지금은 알려진 두 창고를
// 직접 고르게 하고, 권한이 없는 창고를 고르면 403을 그대로 안내한다.
const WAREHOUSES = ['ICN01', 'YIT01']

const LOCATION_TYPE_LABEL: Record<string, string> = {
  RECEIVING: '입고 대기',
  RETURN_HOLD: '검수 대기',
  DAMAGED: '파손',
  TRANSIT: '이동중',
}

export function StockScreen() {
  const [warehouse, setWarehouse] = useState(WAREHOUSES[0])
  const [skuInput, setSkuInput] = useState('')
  const [sku, setSku] = useState('')

  const { data, error, isLoading } = useQuery({
    queryKey: ['stock', warehouse, sku],
    queryFn: () =>
      apiGet<StockRow[]>(
        `/api/stock?warehouse=${encodeURIComponent(warehouse)}${sku ? `&sku=${encodeURIComponent(sku)}` : ''}`,
      ),
  })

  function handleSearch(e: FormEvent) {
    e.preventDefault()
    setSku(skuInput.trim())
  }

  return (
    <div className="screen">
      <header className="screen-header">
        <h1>재고 현황</h1>
      </header>

      <form className="stock-filters" onSubmit={handleSearch}>
        <label className="field field-inline">
          <span className="field-label">창고</span>
          <select value={warehouse} onChange={(e) => setWarehouse(e.target.value)}>
            {WAREHOUSES.map((code) => (
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
            value={skuInput}
            onChange={(e) => setSkuInput(e.target.value)}
            placeholder="SKU 코드 (비우면 전체)"
          />
        </label>
        <button type="submit" className="btn btn-secondary">
          조회
        </button>
      </form>

      {isLoading && <p className="state-message">불러오는 중…</p>}

      {error && <StockError error={error} warehouse={warehouse} />}

      {/* react-query는 새 조회가 실패해도 이전 결과를 data에 남겨둔다 — 다른 창고(다른 권한 범위)의
          재고가 오류 메시지와 함께 그대로 보이면 안 되므로 오류가 있는 동안은 표를 그리지 않는다. */}
      {data && !error && (
        <div className="table-panel">
          <table className="data-table">
            <thead>
              <tr>
                <th>로케이션</th>
                <th>SKU</th>
                <th>로트</th>
                <th>유통기한</th>
                <th className="num">보유</th>
                <th className="num">할당</th>
                <th className="num">가용</th>
                <th>상태</th>
              </tr>
            </thead>
            <tbody>
              {data.length === 0 && (
                <tr>
                  <td colSpan={8} className="state-message">
                    재고가 없다
                  </td>
                </tr>
              )}
              {data.map((row) => (
                <tr key={`${row.locationCode}-${row.skuCode}-${row.lotNo}`}>
                  <td className="mono">{row.locationCode}</td>
                  <td>
                    <div className="sku-code mono">{row.skuCode}</div>
                    <div className="sku-name">{row.skuName}</div>
                  </td>
                  <td className="mono">{row.lotNo}</td>
                  <td className="mono">{row.expiryDate ?? '-'}</td>
                  <td className="num mono">{row.onHandQty}</td>
                  <td className="num mono">{row.allocatedQty}</td>
                  <td className="num mono">{row.availableQty}</td>
                  <td>
                    <div className="badge-row">
                      {row.locationType !== 'STORAGE' && (
                        <span className={`badge badge-${row.locationType.toLowerCase().replace('_', '-')}`}>
                          {LOCATION_TYPE_LABEL[row.locationType] ?? row.locationType}
                        </span>
                      )}
                      {row.inCount && <span className="badge badge-incount">실사 중</span>}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

function StockError({ error, warehouse }: { error: unknown; warehouse: string }) {
  if (error instanceof ApiError && error.status === 403) {
    return <p className="state-message state-error">창고 {warehouse}에 접근할 권한이 없다</p>
  }
  if (error instanceof ApiError) {
    return <p className="state-message state-error">{error.message}</p>
  }
  return <p className="state-message state-error">알 수 없는 오류가 발생했다</p>
}
