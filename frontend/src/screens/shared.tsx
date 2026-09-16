import { useEffect, useState, type ReactNode } from 'react'
import { ApiError } from '../api/client'
import { useMe, type MeResponse } from '../hooks/useMe'

/** 화면들이 함께 쓰는 작은 표시 조각들. 화면마다 새로 만들지 않으려고 여기 모았다. */

/** 게이트가 그리는 화면. 본문 없이 제목과 안내 문구 한 줄뿐이다 — 각 화면의 껍데기와 같은 마크업이다. */
function GateScreen({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="screen">
      <header className="screen-header">
        <h1>{title}</h1>
      </header>
      {children}
    </div>
  )
}

type WarehouseGateResult =
  // gate가 있으면 화면을 그리지 말고 그것을 그대로 돌려준다. 둘 중 하나만 널이 아니다.
  { gate: ReactNode; me: null } | { gate: null; me: MeResponse }

/**
 * 일곱 화면이 모두 거치는 {@code /api/me} 게이트.
 *
 * <p>화면들은 창고 드롭다운을 {@code me.warehouses}로 채우고, 목록 쿼리는 {@code enabled: warehouse != null}
 * 이다. 그래서 {@code /api/me}가 실패했거나 창고 권한이 0개면 쿼리가 <b>시작조차 하지 않고</b>
 * {@code isLoading}도 false다 — 게이트가 없으면 빈 드롭다운만 있는 화면이나 영원한 "불러오는 중…"이
 * 남고, 사용자는 권한 문제인지 데이터가 없는 것인지 구분할 수 없다.
 *
 * <p>같은 게이트가 화면마다 복제돼 있었고 다섯 화면은 아예 빠져 있었다. 화면마다 다른 것은 제목뿐이라
 * 그것만 받는다. 세 상태를 구분한다: me 로딩 중 / me 실패 / 창고 0개.
 *
 * <p>창고 0개를 역할 게이트(입고·출고·실사의 "권한 없음")보다 먼저 본다. 창고가 없으면 역할이
 * 무엇이든 그 화면에서 할 수 있는 일이 없기 때문이다.
 */
export function useWarehouseGate(title: string): WarehouseGateResult {
  const { data: me, isError } = useMe()

  if (!me) {
    return {
      me: null,
      gate: (
        <GateScreen title={title}>
          {isError ? (
            // 실패를 "불러오는 중…"으로 두면 화면이 영원히 그 상태로 남는다 — 쿼리는 이미 끝났다.
            <p className="state-message state-error">사용자 정보를 불러오지 못했다. 새로고침해 달라.</p>
          ) : (
            <p className="state-message">불러오는 중…</p>
          )}
        </GateScreen>
      ),
    }
  }

  if (me.warehouses.length === 0) {
    return {
      me: null,
      gate: (
        <GateScreen title={title}>
          <p className="state-message">접근할 수 있는 창고가 없다. 관리자에게 권한을 요청해 달라.</p>
        </GateScreen>
      ),
    }
  }

  return { me, gate: null }
}

export function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString('ko-KR', { hour12: false })
}

/** 오류를 {code,message} 그대로가 아니라 message(사람이 읽는 문구)로만 보여준다. code는 노출하지 않는다. */
export function ApiErrorMessage({ error }: { error: unknown }) {
  if (error instanceof ApiError) {
    return <p className="state-message state-error">{error.message}</p>
  }
  return <p className="state-message state-error">알 수 없는 오류가 발생했다</p>
}

/** 만료까지 남은 시간. 30초마다 다시 그려 화면에 띄워둔 채로도 값이 낡지 않게 한다. */
export function ExpiryCountdown({ expiresAt }: { expiresAt: string }) {
  const [, tick] = useState(0)
  useEffect(() => {
    const timer = setInterval(() => tick((n) => n + 1), 30_000)
    return () => clearInterval(timer)
  }, [])

  const remainingMs = new Date(expiresAt).getTime() - Date.now()
  if (remainingMs <= 0) {
    return <span className="countdown countdown-expired">만료됨</span>
  }
  const minutes = Math.floor(remainingMs / 60_000)
  const label = minutes < 60 ? `${minutes}분 남음` : `${Math.floor(minutes / 60)}시간 ${minutes % 60}분 남음`
  return <span className={`countdown${minutes < 10 ? ' countdown-warning' : ''}`}>{label}</span>
}

/**
 * AI가 작성한 서술(제안 rationale, 이슈 ai_analysis)을 사람이 쓴 것과 구분해 보여준다 — 검증되지 않은
 * 내용이라는 걸 시각적으로 알리기 위해서다. JSON이면 보기 좋게 펼치고, 아니면 그대로 보여준다.
 */
export function AiNote({ label, content }: { label: string; content: string }) {
  let display = content
  try {
    display = JSON.stringify(JSON.parse(content), null, 2)
  } catch {
    // JSON이 아니면(예: rationale은 그냥 문장이다) 원문을 그대로 둔다
  }
  return (
    <div className="ai-note">
      <div className="ai-note-label">
        <span className="ai-badge">AI</span>
        {label} · 검증되지 않은 서술이다
      </div>
      <div className="ai-note-body">{display}</div>
    </div>
  )
}

export function validBadge(valid: boolean) {
  return <span className={`badge ${valid ? 'badge-valid' : 'badge-invalid'}`}>{valid ? '유효' : '이탈'}</span>
}
