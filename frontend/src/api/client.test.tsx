import { expect, it } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import App from '../App'
import { getCredentials } from './client'
import { server } from '../test/server'
import { USERS, unauthorized, authenticate, decodeBasic, meHandler, stockHandler } from '../test/fakeApi'
import { submitLogin } from '../test/ui'

/**
 * 자격 증명은 localStorage에 남지 않는다.
 *
 * <p>여기 담기는 것은 HTTP Basic에 그대로 쓰이는 아이디·비밀번호다 — 만료도 폐기도 없는 값이라,
 * localStorage에 들어가는 순간 XSS 한 번으로 영구 탈취된다. sessionStorage는 탭을 닫으면 사라지고
 * 다른 탭·오리진으로 새지 않아, "새로고침은 견디지만 오래 남지는 않는" 절충점이다.
 *
 * <p>지금까지 이 규칙은 client.ts 맨 위 주석으로만 지켜졌다. 주석은 새로고침이 풀린다는 버그 제보
 * 한 건이면 지워진다 — 저장 위치를 바꾸는 한 줄이 테스트를 깨도록 고정한다.
 */
it('로그인해도 localStorage에는 아무것도 들어가지 않는다', async () => {
  server.use(meHandler, stockHandler)
  const user = userEvent.setup()
  render(<App />)

  await submitLogin(user, 'park.jh')
  expect(await screen.findByText('park.jh')).toBeInTheDocument()

  // 키 이름을 짚지 않고 "비어 있다"로 본다 — 다른 키로 새로 담기 시작해도 걸려야 하기 때문이다.
  expect(localStorage.length).toBe(0)
  expect(sessionStorage.getItem('zerosum.credentials')).not.toBeNull()
})

/**
 * 비ASCII 비밀번호로도 로그인 요청이 나가는가.
 *
 * <p>인증 헤더를 btoa(`${id}:${pw}`)로 만들던 동안 한글 비밀번호는 로그인이 <b>불가능</b>했다. btoa는
 * 인자를 "한 문자 = 한 바이트"인 이진 문자열로 보므로 Latin-1 밖의 문자에서 InvalidCharacterError를
 * 던진다 — 요청이 아예 나가지 않았다는 뜻이다. 게다가 그 예외는 ApiError가 아니라 DOMException이라
 * LoginForm의 마지막 else로 빠져 "로그인 요청에 실패했다"가 떴다: 비밀번호가 원인인데 화면에는 서버
 * 장애로 보인다.
 *
 * <p>여기서 보는 것은 두 가지다. (1) 요청이 실제로 나가 로그인이 된다 — 가짜 서버는 헤더를 UTF-8로
 * 풀어 비밀번호를 맞춰보므로(fakeApi의 authenticate) 인코딩이 틀리면 401이다. (2) 헤더의 바이트가
 * 정확히 UTF-8 base64다 — 서버가 옥텟을 UTF-8로 읽는다는 것은 :web에서 실측했다(BasicAuthCharsetTest).
 */
it('비ASCII 비밀번호로도 로그인되고 Authorization 헤더가 UTF-8 base64다', async () => {
  const password = USERS['han.gm'].password
  // 헤더는 배열에 모은다 — 콜백 안에서 대입한 값은 TS의 흐름 분석이 따라가지 못해, 단일 변수로 받으면
  // 뒤에서 그 값을 쓸 때 타입이 null로 좁혀진다.
  const authHeaders: (string | null)[] = []
  server.events.on('request:start', ({ request }) => {
    if (new URL(request.url).pathname === '/api/me') {
      authHeaders.push(request.headers.get('Authorization'))
    }
  })
  server.use(meHandler, stockHandler)
  const user = userEvent.setup()
  render(<App />)

  await submitLogin(user, 'han.gm', password)

  expect(await screen.findByText('han.gm')).toBeInTheDocument()

  // 요청 횟수는 단언하지 않는다. 로그인 한 번에 /api/me는 두 번 나가고(AuthContext.login의 직접
  // 프로브 + 화면들이 쓰는 useMe 쿼리), 두 번째가 이 시점까지 시작됐는지는 타이밍에 달려 있다 —
  // toHaveLength(1)로 두었더니 6회 중 3회 실패하는 플레이크였다. 여기서 값이 있는 것은 횟수가
  // 아니라 바이트이므로, 모아진 헤더를 전부 검사한다(하나라도 틀리면 실패한다).
  expect(authHeaders.length).toBeGreaterThan(0)
  for (const header of authHeaders) {
    expect(decodeBasic(header ?? '')).toBe(`han.gm:${password}`)
  }
})

/**
 * 401 처리는 이제 client.ts의 request() 한 곳에 있다 — apiGet·apiPost·apiDelete가 그 하나를 거친다.
 * 그래도 세 경로를 따로 고정하는 이유는, 합쳐진 것이 <b>공통부뿐</b>이고 각 래퍼가 여전히 자기 요청을
 * 스스로 만들기 때문이다: 어느 하나가 request()를 거치지 않게 되는 변경은 타입으로 막히지 않는다.
 *
 * <p>합치기 전에는 같은 401 블록이 세 벌 복제돼 있었고, 실제로 apiPost·apiDelete 쪽 블록을 통째로
 * 지워도 스위트가 초록불이었다 — 앱에서 세션 만료가 걸리는 쓰기 경로(제안 승인·거부, 입고, 출고,
 * 실사, 할당 해제)는 전부 POST/DELETE인데도 그랬다. 아래 둘과 AuthContext 쪽 GET(재고 조회) 테스트가
 * 세 래퍼를 모두 지나간다.
 */
it('POST 요청에서도 401이면 로그인 화면으로 돌아가고 자격 증명이 지워진다', async () => {
  server.use(meHandler, stockHandler, http.post('*/api/receipts', () => unauthorized()))
  const user = userEvent.setup()
  render(<App />)

  await submitLogin(user, 'park.jh') // OPERATOR — 입고 폼을 볼 수 있다
  await user.click(await screen.findByRole('link', { name: '입고' }))

  await user.type(await screen.findByLabelText('발주 줄(poLineRef)'), 'PO-1')
  // 차수는 화면이 채워 주지 않는다 — 비워 두면 제출 버튼이 죽어 있어 요청 자체가 나가지 않는다.
  await user.type(screen.getByLabelText('입고 차수(receiptSeq)'), '1')
  await user.type(screen.getByLabelText('로케이션 (물리)'), 'RCV-01')
  await user.type(screen.getByLabelText('SKU'), 'SKU-1')
  await user.type(screen.getByLabelText('로트'), 'L1')
  await user.type(screen.getByLabelText('수량'), '5')
  await user.click(screen.getByRole('button', { name: '입고 등록' }))

  expect(await screen.findByRole('button', { name: '로그인' })).toBeInTheDocument()
  expect(getCredentials()).toBeNull()
  expect(sessionStorage.getItem('zerosum.credentials')).toBeNull()
})

/**
 * DELETE 경로(할당 해제, DELETE /api/allocations)의 401. 합치기 전에는 이 경로만 끝내 미검사로 남아
 * 있었다 — apiDelete의 401 블록을 지워도 아무 테스트도 빨간불이 되지 않았다는 뜻이다.
 *
 * <p>실제로 이 자리가 무너지면 보이는 모습은 이렇다: 세션이 만료된 채 할당 해제를 누르면 로그인
 * 화면으로 돌아가는 대신 행 옆에 "인증에 실패했다"만 뜨고, 죽은 자격 증명을 그대로 든 화면에 남는다.
 */
it('DELETE 요청에서도 401이면 로그인 화면으로 돌아가고 자격 증명이 지워진다', async () => {
  server.use(
    meHandler,
    stockHandler,
    http.post('*/api/allocations', ({ request }) => {
      if (!authenticate(request)) return unauthorized()
      // 할당은 성공해야 '할당 해제' 버튼이 그려진다. 줄은 서버(FEFO)가 고른 결과를 흉내 낸 최소한이다.
      return HttpResponse.json({
        allocationIds: [11],
        lines: [{ allocationId: 11, locationCode: 'ICN01-A-01', skuCode: 'SKU-1', lotNo: 'L1', qty: 5 }],
      })
    }),
    // 여기서부터 세션이 만료됐다고 본다.
    http.delete('*/api/allocations', () => unauthorized()),
  )
  const user = userEvent.setup()
  render(<App />)

  await submitLogin(user, 'park.jh') // OPERATOR — 할당·출고 폼을 볼 수 있다
  await user.click(await screen.findByRole('link', { name: '출고' }))

  // 출고 화면에는 폼이 둘이고(1단계 할당, 2단계 출고 확정) '주문번호(orderLineRef)' 라벨이 양쪽에 있다 —
  // 1단계 폼으로 범위를 좁힌다.
  const allocateForm = (await screen.findByRole('button', { name: '할당 요청' })).closest('form') as HTMLFormElement
  await user.type(within(allocateForm).getByLabelText('주문번호(orderLineRef)'), 'SO-1')
  await user.type(within(allocateForm).getByLabelText('SKU'), 'SKU-1')
  await user.type(within(allocateForm).getByLabelText('수량'), '5')
  await user.click(within(allocateForm).getByRole('button', { name: '할당 요청' }))

  await user.click(await screen.findByRole('button', { name: '할당 해제' }))

  expect(await screen.findByRole('button', { name: '로그인' })).toBeInTheDocument()
  expect(getCredentials()).toBeNull()
  expect(sessionStorage.getItem('zerosum.credentials')).toBeNull()
})

/**
 * 쓰기 요청은 Content-Type: application/json을 달고 나가야 한다.
 *
 * <p>401 블록을 request() 하나로 합치면서 이 헤더는 호출부가 넘기는 값으로 남았다 — 즉 한 줄에만
 * 걸려 있다. 그 줄이 사라져도 프론트 테스트는 전부 초록불이었다. 실물에서는 Spring이 415
 * Unsupported Media Type으로 거절하므로, 모든 쓰기 화면(입고·출고·실사·승인·할당 해제)이 한꺼번에
 * 죽는다. 가짜 서버는 본문만 보고 관대하게 넘어가니 여기서 명시적으로 본다.
 */
it('쓰기 요청은 Content-Type: application/json을 달고 나간다', async () => {
  const contentTypes: (string | null)[] = []
  server.use(
    meHandler,
    stockHandler,
    http.post('*/api/receipts', ({ request }) => {
      if (!authenticate(request)) return unauthorized()
      contentTypes.push(request.headers.get('Content-Type'))
      return HttpResponse.json({ txnId: 1 })
    }),
  )
  const user = userEvent.setup()
  render(<App />)

  await submitLogin(user, 'park.jh')
  await user.click(await screen.findByRole('link', { name: '입고' }))

  await user.type(await screen.findByLabelText('발주 줄(poLineRef)'), 'PO-CT')
  await user.type(screen.getByLabelText('입고 차수(receiptSeq)'), '1')
  await user.type(screen.getByLabelText('로케이션 (물리)'), 'RCV-01')
  await user.type(screen.getByLabelText('SKU'), 'SKU-1')
  await user.type(screen.getByLabelText('로트'), 'L1')
  await user.type(screen.getByLabelText('수량'), '5')
  await user.click(screen.getByRole('button', { name: '입고 등록' }))

  // 화면 문구가 아니라 실제로 나간 요청을 기다린다 — 이 테스트가 보는 것은 렌더 결과가 아니다.
  await waitFor(() => expect(contentTypes.length).toBeGreaterThan(0))
  for (const type of contentTypes) {
    expect(type).toMatch(/application\/json/)
  }
})
