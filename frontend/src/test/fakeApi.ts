import { http, HttpResponse } from 'msw'

/**
 * 로그인 전환을 돌려보기 위한 최소한의 가짜 백엔드.
 *
 * <p>여기서 중요한 것은 응답의 정확함이 아니라 <b>사용자마다 답이 다르다</b>는 점 하나다. 같은 답을
 * 주는 서버 앞에서는 "A의 캐시가 B 화면에 남았다"를 구분할 방법이 없기 때문이다.
 */
export interface FakeUser {
  password: string
  roles: string[]
  warehouses: string[]
}

/**
 * web/src/main/resources/application.yml의 실제 사용자 셋(choi.dw·park.jh·lee.sm)을 역할·창고까지
 * 그대로 맞춘다 — 여기서 어긋나면 화면은 여기 정의된 가짜 권한으로 그려지고 통과하는데, 실제 서버
 * 앞에서는 다른 역할·창고로 막히거나 열린다.
 */
export const USERS: Record<string, FakeUser> = {
  'choi.dw': { password: 'zerosum', roles: ['SUPERVISOR'], warehouses: ['ICN01', 'YIT01'] },
  'park.jh': { password: 'zerosum', roles: ['OPERATOR'], warehouses: ['ICN01'] },
  'lee.sm': { password: 'zerosum', roles: ['VIEWER'], warehouses: ['YIT01'] },
  // application.yml에 없는 가공 픽스처. 창고 권한이 하나도 없는 사용자가 필요한데(로그인 성공 판정이
  // 창고와 무관하다는 것을 보이는 용도) 실제 사용자 중에는 그런 사람이 없다 — 이것만 가공이다.
  'jung.hs': { password: 'zerosum', roles: ['OPERATOR'], warehouses: [] },
  // 이것도 가공 픽스처다. 비ASCII 비밀번호를 쓰는 사용자 — btoa는 Latin-1 밖의 문자에서 던지므로
  // 이런 사용자가 없으면 인증 헤더를 만드는 코드가 ASCII로만 시험된다.
  'han.gm': { password: '비밀번호-Ω', roles: ['OPERATOR'], warehouses: ['ICN01'] },
}

/**
 * Authorization: Basic 헤더의 base64를 풀어 "아이디:비밀번호" 문자열로 돌려준다.
 *
 * <p>atob의 결과는 바이트 하나당 문자 하나인 이진 문자열이지 텍스트가 아니다 — 그대로 쓰면 UTF-8
 * 여러 바이트로 적힌 글자가 깨진다. 실제 서버(Spring Security의 BasicAuthenticationConverter)가
 * 옥텟을 UTF-8로 읽으므로(web/.../BasicAuthCharsetTest에서 실측) 여기서도 UTF-8로 읽는다. 이게
 * 어긋나면 프론트가 올바른 헤더를 보내도 가짜 서버만 혼자 401을 주게 된다.
 */
export function decodeBasic(header: string): string {
  const binary = atob(header.slice('Basic '.length))
  return new TextDecoder().decode(Uint8Array.from(binary, (ch) => ch.charCodeAt(0)))
}

/** Authorization: Basic 헤더를 풀어 사용자 이름을 돌려준다. 자격 증명이 틀리면 null. */
export function authenticate(request: Request): string | null {
  const header = request.headers.get('Authorization')
  if (!header?.startsWith('Basic ')) {
    return null
  }
  const [username, password] = decodeBasic(header).split(':')
  const user = USERS[username]
  return user && user.password === password ? username : null
}

/**
 * 401 응답. 상수가 아니라 <b>매번 새로 만드는 함수</b>여야 한다 — Response 본문은 한 번 읽히면
 * 소진되므로, 하나를 만들어 두고 두 번 돌려주면 두 번째 요청에서 MSW가 "Response body object should
 * not be disturbed or locked"로 터진다. 게다가 그 오류는 처리되지 않은 rejection으로 새어 나와,
 * 실패한 테스트가 아니라 엉뚱한 자리를 가리킨다(실측: 한 파일에서 401을 두 번 쓰자마자 났다).
 */
export const unauthorized = () =>
  HttpResponse.json({ code: 'UNAUTHORIZED', message: '인증에 실패했다' }, { status: 401 })

export const meHandler = http.get('*/api/me', ({ request }) => {
  const username = authenticate(request)
  if (!username) {
    return unauthorized()
  }
  const user = USERS[username]
  return HttpResponse.json({ username, roles: user.roles, warehouses: user.warehouses })
})

// 재고 조회. 권한 없는 창고는 403이다 — 서버(WarehouseScopeArgumentResolver)와 같은 구분이고,
// "403은 자격 증명이 틀린 것이 아니다"를 테스트가 다룰 수 있게 하려면 401과 갈라져 있어야 한다.
export const stockHandler = http.get('*/api/stock', ({ request }) => {
  const username = authenticate(request)
  if (!username) {
    return unauthorized()
  }
  const warehouse = new URL(request.url).searchParams.get('warehouse') ?? ''
  if (!USERS[username].warehouses.includes(warehouse)) {
    return HttpResponse.json({ code: 'FORBIDDEN', message: '권한이 없다' }, { status: 403 })
  }
  return HttpResponse.json([
    {
      warehouseCode: warehouse,
      skuCode: 'SKU-200002',
      skuName: '우유 1L',
      lotNo: 'L20260910-B',
      expiryDate: '2026-09-30',
      locationCode: `${warehouse}-A-01`,
      locationType: 'STORAGE',
      onHandQty: 40,
      allocatedQty: 0,
      availableQty: 40,
      inCount: false,
    },
  ])
})
