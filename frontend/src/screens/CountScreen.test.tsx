import { expect, it } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { UserEvent } from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { setCredentials } from '../api/client'
import { server } from '../test/server'
import { authenticate, meHandler, stockHandler, unauthorized } from '../test/fakeApi'
import { CountScreen } from './CountScreen'

/**
 * 실사 화면은 상태 기계다 — 열림 → (제출) → 검토 또는 확정 → (포기/정정) → 끝. 여기서 고정하는 것은 그
 * 전이가 <b>서버의 답에 따라</b> 갈리는지, 그리고 끝난 뒤 처음으로 돌아갈 수 있는지 둘이다.
 *
 * <p>허용 오차가 얼마인지, 이 차이가 확정 가능한지는 서버 설정값이고 서버의 판정이다(화면에는 수치조차
 * 없다). 그래서 이 테스트들은 오차 계산을 검증하지 않는다 — <b>서버가 준 답을 화면이 올바른 단계로
 * 번역하는지</b>만 본다.
 *
 * <p>로케이션은 fakeApi의 stockHandler가 돌려주는 {@code ICN01-A-01}로 맞춘다 — 실사 줄은 창고 전체 재고를
 * 받아 로케이션으로 걸러 만들어지므로(GET /api/stock에 location 필터가 없다) 이 둘이 어긋나면 입력 줄이
 * 아예 생기지 않는다.
 */

const WAREHOUSE = 'ICN01' // choi.dw(SUPERVISOR)의 첫 창고 — StartForm이 기본으로 고르는 값과 같아야 한다.
const LOCATION = 'ICN01-A-01'
const SYSTEM_QTY = 40 // stockHandler가 이 로케이션에 내려주는 전산 수량

interface StartBody {
  warehouseCode: string
  locationCode: string
}

interface SubmitBody {
  lines: { skuCode: string; lotNo: string; countedQty: number }[]
}

/** 화면만 떼어 그린다 — AuthProvider를 거치지 않으므로 자격 증명은 직접 심는다. 기다리지 않는다. */
function renderAs(username: string): UserEvent {
  setCredentials({ username, password: 'zerosum' })
  const user = userEvent.setup()
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
  })
  render(
    <QueryClientProvider client={queryClient}>
      <CountScreen />
    </QueryClientProvider>,
  )
  return user
}

async function renderCountScreen(): Promise<UserEvent> {
  // 정정 승인은 SUPERVISOR만 할 수 있으므로 application.yml 기준 SUPERVISOR인 choi.dw로 로그인한다.
  const user = renderAs('choi.dw')
  // /api/me가 오기 전에는 "불러오는 중…"뿐이다 — 시작 폼이 그려질 때까지 기다린다.
  await screen.findByRole('button', { name: '실사 세션 시작' })
  return user
}

/** 시작 폼에 로케이션을 넣고 세션을 시작한 뒤, 전산 수량이 실사 줄로 들어올 때까지 기다린다. */
async function startSession(user: UserEvent): Promise<void> {
  await user.type(screen.getByLabelText('로케이션'), LOCATION)
  await user.click(screen.getByRole('button', { name: '실사 세션 시작' }))
  // 전산 수량이 실사 줄(수량 입력칸)로 들어오기 전에 입력을 시작하면, 줄을 만드는 useEffect가 입력값을
  // 덮어쓰는 시점과 겹친다.
  await screen.findByRole('spinbutton')
}

/** 실사 수량을 고쳐 제출한다. */
async function submitCount(user: UserEvent, countedQty: number): Promise<void> {
  const input = screen.getByRole('spinbutton')
  await user.clear(input)
  await user.type(input, String(countedQty))
  await user.click(screen.getByRole('button', { name: '실사 제출' }))
}

/** POST /api/counts — 시작할 때마다 다음 세션 id를 준다(같은 로케이션이어도 새 세션이다). */
function startHandler(sessionIds: number[], captured: StartBody[]) {
  const queue = [...sessionIds]
  return http.post('*/api/counts', async ({ request }) => {
    if (!authenticate(request)) return unauthorized()
    const body = (await request.json()) as StartBody
    captured.push(body)
    if (body.warehouseCode !== WAREHOUSE || body.locationCode !== LOCATION) {
      // 실제 서버는 권한 없는 창고를 403으로 막는다. 아무 요청에나 세션을 열어주는 mock이면 화면이
      // 창고·로케이션을 잘못 보내도 통과한다.
      return HttpResponse.json({ code: 'FORBIDDEN', message: '권한이 없다' }, { status: 403 })
    }
    const sessionId = queue.shift()
    if (sessionId === undefined) {
      throw new Error('예상보다 많은 실사 시작 요청이 왔다')
    }
    return HttpResponse.json({ sessionId })
  })
}

/**
 * POST /api/counts/{id}/submit — 정해진 세션 id로 온 제출만 받는다.
 *
 * <p>{@code {id}}를 검사하지 않으면 화면이 엉뚱한 세션에 제출해도 초록불이다. 실사에서 그건 다른
 * 로케이션의 실사 결과를 이 세션의 것으로 확정하는 것과 같다.
 */
function submitHandler(sessionId: number, outcome: object, captured: SubmitBody[]) {
  return http.post('*/api/counts/:id/submit', async ({ request, params }) => {
    if (!authenticate(request)) return unauthorized()
    if (params.id !== String(sessionId)) {
      return HttpResponse.json(
        { code: 'COUNT_SESSION_NOT_OPEN', message: `세션 ${String(params.id)}는 열려 있지 않다` },
        { status: 409 },
      )
    }
    captured.push((await request.json()) as SubmitBody)
    return HttpResponse.json(outcome)
  })
}

/**
 * 서버가 ReviewRequired({})를 주면 정정 단계로 간다.
 *
 * <p>화면은 {@code 'resolutionTxnId' in outcome}으로 갈린다(CountScreen의 isConfirmed) — Confirmed는 값이
 * null이어도 키를 내려주고 ReviewRequired는 컴포넌트가 없는 빈 객체라서다. 이 판정이 뒤집히면
 * <b>정정해야 하는 사람에게 "완료"가 보인다</b>: 허용 오차를 넘은 차이가 승인 없이 묻히고, 로케이션 잠금은
 * 풀리지 않은 채 남는다. 두 방향을 각각 고정하고, 서로의 부정 단언("확정됐다"가 안 보인다 / "검토"가 안
 * 보인다)을 상대 테스트가 뒷받침한다.
 */
it('제출 결과가 빈 객체(REVIEW)면 정정 단계가 뜬다', async () => {
  const SESSION_ID = 1
  const startBodies: StartBody[] = []
  const submitBodies: SubmitBody[] = []

  server.use(
    meHandler,
    stockHandler,
    startHandler([SESSION_ID], startBodies),
    // ReviewRequired는 컴포넌트가 없는 record라 Jackson이 {}로 직렬화한다 — resolutionTxnId 키 자체가 없다.
    submitHandler(SESSION_ID, {}, submitBodies),
  )

  const user = await renderCountScreen()
  await startSession(user)
  await submitCount(user, 10) // 전산 40 대비 -30 — 허용 오차를 넘었다고 서버가 판정한 상황

  expect(await screen.findByText(/허용 오차를 넘는 차이가 있어/)).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '정정 승인' })).toBeInTheDocument()
  // 확정 문구는 CONFIRMED 테스트에서 실제로 뜨는 것을 확인했으므로, 여기서의 부정 단언은 빈 단언이 아니다.
  expect(screen.queryByText(/실사가 확정됐다/)).not.toBeInTheDocument()

  expect(startBodies).toEqual([{ warehouseCode: WAREHOUSE, locationCode: LOCATION }])
  expect(submitBodies).toEqual([{ lines: [{ skuCode: 'SKU-200002', lotNo: 'L20260910-B', countedQty: 10 }] }])
})

/**
 * 서버가 Confirmed({resolutionTxnId})를 주면 바로 완료로 간다.
 *
 * <p>위 테스트의 반대 방향이다. 여기서 정정 단계가 뜬다면, 이미 확정된(그리고 잠금이 풀린) 세션에
 * SUPERVISOR가 정정 승인을 누르게 되고 그 요청은 서버에서 거절된다 — 사용자는 무엇이 잘못됐는지 알 수 없다.
 */
it('제출 결과에 resolutionTxnId가 있으면(CONFIRMED) 정정 없이 완료된다', async () => {
  const SESSION_ID = 1
  const startBodies: StartBody[] = []
  const submitBodies: SubmitBody[] = []

  server.use(
    meHandler,
    stockHandler,
    startHandler([SESSION_ID], startBodies),
    submitHandler(SESSION_ID, { resolutionTxnId: 900 }, submitBodies),
  )

  const user = await renderCountScreen()
  await startSession(user)
  await submitCount(user, SYSTEM_QTY - 2) // 오차 안의 차이 — 서버가 바로 정정 거래를 내준 상황

  expect(await screen.findByText(/정정 거래 #900/)).toBeInTheDocument()
  // 검토 문구는 REVIEW 테스트에서 실제로 뜨는 것을 확인했으므로, 이 부정 단언도 빈 단언이 아니다.
  expect(screen.queryByText(/허용 오차를 넘는 차이가 있어/)).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '정정 승인' })).not.toBeInTheDocument()

  expect(submitBodies).toEqual([
    { lines: [{ skuCode: 'SKU-200002', lotNo: 'L20260910-B', countedQty: SYSTEM_QTY - 2 }] },
  ])
})

/**
 * 차이가 없어 정정 거래를 만들지 않은 확정({@code resolutionTxnId: null})도 확정이다.
 *
 * <p>화면은 {@code 'resolutionTxnId' in outcome}으로 판정한다 — 값이 아니라 <b>필드의 존재</b>를 본다.
 * 그 형태여야 하는 이유가 바로 이 경우다. 실사 결과가 전산 수량과 정확히 같으면 서버는 정정 거래를
 * 만들지 않고 {@code Confirmed(null)}을 내려주는데(`CountControllerTest`가 REVIEW는 필드 자체가 없음을,
 * CONFIRMED는 있음을 못 박는다), 판정을 {@code outcome.resolutionTxnId != null}로 바꾸면 그 확정이
 * REVIEW로 새어 <b>이미 잠금이 풀린 세션에 SUPERVISOR가 정정 승인을 누르게 된다</b>(서버는 거절한다).
 *
 * <p>위 두 테스트는 이 구분을 못 잡는다 — 한쪽은 필드가 없고 한쪽은 값이 있어서, {@code in}과 값 검사가
 * 같은 답을 낸다. 값이 null인 이 세 번째 모양이 있어야 둘이 갈린다.
 */
it('차이가 없는 확정(resolutionTxnId가 null)도 정정 단계로 새지 않는다', async () => {
  const SESSION_ID = 1
  const submitBodies: SubmitBody[] = []

  server.use(
    meHandler,
    stockHandler,
    startHandler([SESSION_ID], []),
    submitHandler(SESSION_ID, { resolutionTxnId: null }, submitBodies),
  )

  const user = await renderCountScreen()
  await startSession(user)
  await submitCount(user, SYSTEM_QTY) // 전산 수량과 같다 — 정정할 것이 없다

  expect(await screen.findByText(/차이가 없었다/)).toBeInTheDocument()
  expect(screen.queryByText(/허용 오차를 넘는 차이가 있어/)).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '정정 승인' })).not.toBeInTheDocument()
  expect(submitBodies).toEqual([
    { lines: [{ skuCode: 'SKU-200002', lotNo: 'L20260910-B', countedQty: SYSTEM_QTY }] },
  ])
})

/**
 * 끝낸 뒤 같은 로케이션을 다시 실사할 수 있다 — 방금 코어에서 고친 결함의 화면 쪽 증거다.
 *
 * <p>순환 실사는 정기 업무라 한 로케이션을 몇 번이고 다시 센다. 예전에는 시작이 (창고, 로케이션)에서 멱등
 * 키를 파생해서, 두 번째 시작이 <b>첫 번째 세션의 재생</b>이 됐다 — 화면에는 이미 닫힌 세션이 떠서 제출도
 * 포기도 서버에서 거절됐다. 코어 쪽은 RecountSameLocationTest가 지킨다. 화면 쪽에서 필요한 것은 그다음
 * 절반이다: 재시작 때 <b>서버에 다시 물어보고</b>, 서버가 준 <b>새 id로</b> 이어서 일하는가.
 *
 * <p>그래서 가짜 서버는 첫 시작에 1, 재시작에 2를 주고, 제출은 2번 세션에서만 받는다. 화면이 옛 세션을
 * 들고 있으면 제출이 409로 거절되고 완료 문구가 뜨지 않는다.
 */
it('포기한 뒤 같은 로케이션을 다시 시작하면 새 세션 id로 이어진다', async () => {
  const startBodies: StartBody[] = []
  const submitBodies: SubmitBody[] = []
  const abandonedIds: string[] = []

  server.use(
    meHandler,
    stockHandler,
    startHandler([1, 2], startBodies),
    http.post('*/api/counts/:id/abandon', ({ request, params }) => {
      if (!authenticate(request)) return unauthorized()
      abandonedIds.push(String(params.id))
      return new HttpResponse(null, { status: 204 })
    }),
    // 2번 세션만 받는다 — 1번으로 오면 이미 닫힌 세션이므로 실제 서버도 거절한다.
    submitHandler(2, { resolutionTxnId: 901 }, submitBodies),
  )

  const user = await renderCountScreen()
  await startSession(user)
  expect(screen.getByText('#1')).toBeInTheDocument()

  await user.click(screen.getByRole('button', { name: '실사 포기' }))
  expect(await screen.findByText(/실사를 포기했다/)).toBeInTheDocument()

  await user.click(screen.getByRole('button', { name: '새 실사 시작' }))
  await startSession(user)

  expect(await screen.findByText('#2')).toBeInTheDocument()

  // 세션 번호만 바뀌고 실제 작업이 옛 세션으로 나가면 의미가 없다 — 제출까지 2번으로 가는지 본다.
  await submitCount(user, SYSTEM_QTY)
  expect(await screen.findByText(/정정 거래 #901/)).toBeInTheDocument()

  // 같은 창고·로케이션으로 두 번 시작했다 — 두 번째가 첫 번째의 재생이 아니라는 것이 이 화면의 주장이다.
  expect(startBodies).toEqual([
    { warehouseCode: WAREHOUSE, locationCode: LOCATION },
    { warehouseCode: WAREHOUSE, locationCode: LOCATION },
  ])
  expect(abandonedIds).toEqual(['1'])
  expect(submitBodies).toEqual([{ lines: [{ skuCode: 'SKU-200002', lotNo: 'L20260910-B', countedQty: SYSTEM_QTY }] }])
})

/**
 * 정정 승인(POST /api/counts/{id}/resolve)이 실제로 나가고, 화면이 그 결과로 닫힌다.
 *
 * <p>이 화면의 유일한 SUPERVISOR 전용 쓰기인데 위 테스트들은 버튼이 <b>떠 있는지</b>까지만 본다 —
 * 누르면 무엇이 나가는지, 응답을 어떻게 반영하는지는 아무도 지키지 않았다. 실사 정정은 원장에
 * 조정 거래를 남기는 일이라(허용 오차를 넘은 차이를 사람이 승인해 확정한다) 조용히 안 나가거나
 * 엉뚱한 세션으로 나가면 로케이션이 잠긴 채 남는다.
 */
it('정정 승인은 그 세션으로 나가고 결과가 화면에 반영된다', async () => {
  const SESSION_ID = 7
  const resolvedSessions: string[] = []

  server.use(
    meHandler,
    stockHandler,
    startHandler([SESSION_ID], []),
    submitHandler(SESSION_ID, {}, []), // 빈 객체 = REVIEW, 정정 승인 버튼이 뜬다
    http.post('*/api/counts/:id/resolve', ({ request, params }) => {
      if (!authenticate(request)) return unauthorized()
      resolvedSessions.push(String(params.id))
      return HttpResponse.json({ resolutionTxnId: 4242 })
    }),
  )

  const user = await renderCountScreen()
  await startSession(user)
  await submitCount(user, SYSTEM_QTY - 20) // 오차를 넘는 차이 → REVIEW

  await user.click(await screen.findByRole('button', { name: '정정 승인' }))

  expect(await screen.findByText(/정정 거래 #4242/)).toBeInTheDocument()
  // 시작 때 서버가 준 세션으로 나가야 한다 — 다른 세션으로 나가면 서버가 거절하고 이 로케이션은 잠긴 채 남는다.
  expect(resolvedSessions).toEqual([String(SESSION_ID)])
  expect(screen.queryByRole('button', { name: '정정 승인' })).not.toBeInTheDocument()
})

/**
 * 실사 입력 줄은 이 세션의 로케이션 재고만으로 만들어진다.
 *
 * <p>{@code GET /api/stock}에는 로케이션 필터가 없어(SKU만 있다) 화면이 창고 전체를 받아 세션 로케이션으로
 * 거른다. 그 필터가 무너지면 <b>다른 로케이션의 재고가 이 세션의 실사 대상이 된다</b> — 제출 본문은
 * {@code (skuCode, lotNo, countedQty)}뿐이고 로케이션은 서버가 세션에서 가져오므로, 옆칸 재고를 이 칸에서
 * 센 것으로 굳혀 버린다. 그 차이는 정정 거래로 원장에 남는다.
 *
 * <p>기존 테스트들은 이 필터를 반증할 수 없다 — fakeApi의 stockHandler가 한 로케이션 한 줄만 주므로
 * 필터를 통째로 지워도 결과가 같다. 그래서 여기서만 로케이션 둘짜리 재고를 세운다.
 */
it('실사 줄은 이 세션의 로케이션 재고만으로 만들어진다', async () => {
  const SESSION_ID = 1
  const submitBodies: SubmitBody[] = []

  server.use(
    meHandler,
    // 같은 창고의 다른 로케이션(ICN01-B-02)에 다른 SKU가 있다 — 이 세션과는 무관한 재고다.
    http.get('*/api/stock', ({ request }) => {
      if (!authenticate(request)) return unauthorized()
      return HttpResponse.json([
        {
          warehouseCode: WAREHOUSE, skuCode: 'SKU-200002', skuName: '우유 1L', lotNo: 'L20260910-B',
          expiryDate: '2026-09-30', locationCode: LOCATION, locationType: 'STORAGE',
          onHandQty: SYSTEM_QTY, allocatedQty: 0, availableQty: SYSTEM_QTY, inCount: false,
        },
        {
          warehouseCode: WAREHOUSE, skuCode: 'SKU-999999', skuName: '옆칸 물건', lotNo: 'L-OTHER',
          expiryDate: null, locationCode: 'ICN01-B-02', locationType: 'STORAGE',
          onHandQty: 7, allocatedQty: 0, availableQty: 7, inCount: false,
        },
      ])
    }),
    startHandler([SESSION_ID], []),
    submitHandler(SESSION_ID, { resolutionTxnId: 1 }, submitBodies),
  )

  const user = await renderCountScreen()
  await startSession(user)

  // 옆칸 SKU는 입력 줄로 그려지지도 않는다.
  expect(await screen.findByText('SKU-200002')).toBeInTheDocument()
  expect(screen.queryByText('SKU-999999')).not.toBeInTheDocument()

  await submitCount(user, SYSTEM_QTY)

  // 제출 본문에도 이 로케이션 줄만 실린다 — 본문에는 로케이션이 없어 서버가 걸러줄 수 없다.
  expect(submitBodies).toEqual([
    { lines: [{ skuCode: 'SKU-200002', lotNo: 'L20260910-B', countedQty: SYSTEM_QTY }] },
  ])
})

/**
 * 새 실사는 <b>자기 세션의</b> 전산 수량으로 시작한다 — 앞 세션의 캐시가 아니라.
 *
 * <p>재고 조회 키가 {@code ['count-stock', 창고코드]}였다. 같은 창고에서 새 실사를 시작하면 react-query가
 * 앞 세션의 응답을 캐시에서 즉시 돌려주고(기본 gcTime 5분), 줄을 만드는 useEffect는 <b>처음 본 데이터</b>로
 * 한 번만 채우므로 그 낡은 수량이 그대로 굳는다. 뒤늦게 도착하는 새 응답은 {@code lines}가 이미 차 있어
 * 반영되지 않는다.
 *
 * <p>실사 수량은 전산 수량으로 미리 채워지므로 차이 칸은 0으로 그려진다 — 화면에는 단서가 없다. 그래서
 * 가장 흔한 동작("숫자가 맞다, 그대로 제출")이 곧 <b>거짓 실사</b>가 된다: 서버는 세션 시작 때 자기가
 * 찍은 {@code system_qty}와 대조하므로 차이가 생기고, 허용 오차 안이면 같은 트랜잭션에서 조정 거래가
 * 바로 나간다. 원장에 남는 것은 사람이 세지도 않은 수량이다.
 *
 * <p>그래서 단언을 화면 표시에서 끝내지 않고 <b>제출 본문</b>까지 본다 — 원장에 무엇이 남는지를 정하는
 * 것은 그 숫자다. 기존 재시작 테스트는 이걸 잡지 못한다: 가짜 서버가 두 번 다 같은 수량을 주므로
 * 캐시를 그대로 써도 결과가 같다.
 */
it('같은 창고에서 새 실사를 시작하면 전산 수량을 그 세션 기준으로 다시 읽는다', async () => {
  const FIRST_QTY = 40
  const SECOND_QTY = 12 // 두 세션 사이에 출고가 나가 재고가 줄어든 상황
  const submitBodies: SubmitBody[] = []
  let currentQty = FIRST_QTY

  server.use(
    meHandler,
    // 요청 횟수가 아니라 "지금 서버의 재고"로 답한다 — 화면이 배경 재조회를 몇 번 하든 결과가 흔들리지 않는다.
    http.get('*/api/stock', ({ request }) => {
      if (!authenticate(request)) return unauthorized()
      return HttpResponse.json([
        {
          warehouseCode: WAREHOUSE, skuCode: 'SKU-200002', skuName: '콜드브루 원액 1L', lotNo: 'L20260910-B',
          expiryDate: '2026-09-30', locationCode: LOCATION, locationType: 'STORAGE',
          onHandQty: currentQty, allocatedQty: 0, availableQty: currentQty, inCount: false,
        },
      ])
    }),
    startHandler([1, 2], []),
    http.post('*/api/counts/:id/abandon', ({ request }) => {
      if (!authenticate(request)) return unauthorized()
      return new HttpResponse(null, { status: 204 })
    }),
    submitHandler(2, { resolutionTxnId: 903 }, submitBodies),
  )

  const user = await renderCountScreen()
  await startSession(user)
  expect(screen.getByRole('spinbutton')).toHaveValue(FIRST_QTY)

  await user.click(screen.getByRole('button', { name: '실사 포기' }))
  expect(await screen.findByText(/실사를 포기했다/)).toBeInTheDocument()

  currentQty = SECOND_QTY

  await user.click(screen.getByRole('button', { name: '새 실사 시작' }))
  await startSession(user)

  // 캐시로 채워졌다면 여기가 40이고, 차이 칸은 그래도 0이라 화면만 보고는 알 수 없다.
  expect(await screen.findByRole('spinbutton')).toHaveValue(SECOND_QTY)

  // 손대지 않고 그대로 제출한다 — 사람이 "맞다"고 보고 넘기는 바로 그 동작이다.
  await user.click(screen.getByRole('button', { name: '실사 제출' }))
  expect(await screen.findByText(/정정 거래 #903/)).toBeInTheDocument()
  expect(submitBodies).toEqual([
    { lines: [{ skuCode: 'SKU-200002', lotNo: 'L20260910-B', countedQty: SECOND_QTY }] },
  ])
})

/**
 * 창고 권한이 하나도 없는 사용자에게는 이유를 말한다.
 *
 * <p>실사 화면에는 게이트가 "me 로딩 중" 하나뿐이었다. 창고가 0개면 로딩은 끝났으므로 그대로 통과해,
 * 선택지 0개짜리 드롭다운과 로케이션 입력칸만 남은 시작 폼이 떴다 — 눌러 봐야 서버가 막는다.
 * 재고·이슈 화면이 먼저 겪고 고친 것과 같은 결함이고, 이제 게이트는 {@code useWarehouseGate} 한 벌이다.
 *
 * <p>화면마다 이 단언을 두는 이유는, 공용 게이트가 있다는 것과 <b>이 화면이 그것을 거친다</b>는 것이
 * 서로 다른 사실이기 때문이다. 다섯 화면이 빠뜨렸던 것이 바로 후자다.
 */
it('창고 권한이 없는 사용자에게는 이유를 말한다', async () => {
  server.use(meHandler)

  renderAs('jung.hs') // 가공 픽스처 — OPERATOR지만 창고 권한이 없다

  expect(await screen.findByText(/접근할 수 있는 창고가 없다/)).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '실사 세션 시작' })).not.toBeInTheDocument()
})
