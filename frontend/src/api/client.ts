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

/**
 * Authorization: Basic 헤더 값을 만든다.
 *
 * <p>btoa에 문자열을 그대로 넘기면 안 된다 — btoa는 인자를 "각 문자가 한 바이트"인 이진 문자열로 보므로
 * Latin-1(코드포인트 0–255) 밖의 문자를 만나면 InvalidCharacterError를 던진다. 한글 비밀번호면 요청이
 * 아예 나가지 않고, 그 예외는 ApiError가 아니라 DOMException이라 LoginForm의 마지막 else로 빠져
 * "로그인 요청에 실패했다"가 뜬다 — 비밀번호 문제인데 서버 장애처럼 보인다.
 *
 * <p>RFC 7617에서 user-pass는 문자열이 아니라 옥텟 열이고, 그 옥텟을 만드는 인코딩은 규격이 정하지
 * 않는다 — 서버가 정하고 401의 charset 파라미터로 알릴 수 있으며 그 값으로 허용된 것은 UTF-8뿐이다.
 * 그래서 UTF-8로 보내는 것은 서버가 UTF-8로 읽을 때만 맞다: 이 서버가 그렇다는 것은 추측이 아니라
 * 실측이다(web/.../BasicAuthCharsetTest). TextEncoder로 UTF-8 바이트를 만든 뒤 base64한다.
 */
function basicAuthHeader(creds: Credentials): string {
  const bytes = new TextEncoder().encode(`${creds.username}:${creds.password}`)
  // 바이트 하나당 문자 하나인 이진 문자열로 바꿔 btoa에 넘긴다. 전개 연산자(String.fromCharCode(...bytes))는
  // 긴 입력에서 인자 개수 한계에 걸리므로 쓰지 않는다.
  let binary = ''
  for (const byte of bytes) {
    binary += String.fromCharCode(byte)
  }
  return 'Basic ' + btoa(binary)
}

/**
 * 세 래퍼의 공통부 — 자격 증명 붙이기, 401 처리, 실패 응답을 ApiError로 바꾸기.
 *
 * <p>응답 본문은 여기서 읽지 않고 Response를 그대로 돌려준다. 성공 응답의 파싱 방식은 메서드마다
 * 다르고(GET은 항상 JSON, POST·DELETE는 빈 본문이 정상), 그 차이를 여기서 하나로 합치면 각 래퍼의
 * 계약이 실제보다 넓어진다.
 */
// headers를 RequestInit 안에 담아 받지 않고 따로 받는다. RequestInit['headers']는 Headers나
// [k,v][] 형태도 허용하는 합 타입이라, 그걸 Record로 캐스팅해 스프레드하면 타입 오류가 나야 할
// 자리가 조용한 헤더 소실({}가 된다)로 바뀐다 — tsc는 통과시킨다.
async function request(path: string, init: Omit<RequestInit, 'headers'>,
    extraHeaders: Record<string, string> = {}): Promise<Response> {
  const headers: Record<string, string> = { ...extraHeaders }
  if (credentials) {
    headers.Authorization = basicAuthHeader(credentials)
  }

  const res = await fetch(path, { ...init, headers })

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

  return res
}

/** GET 전용 래퍼. 조회 응답은 언제나 JSON 본문이 있다. */
export async function apiGet<T>(path: string): Promise<T> {
  const res = await request(path, {})
  return (await res.json()) as T
}

/** POST 전용 래퍼. */
export async function apiPost<T>(path: string, body?: unknown): Promise<T> {
  const res = await request(
    path,
    { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) },
    { 'Content-Type': 'application/json' },
  )

  // approve는 본문(ApprovalOutcome)이 있지만 reject·ack·resolve는 본문이 없다(void) — 빈 응답은 그대로 둔다.
  const text = await res.text()
  return (text ? JSON.parse(text) : undefined) as T
}

/** DELETE 전용 래퍼(할당 해제 — AllocationController#release는 본문 있는 DELETE다). */
export async function apiDelete<T>(path: string, body?: unknown): Promise<T> {
  const res = await request(
    path,
    { method: 'DELETE', body: body === undefined ? undefined : JSON.stringify(body) },
    { 'Content-Type': 'application/json' },
  )

  // release는 본문이 없다(void) — apiPost와 같은 이유로 빈 응답을 undefined로 돌려준다.
  const text = await res.text()
  return (text ? JSON.parse(text) : undefined) as T
}
