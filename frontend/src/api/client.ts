// 자격 증명 보관 위치: 메모리(모듈 변수)가 기본이고, 새로고침 후 로그인 유지를 위해 sessionStorage에도
// 같은 값을 남긴다. localStorage는 쓰지 않는다 — XSS 한 번으로 영구 탈취되기 때문이다. sessionStorage는
// 탭을 닫으면 사라지고 다른 탭·오리진으로 새지 않아, "새로고침은 견디지만 오래 남지는 않는" 절충점이다.
const STORAGE_KEY = 'zerosum.credentials'

export interface Credentials {
  username: string
  password: string
}

let credentials: Credentials | null = loadFromSession()

function loadFromSession(): Credentials | null {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY)
    return raw ? (JSON.parse(raw) as Credentials) : null
  } catch {
    return null
  }
}

export function getCredentials(): Credentials | null {
  return credentials
}

export function setCredentials(next: Credentials): void {
  credentials = next
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(next))
  } catch {
    // sessionStorage를 쓸 수 없는 환경(프라이빗 모드 등)이면 메모리 보관만으로 동작한다.
  }
}

export function clearCredentials(): void {
  credentials = null
  try {
    sessionStorage.removeItem(STORAGE_KEY)
  } catch {
    // 위와 같음
  }
}

export class ApiError extends Error {
  status: number
  code: string

  constructor(status: number, code: string, message: string) {
    super(message)
    this.status = status
    this.code = code
  }
}

// 401을 만났을 때 인증 컨텍스트에 알리기 위한 훅. AuthProvider가 등록한다.
let onUnauthorized: (() => void) | null = null

export function setUnauthorizedHandler(handler: (() => void) | null): void {
  onUnauthorized = handler
}

/** GET 전용 래퍼. 이번 단계는 재고 조회 하나뿐이라 GET만 있으면 된다. */
export async function apiGet<T>(path: string): Promise<T> {
  const headers: Record<string, string> = {}
  if (credentials) {
    headers.Authorization = 'Basic ' + btoa(`${credentials.username}:${credentials.password}`)
  }

  const res = await fetch(path, { headers })

  if (res.status === 401) {
    clearCredentials()
    onUnauthorized?.()
    throw new ApiError(401, 'UNAUTHORIZED', '인증에 실패했다')
  }

  if (!res.ok) {
    // 오류 본문은 {code, message}가 기본이지만(ApiExceptionHandler), 403처럼 Spring Security가
    // 직접 처리하는 응답은 본문이 없을 수 있다 — 그 경우 상태 코드만으로 기본 메시지를 만든다.
    let code = 'UNKNOWN'
    let message = `요청이 실패했다 (${res.status})`
    try {
      const body = (await res.json()) as { code?: string; message?: string }
      if (body.code) code = body.code
      if (body.message) message = body.message
    } catch {
      // 본문이 JSON이 아니면 기본 메시지를 쓴다
    }
    throw new ApiError(res.status, code, message)
  }

  return (await res.json()) as T
}

/** POST 전용 래퍼. 오류 처리는 apiGet과 같다(본문 파싱 방식만 다르다 — POST 응답은 비어 있을 수 있다). */
export async function apiPost<T>(path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (credentials) {
    headers.Authorization = 'Basic ' + btoa(`${credentials.username}:${credentials.password}`)
  }

  const res = await fetch(path, {
    method: 'POST',
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })

  if (res.status === 401) {
    clearCredentials()
    onUnauthorized?.()
    throw new ApiError(401, 'UNAUTHORIZED', '인증에 실패했다')
  }

  if (!res.ok) {
    let code = 'UNKNOWN'
    let message = `요청이 실패했다 (${res.status})`
    try {
      const errBody = (await res.json()) as { code?: string; message?: string }
      if (errBody.code) code = errBody.code
      if (errBody.message) message = errBody.message
    } catch {
      // 본문이 JSON이 아니면 기본 메시지를 쓴다
    }
    throw new ApiError(res.status, code, message)
  }

  // approve는 본문(ApprovalOutcome)이 있지만 reject·ack·resolve는 본문이 없다(void) — 빈 응답은 그대로 둔다.
  const text = await res.text()
  return (text ? JSON.parse(text) : undefined) as T
}
