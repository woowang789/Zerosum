import { expect, it } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { UserEvent } from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { setCredentials } from '../api/client'
import { server } from '../test/server'
import { authenticate, meHandler, unauthorized } from '../test/fakeApi'
import { ReceiptScreen } from './ReceiptScreen'

/**
 * 입고 화면이 {@code /api/me} 게이트(useWarehouseGate)를 거치는지 본다.
 *
 * <p>이 화면에는 게이트가 "me 로딩 중" 하나뿐이었다. 창고가 0개면 로딩은 이미 끝났으므로 그대로
 * 통과해, <b>선택지 0개짜리 창고 드롭다운이 달린 입고 폼</b>이 떴다 — 채워 넣고 눌러야 서버가
 * "창고가 없다"로 막는다. 권한 문제라는 것을 화면 어디서도 말해주지 않는다.
 *
 * <p>공용 게이트가 있다는 것과 <b>이 화면이 그것을 거친다</b>는 것은 다른 사실이다. 다섯 화면이
 * 빠뜨렸던 것이 후자이므로, 화면마다 이 단언을 하나씩 둔다. 게이트 자체의 세 상태
 * (로딩·실패·창고 0개) 구분은 StockScreen.test.tsx가 본다.
 *
 * <p>입고 폼의 나머지(미리보기, 상대 줄)는 여전히 테스트가 없다. 폼이 서버 규칙을 다시 계산하지
 * 않기 때문이다(README의 프론트엔드 테스트 범위). 예외가 입고 차수인데, 그건 화면이 값을 채워 두는
 * 순간 서버가 볼 수 없는 결함이 되므로 아래에서 따로 본다.
 */
it('창고 권한이 없는 사용자에게는 이유를 말한다', async () => {
  server.use(meHandler)
  // 화면만 떼어 그리므로 AuthProvider를 거치지 않는다 — 자격 증명은 직접 심는다.
  // jung.hs는 가공 픽스처다: OPERATOR라 권한 게이트는 통과하지만 창고가 하나도 없다.
  setCredentials({ username: 'jung.hs', password: 'zerosum' })
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
  })

  render(
    <QueryClientProvider client={queryClient}>
      <ReceiptScreen />
    </QueryClientProvider>,
  )

  expect(await screen.findByText(/접근할 수 있는 창고가 없다/)).toBeInTheDocument()
  expect(screen.queryByLabelText('발주 줄(poLineRef)')).not.toBeInTheDocument()
})

/**
 * 입고 차수(receiptSeq)는 화면이 대신 정해 줄 수 없는 값이다.
 *
 * <p>멱등 키가 {@code receipt:{poLineRef}:{receiptSeq}}다. 화면이 차수를 1로 채워 두면, 같은 발주 줄로
 * <b>물건이 한 번 더 들어온</b> 두 번째 입고를 등록할 때 작업자가 고른 적 없는 1이 그대로 다시 나간다.
 * 서버는 그것을 재시도로 보고 원장·잔액을 건드리지 않은 채 첫 거래 id를 돌려주며, 화면은 똑같이
 * "기록됐다 — 거래 #N"을 띄운다. 파렛트는 창고에 있는데 원장에는 없다.
 *
 * <p>정합 검증으로는 못 잡는다 — 기록 자체가 생기지 않았으므로 원장과 잔액은 완벽히 일치한다. 그래서
 * 이 결함은 화면에서만 막을 수 있고, 여기서만 테스트할 수 있다.
 */

/** 화면만 떼어 그린다 — AuthProvider를 거치지 않으므로 자격 증명은 직접 심는다. 폼이 그려지면 돌려준다. */
async function renderReceiptForm(): Promise<UserEvent> {
  // 입고 폼은 OPERATOR 이상에게만 보인다 — application.yml 기준 OPERATOR이고 창고가 있는 park.jh.
  setCredentials({ username: 'park.jh', password: 'zerosum' })
  const user = userEvent.setup()
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
  })
  render(
    <QueryClientProvider client={queryClient}>
      <ReceiptScreen />
    </QueryClientProvider>,
  )
  // /api/me가 오기 전에는 게이트의 "불러오는 중…"뿐이다 — 폼이 뜰 때까지 기다린다.
  await screen.findByLabelText('발주 줄(poLineRef)')
  return user
}

/**
 * 차수를 뺀 나머지 칸을 전부 채운다.
 *
 * <p>차수만 비워 두는 것이 핵심이다 — 나머지가 다 찬 상태에서도 버튼이 죽어 있어야, 막고 있는 것이
 * 차수라는 것이 드러난다.
 */
async function fillExceptSeq(user: UserEvent, poLineRef: string): Promise<void> {
  await user.type(screen.getByLabelText('발주 줄(poLineRef)'), poLineRef)
  await user.type(screen.getByLabelText('로케이션 (물리)'), 'RCV-01')
  await user.type(screen.getByLabelText('SKU'), 'SKU-200002')
  await user.type(screen.getByLabelText('로트'), 'L20260910-B')
  await user.type(screen.getByLabelText('수량'), '5')
}

/** 제출 버튼. 요청이 나가는 동안 이름이 '등록 중…'으로 바뀌므로 매번 다시 집는다. */
function submitButton(): HTMLElement {
  return screen.getByRole('button', { name: '입고 등록' })
}

function seqField(): HTMLElement {
  return screen.getByLabelText('입고 차수(receiptSeq)')
}

/**
 * 처음 그려진 폼의 차수 칸은 비어 있고, 나머지를 다 채워도 차수를 적기 전에는 제출할 수 없다.
 *
 * <p>차수가 1로 채워져 있으면 작업자는 그 값을 <b>보지 않는다</b> — 자기가 고른 값이 아니기 때문이다.
 * 빈 칸은 다르다. 적어야 버튼이 살아나므로, 이 입고가 몇 번째인지 한 번은 생각하게 된다.
 */
it('차수 칸은 처음부터 비어 있고, 차수를 적기 전에는 제출할 수 없다', async () => {
  server.use(meHandler)

  const user = await renderReceiptForm()

  expect(seqField()).toHaveValue(null)
  expect(submitButton()).toBeDisabled()

  // 차수 말고 전부 채워도 여전히 막혀 있다 — 막는 것이 차수라는 뜻이다.
  await fillExceptSeq(user, 'PO-1001')
  expect(seqField()).toHaveValue(null)
  expect(submitButton()).toBeDisabled()

  // 작업자가 직접 적고 나서야 열린다.
  await user.type(seqField(), '2')
  expect(submitButton()).toBeEnabled()
})

/**
 * 입고에 성공한 뒤에도 차수 칸은 비어 있다 — 다음 입고의 차수는 다시 작업자가 적는다.
 *
 * <p>성공 처리가 폼을 비우면서 차수만 1로 되돌려 놓으면, 같은 발주 줄의 두 번째 파렛트를 이어서
 * 등록하는 가장 흔한 흐름이 정확히 그 함정으로 들어간다: 나머지만 채우면 버튼이 살아나고, 나간 차수는
 * 방금 쓴 1이다.
 */
it('입고에 성공한 뒤에도 차수는 비어 있어 다음 입고를 그대로 제출할 수 없다', async () => {
  server.use(
    meHandler,
    http.post('*/api/receipts', ({ request }) => {
      if (!authenticate(request)) return unauthorized()
      return HttpResponse.json({ txnId: 301 })
    }),
  )

  const user = await renderReceiptForm()

  await fillExceptSeq(user, 'PO-1001')
  await user.type(seqField(), '1')
  await user.click(submitButton())

  expect(await screen.findByText('기록됐다 — 거래 #301')).toBeInTheDocument()
  expect(seqField()).toHaveValue(null)

  // 같은 발주 줄로 두 번째 파렛트가 들어왔다. 나머지를 다 채워도 차수를 적기 전에는 나갈 수 없다.
  await fillExceptSeq(user, 'PO-1001')
  expect(seqField()).toHaveValue(null)
  expect(submitButton()).toBeDisabled()
})

/**
 * 멱등 안내가 재시도와 두 번째 입고를 구분해서 말한다.
 *
 * <p>"같은 요청을 두 번 보내도 안전하다"만 적혀 있으면 사실이지만 절반이다 — 안전한 것은 <b>같은
 * 입고</b>를 다시 보내는 경우뿐이고, 물건이 한 번 더 들어온 경우에 차수를 그대로 두면 재고가 늘지
 * 않는다. 화면에 뜨는 결과는 두 경우가 똑같아서, 안내문이 이 구분을 말해주지 않으면 작업자가
 * 스스로 알아낼 방법이 없다.
 *
 * <p>문구 전체가 아니라 이 두 가지 뜻만 붙든다 — 표현은 다듬어도 되지만 구분은 남아야 한다.
 */
it('멱등 안내는 재시도는 안전하지만 두 번째 입고는 차수를 올리라고 말한다', async () => {
  server.use(meHandler)

  await renderReceiptForm()

  const note = screen.getByText(/멱등 키:/)
  expect(note).toHaveTextContent(/안전하다/)
  expect(note).toHaveTextContent(/차수를 올려야 한다/)
})
