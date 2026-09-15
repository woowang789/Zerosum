import { beforeEach, describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import App from '../App'
import { getCredentials } from '../api/client'
import { server } from '../test/server'
import { meHandler, stockHandler } from '../test/fakeApi'
import { submitLogin } from '../test/ui'

/**
 * 로그인 성공 여부를 무엇으로 판정하는가.
 *
 * <p>예전에는 하드코딩한 창고 하나로 /api/stock을 찔러 보고 "401이 아니면 통과"로 봤다. 그러면 그
 * 창고에 권한이 없는 사람은 403을 받고 <b>catch 절 덕분에 우연히</b> 로그인됐다 — 맞는 판정이 맞는
 * 이유로 나오지 않는 구조다. 창고 권한 구성이 바뀌는 날 조용히 뒤집힌다. 그래서 인증만 되면 창고와
 * 무관하게 200을 주는 /api/me로 돌렸고, 이 테스트는 그 판정이 다시 창고에 얽히지 않게 고정한다.
 */
describe('로그인 프로브', () => {
  let requested: string[]

  beforeEach(() => {
    requested = []
    server.events.on('request:start', ({ request }) => {
      requested.push(new URL(request.url).pathname)
    })
  })

  it('창고 권한이 하나도 없어도 자격 증명이 맞으면 로그인된다', async () => {
    server.use(meHandler, stockHandler)
    const user = userEvent.setup()
    render(<App />)

    await submitLogin(user, 'jung.hs') // 창고 목록이 비어 있는 사용자

    expect(await screen.findByText('jung.hs')).toBeInTheDocument()
    // 판정의 근거가 /api/me여야 한다. 첫 요청이 재고 조회라면 로그인 성공 여부가 다시 창고 하나에
    // 걸려 있다는 뜻이고, 그러면 이 사용자는 403을 받고 우연히 통과하거나 아예 막힌다.
    expect(requested[0]).toBe('/api/me')
  })

  it('자격 증명이 틀리면 로그인되지 않는다', async () => {
    server.use(meHandler, stockHandler)
    const user = userEvent.setup()
    render(<App />)

    await submitLogin(user, 'park.jh', 'not-the-password')

    expect(await screen.findByText('아이디 또는 비밀번호가 올바르지 않다')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '로그인' })).toBeInTheDocument()
    expect(requested).toEqual(['/api/me'])
  })

  it('401이 아닌 실패는 문구가 다르고, 실패한 자격 증명은 지워진다', async () => {
    // 401만 "아이디 또는 비밀번호가 올바르지 않다"다. 서버 오류 등 나머지를 같은 문구로 뭉뚱그리면
    // 비밀번호가 맞는 사용자가 비밀번호를 고치려 들게 된다 — LoginForm의 분기를 그대로 고정한다.
    //
    // 자격 증명이 지워지는지는 일부러 401이 아닌 경우로 확인한다 — 401이면 client.ts의 apiGet이
    // 자체적으로 clearCredentials()를 이미 부르므로, AuthContext.login()의 catch 절이 지워도 이
    // 케이스에서는 표가 나지 않는다. 500에서는 apiGet이 손대지 않으므로 login() 자신의 정리만 남는다.
    server.use(http.get('*/api/me', () => HttpResponse.json({ code: 'INTERNAL', message: '서버 오류' }, { status: 500 })))
    const user = userEvent.setup()
    render(<App />)

    await submitLogin(user, 'park.jh')

    expect(await screen.findByText('로그인에 실패했다 (500)')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '로그인' })).toBeInTheDocument()
    // 없으면 실패한 자격 증명이 sessionStorage에 그대로 남고, AuthProvider는 초기 상태를
    // getCredentials()?.username으로 잡으므로 새로고침 한 번에 죽은 자격 증명으로 로그인된 화면이 뜬다.
    expect(getCredentials()).toBeNull()
    expect(sessionStorage.getItem('zerosum.credentials')).toBeNull()
  })
})
