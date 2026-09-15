import { useEffect, useState } from 'react'
import { ApiError } from '../api/client'

/** 제안 승인·이슈 화면이 함께 쓰는 작은 표시 조각들. 화면마다 새로 만들지 않으려고 여기 모았다. */

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
