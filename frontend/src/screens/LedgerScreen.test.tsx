import { expect, it } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { setCredentials } from '../api/client'
import { server } from '../test/server'
import { authenticate, meHandler, unauthorized } from '../test/fakeApi'
import { LedgerScreen } from './LedgerScreen'

/**
 * 원장 화면은 읽기 전용이라 테스트가 없었다. 그 판단은 <b>표시</b>에 대해서는 지금도 맞다 — 이 화면은
 * 서버 규칙을 다시 계산하지 않고 v_ledger의 행을 그대로 그린다(정렬만 뒤집는다).
 *
 * <p>하지만 게이트는 표시가 아니라 클라이언트 상태다. 조회 쿼리가 {@code enabled: warehouse != null}이고
 * 창고는 {@code me.warehouses[0]}에서만 채워지므로, {@code /api/me}가 실패했거나 창고가 0개면 쿼리가
 * 시작되지 않고 {@code isLoading}도 false다 — 게이트가 없던 동안 이 화면은 <b>아무 문구도 없는 빈
 * 필터 폼</b>이었다. "원장이 비었다"와 "권한이 없다"가 같은 그림이었다는 뜻이다.
 *
 * <p>그래서 이 파일은 게이트만 다룬다. 두 상태를 각각 고정하고, 서로가 상대의 부정 단언을 뒷받침한다 —
 * 창고 0개 쪽에서 "오류 문구가 아니다"라고만 하면, 게이트를 통째로 지워도 그 단언은 통과한다.
 */

function renderAs(username: string): void {
  // 화면만 떼어 그리므로 AuthProvider를 거치지 않는다 — 자격 증명은 직접 심는다.
  setCredentials({ username, password: 'zerosum' })
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
  })
  render(
    <QueryClientProvider client={queryClient}>
      <LedgerScreen />
    </QueryClientProvider>,
  )
}

it('창고 권한이 없는 사용자에게는 이유를 말한다', async () => {
  server.use(meHandler)

  renderAs('jung.hs') // 가공 픽스처 — OPERATOR지만 창고 권한이 없다

  expect(await screen.findByText(/접근할 수 있는 창고가 없다/)).toBeInTheDocument()
  // 게이트 없이는 여기까지 그려졌다: 선택지 0개짜리 드롭다운과 필터만 있는 폼.
  expect(screen.queryByRole('button', { name: '조회' })).not.toBeInTheDocument()
})

it('사용자 정보를 못 받으면 빈 화면 대신 그 사실을 알린다', async () => {
  server.use(
    http.get('*/api/me', ({ request }) => {
      if (!authenticate(request)) return unauthorized()
      return HttpResponse.json({ code: 'INTERNAL', message: '서버 오류' }, { status: 500 })
    }),
  )

  renderAs('park.jh')

  // 5xx는 정책상 한 번 재시도하고(App.tsx) 그 사이 백오프가 붙는다 — 이 테스트는 그 정책을 끄지
  // 않은 기본 재시도로 도는 화면이 아니라(위 renderAs가 retry:false다) 곧바로 실패 상태가 된다.
  expect(await screen.findByText('사용자 정보를 불러오지 못했다. 새로고침해 달라.')).toBeInTheDocument()
  // 창고 0개 화면과 갈리는지도 본다 — 두 상태를 하나로 뭉뚱그리면 원인을 알 수 없다.
  expect(screen.queryByText(/접근할 수 있는 창고가 없다/)).not.toBeInTheDocument()
})
