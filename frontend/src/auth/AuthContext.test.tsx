import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http } from 'msw'
import App from '../App'
import { getCredentials } from '../api/client'
import { server } from '../test/server'
import { UNAUTHORIZED, meHandler, stockHandler } from '../test/fakeApi'
import { submitLogin } from '../test/ui'

/**
 * 인증 상태가 바뀌는 순간(로그인·로그아웃·401)에 react-query 캐시가 함께 어떻게 움직이는지를 본다.
 *
 * <p>이 프론트에는 다시 계산하는 규칙이 없다 — FEFO 로트 선택도, 실사 허용오차도, 근거 유효성도 전부
 * 서버가 정해 내려준다. 그래서 화면에서 틀릴 수 있는 것은 "값"이 아니라 <b>상태</b>다. 누구의 캐시가
 * 언제 지워지고 어떤 쿼리가 살아남느냐 — 타입 검사(tsc --noEmit)가 볼 수 없고, 응답 모양을 지키는
 * 백엔드 테스트도 볼 수 없는 자리다. 아래 세 가지는 전부 그 자리에서 실제로 났거나 날 수 있었던 일이다.
 */
describe('인증 상태 전이와 캐시', () => {
  it('로그아웃 뒤 다른 사용자로 다시 로그인해도 새 화면의 쿼리가 pending에 갇히지 않는다', async () => {
    // 실제로 났던 버그의 재현이다. queryClient.clear()를 렌더 이펙트에서 호출하던 시절, 새 사용자로
    // 바뀐 뒤 Shell이 먼저 렌더되어 자식 화면이 쿼리를 시작하고 <b>그다음에</b> clear()가 돌았다.
    // 그러면 방금 시작된 쿼리가 캐시에서 제거되면서 취소되고, 구독은 남은 채 응답을 영영 받지 못한다.
    // 타입은 멀쩡했고 화면도 그려졌다 — "불러오는 중…"에서 멈춰 있을 뿐이었다.
    server.use(meHandler, stockHandler)
    const user = userEvent.setup()
    render(<App />)

    await submitLogin(user, 'park.jh')
    expect(await screen.findByText('ICN01-A-01')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '로그아웃' }))
    await submitLogin(user, 'choi.dw')

    // 새 사용자의 화면까지는 늘 그려졌다. 문제는 그 화면이 시작한 쿼리였다.
    expect(await screen.findByText('choi.dw')).toBeInTheDocument()
    expect(await screen.findByText('ICN01-A-01')).toBeInTheDocument()
    expect(screen.queryByText('불러오는 중…')).not.toBeInTheDocument()
  })

  it('사용자를 바꾸면 앞 사용자의 역할·창고가 새 화면에 남지 않는다', async () => {
    // useMe()는 staleTime: Infinity다 — 세션 동안 역할·창고가 바뀌지 않는다는 가정인데, 사용자가
    // 바뀌는 순간 그 가정이 깨진다. 캐시를 비우지 않으면 ['me']는 영원히 낡지 않으므로 앞 사람의
    // 권한으로 그려진 화면이 그대로 남는다. 권한 판정 자체는 서버가 다시 하므로 데이터가 새지는
    // 않지만, 없는 창고를 고르게 하고 누를 수 없는 버튼을 보여주는 화면이 된다.
    //
    // 창고 쪽은 입고(ReceiptScreen)의 <select>가 실제로 그려질 때만 의미가 있다 — 이 화면은
    // canWrite(OPERATOR 이상)가 아니면 폼 자체를 감추고 <option>을 하나도 그리지 않는다. 그래서
    // 창고 확인은 canWrite가 둘 다 참인 choi.dw→park.jh(둘 다 폼이 보인다)로, 역할 확인은 그 뒤
    // park.jh→lee.sm(VIEWER라 폼이 사라져야 한다)으로 나눠서 본다.
    server.use(meHandler, stockHandler)
    const user = userEvent.setup()
    render(<App />)

    await submitLogin(user, 'choi.dw') // SUPERVISOR · ICN01·YIT01
    await user.click(await screen.findByRole('link', { name: '입고' }))
    expect(await screen.findByRole('option', { name: 'YIT01' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'ICN01' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '로그아웃' }))
    await submitLogin(user, 'park.jh') // OPERATOR · ICN01 (canWrite라 폼은 그대로 보인다)

    await user.click(await screen.findByRole('link', { name: '입고' }))
    expect(await screen.findByRole('option', { name: 'ICN01' })).toBeInTheDocument()
    // 앞 사용자(choi.dw)만 갖고 있던 YIT01이 선택지로 남아 있으면 안 된다.
    expect(screen.queryByRole('option', { name: 'YIT01' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '로그아웃' }))
    await submitLogin(user, 'lee.sm') // VIEWER · YIT01

    await user.click(await screen.findByRole('link', { name: '입고' }))
    // 역할이 새로 읽혔다면 VIEWER에게는 폼 대신 안내만 보인다 — park.jh(OPERATOR)의 역할이 캐시에
    // 남아 있었다면 이 사용자도 여전히 폼을 볼 수 있었을 것이다.
    expect(await screen.findByText(/권한 없음/)).toBeInTheDocument()
  })

  it('로그인 뒤 어느 요청에서든 401이 나면 로그인 화면으로 돌아가고 자격 증명이 지워진다', async () => {
    // 세션 만료·비밀번호 변경처럼 로그인한 뒤에 자격 증명이 무효가 되는 경우다. 이때 화면에 그대로
    // 머무르면 사용자는 "왜 아무것도 안 되는지" 알 수 없는 상태로 남는다. 죽은 자격 증명을 들고
    // 계속 요청하는 것도 문제고, sessionStorage에 남겨 두면 새로고침해도 같은 상태로 되돌아온다.
    server.use(meHandler, stockHandler)
    const user = userEvent.setup()
    render(<App />)

    await submitLogin(user, 'park.jh')
    expect(await screen.findByText('ICN01-A-01')).toBeInTheDocument()

    // 여기서부터 세션이 만료됐다고 본다.
    server.use(http.get('*/api/stock', () => UNAUTHORIZED))
    await user.selectOptions(screen.getByLabelText('창고'), 'YIT01')

    expect(await screen.findByRole('button', { name: '로그인' })).toBeInTheDocument()
    expect(getCredentials()).toBeNull()
    expect(sessionStorage.getItem('zerosum.credentials')).toBeNull()
  })
})
