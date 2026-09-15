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
}

/** Authorization: Basic 헤더를 풀어 사용자 이름을 돌려준다. 자격 증명이 틀리면 null. */
export function authenticate(request: Request): string | null {
  const header = request.headers.get('Authorization')
  if (!header?.startsWith('Basic ')) {
    return null
  }
  const [username, password] = atob(header.slice('Basic '.length)).split(':')
  const user = USERS[username]
  return user && user.password === password ? username : null
}

export const UNAUTHORIZED = HttpResponse.json({ code: 'UNAUTHORIZED', message: '인증에 실패했다' }, { status: 401 })

export const meHandler = http.get('*/api/me', ({ request }) => {
  const username = authenticate(request)
  if (!username) {
    return UNAUTHORIZED
  }
  const user = USERS[username]
  return HttpResponse.json({ username, roles: user.roles, warehouses: user.warehouses })
})

// 재고 조회. 권한 없는 창고는 403이다 — 서버(WarehouseScopeArgumentResolver)와 같은 구분이고,
// "403은 자격 증명이 틀린 것이 아니다"를 테스트가 다룰 수 있게 하려면 401과 갈라져 있어야 한다.
export const stockHandler = http.get('*/api/stock', ({ request }) => {
  const username = authenticate(request)
  if (!username) {
    return UNAUTHORIZED
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
