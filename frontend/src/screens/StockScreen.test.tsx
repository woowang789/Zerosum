import { expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import App from '../App'
import { server } from '../test/server'
import { unauthorized, authenticate, meHandler, stockHandler } from '../test/fakeApi'
import { submitLogin } from '../test/ui'

/**
 * 재고 화면의 창고 선택지는 /api/me가 내려준 것뿐이어야 한다.
 *
 * <p>이 화면만 창고 코드 두 개(['ICN01', 'YIT01'])를 파일 상수로 들고 있었다. /api/me가 생기기 전에
 * 남긴 것이고 나머지 다섯 화면은 이미 useMe()를 쓰고 있었는데, 하필 <b>기본 착지 화면</b>이 혼자
 * 낡아 있었다 — 로그인하면 제일 먼저 보이는 화면에서 권한도 없는 창고가 선택지로 떴다는 뜻이다.
 *
 * <p>고른다고 데이터가 새지는 않는다(서버가 403으로 막는다). 새는 것은 <b>창고 코드 자체</b>이고,
 * 사용자는 자기 것이 아닌 창고를 고른 뒤 오류를 보고서야 그 사실을 안다.
 *
 * <p>같은 취지의 단언이 AuthContext 쪽에도 있지만 그쪽은 사용자 전환 때 <b>캐시</b>가 갈리는지를 보는
 * 자리다(입고 화면의 폼). 여기서 고정하는 것은 그것과 다르다 — 사용자를 바꾸지 않아도, 캐시가
 * 깨끗해도, 이 화면이 애초에 무엇을 선택지로 그리느냐다.
 */
it('재고 화면의 창고 선택지는 권한이 있는 창고뿐이다', async () => {
  server.use(meHandler, stockHandler)
  const user = userEvent.setup()
  render(<App />)

  await submitLogin(user, 'lee.sm') // VIEWER · YIT01 하나뿐

  expect(await screen.findByRole('option', { name: 'YIT01' })).toBeInTheDocument()
  // 하드코딩 시절에는 이 사용자에게도 ICN01이 선택지로 떴다.
  expect(screen.queryByRole('option', { name: 'ICN01' })).not.toBeInTheDocument()
  // 선택지만 맞고 조회가 엉뚱한 창고로 나가면 화면은 비어 있다 — 고른 창고로 실제 조회까지 갔는지 본다.
  expect(await screen.findByText('YIT01-A-01')).toBeInTheDocument()
})

/**
 * 영구 오류(4xx)는 재시도하지 않는다.
 *
 * <p>전역 QueryClient가 옵션 없이 만들어져 있었다 — react-query의 기본값은 무조건 3회 재시도이고,
 * 재시도 사이에는 지수 백오프까지 붙는다. "권한이 없다"처럼 다시 물어도 답이 같은 실패에 그 값을
 * 치르면, 사용자는 이미 확정된 오류 문구를 몇 초 뒤에야 보게 된다.
 *
 * <p>요청 횟수로 본다. 화면에 문구가 뜨는지만 보면 재시도가 몇 번 일어났든 결국은 통과하기 때문이다
 * (느려질 뿐이다). 403인데도 창고 선택지를 문제 삼지 않는 이유는, 선택지가 좁아진 지금도 최종
 * 판정자는 서버이기 때문이다 — 설정에서 창고가 빠지거나 /api/me 응답이 낡으면 자기 창고에서도 403이 난다.
 */
it('403은 재시도 없이 한 번만 요청하고 바로 오류를 보여준다', async () => {
  const stockRequests: string[] = []
  server.events.on('request:start', ({ request }) => {
    const url = new URL(request.url)
    if (url.pathname === '/api/stock') {
      stockRequests.push(url.search)
    }
  })
  server.use(
    meHandler,
    http.get('*/api/stock', ({ request }) => {
      if (!authenticate(request)) return unauthorized()
      return HttpResponse.json({ code: 'FORBIDDEN', message: '권한이 없다' }, { status: 403 })
    }),
  )
  const user = userEvent.setup()
  render(<App />)

  await submitLogin(user, 'park.jh') // OPERATOR · ICN01

  expect(await screen.findByText('창고 ICN01에 접근할 권한이 없다')).toBeInTheDocument()
  expect(stockRequests).toHaveLength(1)
})

/**
 * /api/me를 못 받으면 재고 화면은 그 사실을 말해야 한다.
 *
 * <p>창고 목록을 useMe()에서 가져오게 되면서 새로 생긴 실패 모드다. 재고 쿼리는
 * {@code enabled: warehouse != null}이라 창고가 정해지기 전에는 아예 시작되지 않고, 따라서
 * isLoading도 false다 — 게이트 없이 본문을 그리면 빈 드롭다운만 있는 화면이 되고 "불러오는 중…"
 * 조차 뜨지 않는다. 하필 로그인 직후 첫 착지 화면이라 그 침묵은 곧 "앱이 고장났다"로 읽힌다.
 *
 * <p>고치기 전에는 하드코딩한 창고 덕분에 최소한 "창고 ICN01에 접근할 권한이 없다"라도 떴다.
 * 즉 이 화면을 올바르게 고치는 과정에서 안내가 오히려 사라질 뻔했다.
 */
it('사용자 정보를 못 받으면 빈 화면 대신 그 사실을 알린다', async () => {
  let meCalls = 0
  server.use(
    http.get('*/api/me', ({ request }) => {
      if (!authenticate(request)) return unauthorized()
      // 첫 호출(AuthContext.login의 로그인 판정)은 통과시킨다 — 로그인 자체가 막히면
      // 이 테스트가 보려는 화면에 도달하지 못한다.
      meCalls += 1
      if (meCalls === 1) {
        return HttpResponse.json({ username: 'park.jh', roles: ['OPERATOR'], warehouses: ['ICN01'] })
      }
      return HttpResponse.json({ code: 'INTERNAL', message: '서버 오류' }, { status: 500 })
    }),
    stockHandler,
  )
  const user = userEvent.setup()
  render(<App />)

  await submitLogin(user, 'park.jh')

  // 타임아웃을 늘린 것은 기다림을 눈감아 주려는 게 아니라, 앱이 실제로 그만큼 걸리기 때문이다 —
  // 5xx는 정책상 한 번 재시도하고(App.tsx) 그 사이에 백오프가 붙는다. 기본 1초로는 재시도가 끝나기
  // 전에 단언이 먼저 터진다.
  expect(
    await screen.findByText('사용자 정보를 불러오지 못했다. 새로고침해 달라.', undefined, { timeout: 5000 }),
  ).toBeInTheDocument()

  // 재시도 정책의 나머지 절반을 여기서 고정한다. 4xx를 재시도하지 않는 것은 아래 403 테스트가 보고,
  // 5xx를 "무한히가 아니라 한 번만" 재시도하는 것은 이 횟수가 본다. 1(로그인 판정) + 1(useMe 최초)
  // + 1(재시도) = 3이며, retry를 3회로 되돌리면 이 숫자가 늘어난다.
  expect(meCalls).toBe(3)
})

/**
 * 창고 권한이 하나도 없는 사용자도 이유를 들어야 한다.
 *
 * <p>가짜 픽스처 han.gm·jung.hs가 이 상태다. 게이트가 없으면 선택지가 0개인 드롭다운만 남아,
 * 권한 문제인지 데이터가 없는 것인지 화면만 보고는 구분할 수 없다.
 */
it('창고 권한이 없는 사용자에게는 이유를 말한다', async () => {
  server.use(meHandler, stockHandler)
  const user = userEvent.setup()
  render(<App />)

  await submitLogin(user, 'jung.hs')

  expect(await screen.findByText(/접근할 수 있는 창고가 없다/)).toBeInTheDocument()
})
