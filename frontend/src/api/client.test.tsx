import { expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http } from 'msw'
import App from '../App'
import { getCredentials } from './client'
import { server } from '../test/server'
import { UNAUTHORIZED, meHandler, stockHandler } from '../test/fakeApi'
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
 * 401 처리는 client.ts 안에서 apiGet·apiPost·apiDelete 세 곳에 나란히 복제돼 있다. AuthContext 쪽
 * 테스트는 apiGet 경로(재고 조회)로만 401을 겪어 보므로, apiPost·apiDelete 블록을 통째로 지워도
 * 전체 스위트가 초록불이었다 — 실제 앱에서 세션 만료가 걸리는 쓰기 경로(제안 승인·거부, 입고, 출고,
 * 실사, 할당 해제)는 전부 POST/DELETE인데도 그렇다.
 *
 * <p>이 테스트는 그중 하나(입고 등록, POST /api/receipts)로 apiPost의 401 처리를 직접 고정한다.
 * client.ts의 세 블록을 함수 하나로 합치는 리팩터링은 이번 범위 밖이다 — 중복은 남겨 둔다.
 */
it('POST 요청에서도 401이면 로그인 화면으로 돌아가고 자격 증명이 지워진다', async () => {
  server.use(meHandler, stockHandler, http.post('*/api/receipts', () => UNAUTHORIZED))
  const user = userEvent.setup()
  render(<App />)

  await submitLogin(user, 'park.jh') // OPERATOR — 입고 폼을 볼 수 있다
  await user.click(await screen.findByRole('link', { name: '입고' }))

  await user.type(await screen.findByLabelText('발주 줄(poLineRef)'), 'PO-1')
  await user.type(screen.getByLabelText('로케이션 (물리)'), 'RCV-01')
  await user.type(screen.getByLabelText('SKU'), 'SKU-1')
  await user.type(screen.getByLabelText('로트'), 'L1')
  await user.type(screen.getByLabelText('수량'), '5')
  await user.click(screen.getByRole('button', { name: '입고 등록' }))

  expect(await screen.findByRole('button', { name: '로그인' })).toBeInTheDocument()
  expect(getCredentials()).toBeNull()
  expect(sessionStorage.getItem('zerosum.credentials')).toBeNull()
})
