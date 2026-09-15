import { expect, it } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { setCredentials } from '../api/client'
import { server } from '../test/server'
import { UNAUTHORIZED, authenticate, meHandler } from '../test/fakeApi'
import { ProposalsScreen } from './ProposalsScreen'

/**
 * 승인 화면은 재고 조정을 실행시키는 자리다 — "어느 제안을 승인했는가"가 곧 도메인 결과이므로, 아래
 * 핸들러들은 fakeApi.ts의 authenticate() 규약을 그대로 따르고 경로의 id·쿼리의 warehouse를 실제로
 * 검사한다. 검사가 느슨하면(예: id·warehouse를 안 보고 아무 요청에나 같은 응답을 주면) 화면이
 * 엉뚱한 제안을 승인하도록 바뀌거나 창고 스코프가 빠져도 이 테스트는 그대로 통과해 버린다.
 *
 * <p>무효화 키(invalidateQueries)와 조회 키(useQuery)는 서로를 모르는 두 개의 문자열 배열이다. 한쪽만
 * 고치면 컴파일도 되고 화면도 그려진다 — 다만 방금 승인한 제안이 목록이나 상세에 그대로 낡은 채 남는다.
 * 승인 화면에서 이건 단순한 표시 오류가 아니다: 이미 실행된 제안을 다시 누르게 만들고, 두 번째 시도의
 * 결과(AlreadyDecided)를 보고서야 무슨 일이 있었는지 알게 된다.
 */
it('제안을 승인하면 목록과 상세가 갱신된다', async () => {
  const PROPOSAL_ID = 1
  const WAREHOUSE = 'ICN01' // choi.dw의 첫 창고 — ProposalsScreen이 기본으로 고르는 값과 같아야 한다.

  // 승인 뒤 서버에서 사라지는(대기 목록에서 빠지는) 제안을 흉내 낸다. 목록이 다시 읽혔는지 아닌지가
  // 화면에서 갈리도록, 목록 응답은 승인 전후로 달라야 한다.
  let pending = [
    {
      id: PROPOSAL_ID,
      proposalType: 'COUNT_ADJUST',
      rationale: '실사 차이 보정',
      createdAt: '2026-09-15T09:00:00Z',
      expiresAt: '2099-01-01T00:00:00Z',
    },
  ]

  server.use(
    meHandler,
    http.get('*/api/proposals', ({ request }) => {
      if (!authenticate(request)) return UNAUTHORIZED
      const warehouse = new URL(request.url).searchParams.get('warehouse')
      // 실제 서버(/api/proposals)는 warehouse가 없으면 400이다 — mock도 그렇게 다뤄야 창고 파라미터가
      // 빠지는 변경을 이 테스트가 놓치지 않는다.
      if (!warehouse) {
        return HttpResponse.json({ code: 'BAD_REQUEST', message: 'warehouse가 필요하다' }, { status: 400 })
      }
      // 엉뚱한 창고로 고정해도(예: 'WRONG-WH') 진짜 데이터가 보이면 안 된다.
      return HttpResponse.json(warehouse === WAREHOUSE ? pending : [])
    }),
    http.post('*/api/proposals/:id/approve', ({ request, params }) => {
      if (!authenticate(request)) return UNAUTHORIZED
      // id가 기대한 제안과 다르면(예: proposalId + 999) 아무것도 바꾸지 않는다 — 실제 서버라면
      // 존재하지 않는 제안이므로 404다.
      if (params.id !== String(PROPOSAL_ID)) {
        return HttpResponse.json({ code: 'NOT_FOUND', message: '제안을 찾을 수 없다' }, { status: 404 })
      }
      pending = []
      return HttpResponse.json({ proposalId: PROPOSAL_ID, txnId: 77 })
    }),
    http.get('*/api/proposals/:id', ({ request, params }) => {
      if (!authenticate(request)) return UNAUTHORIZED
      if (params.id !== String(PROPOSAL_ID)) {
        return HttpResponse.json({ code: 'NOT_FOUND', message: '제안을 찾을 수 없다' }, { status: 404 })
      }
      return HttpResponse.json({
        proposal: {
          id: PROPOSAL_ID,
          proposalType: 'COUNT_ADJUST',
          entries: [{ warehouseCode: WAREHOUSE, locationCode: 'A-01', skuCode: 'SKU-200002', lotNo: 'L1', qty: -2 }],
          rationale: '실사 차이 보정',
          proposedBy: 'agent',
          agentMeta: null,
          status: pending.length === 0 ? 'EXECUTED' : 'PENDING',
          createdAt: '2026-09-15T09:00:00Z',
          expiresAt: '2099-01-01T00:00:00Z',
          decisionNote: null,
          issueId: null,
        },
        basisReview: { balance: [], warehouseSku: [] },
      })
    }),
  )

  // 이 화면만 떼어 그리므로 AuthProvider를 거치지 않는다 — 자격 증명은 직접 심는다. 승인 버튼은
  // SUPERVISOR에게만 보이므로 application.yml 기준 SUPERVISOR인 choi.dw로 로그인한다.
  setCredentials({ username: 'choi.dw', password: 'zerosum' })
  const queryClient = new QueryClient({
    // 재시도와 포커스 재조회를 끈다. 이 테스트가 세려는 것은 "무효화가 다시 읽게 했는가" 하나이고,
    // 다른 이유로 일어난 재조회가 섞이면 키가 어긋나도 통과해 버린다.
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
  })
  const user = userEvent.setup()
  render(
    <QueryClientProvider client={queryClient}>
      <ProposalsScreen />
    </QueryClientProvider>,
  )

  await user.click(await screen.findByText('실사 차이 보정'))
  expect(await screen.findByText('대기')).toBeInTheDocument()
  await user.click(await screen.findByRole('button', { name: '승인' }))

  expect(await screen.findByText('대기 중인 제안이 없다')).toBeInTheDocument()
  // 상세 쪽 무효화 키(['proposal', proposalId])도 목록과 따로 걸려 있다 — 이게 안 맞으면 상세 패널의
  // 상태 배지가 승인 뒤에도 낡은 "대기"로 남는다.
  expect(await screen.findByText('실행됨')).toBeInTheDocument()
})
