import { expect, it } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { UserEvent } from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { setCredentials } from '../api/client'
import { server } from '../test/server'
import { authenticate, meHandler, unauthorized } from '../test/fakeApi'
import { IssuesScreen } from './IssuesScreen'

/**
 * 이슈 화면에서 고정하는 것은 <b>목록과 상세가 같은 사실을 보여주는가</b>다.
 *
 * <p>무효화 키(invalidateQueries)와 조회 키(useQuery)는 서로를 모르는 두 개의 문자열 배열이라, 한쪽만
 * 어긋나도 컴파일되고 화면도 그려진다 — 다만 조용히 낡은 상태가 남는다. 제안 승인 화면에서 같은 버그를
 * 이미 잡았다. 이슈에서 이건 표시 오류로 끝나지 않는다: "누가 언제 인지했는가"가 곧 감사 기록이고,
 * 낡은 상태를 본 다음 사람이 이미 처리된 이슈를 다시 붙잡는다(중복 대응).
 *
 * <p>목록과 상세는 애초에 <b>읽는 곳이 다르다</b>. 목록은 OPEN·ACKED만 담는 {@code v_open_issue}를 읽고,
 * 상세는 {@code inventory_issue}를 직접 읽는다(IssueRepository#findById) — 그래서 종결한 이슈는 목록에서
 * 빠지지만 상세는 여전히 열려야 한다. 서버 쪽은 ResolvedIssueVisibilityTest가 지키고, 여기서는 화면이
 * 그 차이를 실제로 그려내는지를 본다.
 *
 * <p>mock은 경로의 {@code {id}}와 쿼리의 {@code warehouse}를 실제로 검사한다. 느슨하면 화면이 엉뚱한
 * 이슈를 인지·종결해도 초록불이 된다.
 */

const WAREHOUSE = 'ICN01' // park.jh의 유일한 창고이자 choi.dw의 첫 창고 — 화면이 기본으로 고르는 값과 같다.
const ISSUE_ID = 42
const ISSUE_TYPE = 'LEDGER_MISMATCH'

interface IssueState {
  status: string
  ackedAt: string | null
  ackedBy: string | null
  resolvedAt: string | null
  resolvedBy: string | null
  resolutionNote: string | null
  resolvedTxnId: number | null
}

interface ResolveBody {
  note: string
  resolvedTxnId: number | null
}

function initialIssue(): IssueState {
  return {
    status: 'OPEN',
    ackedAt: null,
    ackedBy: null,
    resolvedAt: null,
    resolvedBy: null,
    resolutionNote: null,
    resolvedTxnId: null,
  }
}

/** 목록·상세가 공유하는 이슈 본문. 목록 행(OpenIssueRow)에 상세만 갖는 종결 감사 필드가 더해진 모양이다. */
function issueBody(state: IssueState) {
  return {
    issueId: ISSUE_ID,
    issueType: ISSUE_TYPE,
    severity: 'CRITICAL',
    status: state.status,
    locationId: 1,
    locationCode: 'ICN01-A-01',
    warehouseCode: WAREHOUSE,
    skuId: 2,
    skuCode: 'SKU-200002',
    lotId: 3,
    lotNo: 'L20260910-B',
    countSessionId: null,
    detail: '{"onHandQty":40,"ledgerQty":38}',
    aiAnalysis: null,
    detectedAt: '2026-09-15T09:00:00Z',
    ackedAt: state.ackedAt,
    ackedBy: state.ackedBy,
    resolvedBy: state.resolvedBy,
    resolvedAt: state.resolvedAt,
    resolutionNote: state.resolutionNote,
    resolvedTxnId: state.resolvedTxnId,
  }
}

/**
 * GET /api/issues — {@code v_open_issue}처럼 OPEN·ACKED만 담는다. 종결된 이슈는 여기서 빠진다.
 *
 * <p>warehouse 파라미터를 실제로 검사한다. 실제 서버(WarehouseScope)는 없으면 400이고 권한 밖이면 403이다.
 */
function listHandler(state: IssueState) {
  return http.get('*/api/issues', ({ request }) => {
    if (!authenticate(request)) return unauthorized()
    const warehouse = new URL(request.url).searchParams.get('warehouse')
    if (!warehouse) {
      return HttpResponse.json({ code: 'BAD_REQUEST', message: 'warehouse가 필요하다' }, { status: 400 })
    }
    if (warehouse !== WAREHOUSE) {
      return HttpResponse.json({ code: 'FORBIDDEN', message: '권한이 없다' }, { status: 403 })
    }
    return HttpResponse.json(state.status === 'RESOLVED' ? [] : [issueBody(state)])
  })
}

/** GET /api/issues/{id} — inventory_issue를 직접 읽는 쪽. 상태와 무관하게 200이다. */
function detailHandler(state: IssueState) {
  return http.get('*/api/issues/:id', ({ request, params }) => {
    if (!authenticate(request)) return unauthorized()
    if (params.id !== String(ISSUE_ID)) {
      return HttpResponse.json({ code: 'ISSUE_NOT_FOUND', message: '이슈를 찾을 수 없다' }, { status: 404 })
    }
    return HttpResponse.json({ issue: issueBody(state), ledger: [], countHistory: [] })
  })
}

/**
 * 목록의 이슈 줄.
 *
 * <p>상세 패널에도 표가 둘 있지만(원장·실사 이력) 이 테스트들은 둘을 비워 두므로 이슈 유형 문자열을 담은
 * 행은 목록의 것뿐이다 — 상세 헤더의 같은 문자열은 행이 아니라 제목(h2)이다.
 */
function listRow(): HTMLElement {
  return screen.getByRole('row', { name: new RegExp(ISSUE_TYPE) })
}

async function renderIssuesScreen(username: string): Promise<UserEvent> {
  // 화면만 떼어 그리므로 AuthProvider를 거치지 않는다 — 자격 증명은 직접 심는다.
  setCredentials({ username, password: 'zerosum' })
  const user = userEvent.setup()
  const queryClient = new QueryClient({
    // 재시도와 포커스 재조회를 끈다. 여기서 세려는 것은 "무효화가 다시 읽게 했는가" 하나이고, 다른
    // 이유로 일어난 재조회가 섞이면 키가 어긋나도 통과해 버린다.
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
  })
  render(
    <QueryClientProvider client={queryClient}>
      <IssuesScreen />
    </QueryClientProvider>,
  )
  return user
}

/**
 * 인지한 뒤 목록과 상세가 둘 다 갱신된다.
 *
 * <p>인지는 재고를 움직이지 않는다 — 남는 것은 "누가 언제 봤는가"뿐이다. 그래서 이 화면에서 인지의 결과는
 * <b>표시되는 것 자체</b>가 전부다: 낡은 상태가 남으면 다음 사람이 같은 이슈를 다시 붙잡고, 그 사실은
 * 서버 상태로는 드러나지 않는다(두 번 인지해도 조용히 성공한다).
 */
it('이슈를 인지하면 목록과 상세가 갱신된다', async () => {
  const state = initialIssue()
  const ackedIds: string[] = []

  server.use(
    meHandler,
    listHandler(state),
    detailHandler(state),
    http.post('*/api/issues/:id/ack', ({ request, params }) => {
      if (!authenticate(request)) return unauthorized()
      // id를 검사하지 않으면 화면이 엉뚱한 이슈를 인지해도 통과한다 — 실제 서버라면 404다.
      if (params.id !== String(ISSUE_ID)) {
        return HttpResponse.json({ code: 'ISSUE_NOT_FOUND', message: '이슈를 찾을 수 없다' }, { status: 404 })
      }
      ackedIds.push(String(params.id))
      state.status = 'ACKED'
      state.ackedAt = '2026-09-16T01:00:00Z'
      state.ackedBy = 'park.jh'
      return new HttpResponse(null, { status: 204 })
    }),
  )

  const user = await renderIssuesScreen('park.jh') // OPERATOR — 인지는 OPERATOR 이상
  await user.click(await screen.findByRole('row', { name: new RegExp(ISSUE_TYPE) }))

  // 출발점: 목록은 '열림', 상세에는 인지 항목이 아예 없다(ackedAt이 null이라 그 블록을 그리지 않는다).
  expect(await screen.findByRole('heading', { name: new RegExp(`이슈 #${ISSUE_ID}`) })).toBeInTheDocument()
  expect(listRow()).toHaveTextContent('열림')
  expect(screen.queryByText(/\(park\.jh\)/)).not.toBeInTheDocument()

  await user.click(screen.getByRole('button', { name: '인지' }))

  // 목록 쪽 무효화 키(['issues', warehouse])
  expect(await screen.findByRole('row', { name: /인지됨/ })).toBeInTheDocument()
  // 상세 쪽 무효화 키(['issue', issueId]) — 목록과 따로 걸려 있다. 인지자·인지 시각은 상세에만 나온다.
  expect(await screen.findByText(/\(park\.jh\)/)).toBeInTheDocument()
  // 이미 인지한 이슈에는 인지 버튼이 없다 — 방금 눌렀을 때는 있었으므로 빈 단언이 아니다.
  expect(screen.queryByRole('button', { name: '인지' })).not.toBeInTheDocument()

  expect(ackedIds).toEqual([String(ISSUE_ID)])
})

/**
 * 종결한 이슈는 목록에서 빠지지만 상세는 여전히 열린다.
 *
 * <p>목록은 작업 목록이므로 종결된 것이 빠지는 게 맞고, 상세는 감사 기록이므로 상태와 무관하게 열려야
 * 한다 — 방금 종결한 사람이 새로고침하는 순간 종결자·사유·정정 거래를 볼 수 없게 되면, 왜 닫혔는지를
 * 아무도 되짚을 수 없다. 서버 쪽은 ResolvedIssueVisibilityTest가 지키지만, 화면은 목록이 비는 것과
 * 상세가 남는 것을 <b>동시에</b> 해내야 한다(목록이 비었다고 선택을 지워버리면 상세도 함께 사라진다).
 */
it('이슈를 종결하면 목록에서 빠지고 상세는 종결 정보를 보여준다', async () => {
  const state = initialIssue()
  const resolveBodies: ResolveBody[] = []
  const NOTE = '원장은 고칠 수 없어 사유만 남긴다'

  server.use(
    meHandler,
    listHandler(state),
    detailHandler(state),
    http.post('*/api/issues/:id/resolve', async ({ request, params }) => {
      if (!authenticate(request)) return unauthorized()
      if (params.id !== String(ISSUE_ID)) {
        return HttpResponse.json({ code: 'ISSUE_NOT_FOUND', message: '이슈를 찾을 수 없다' }, { status: 404 })
      }
      const body = (await request.json()) as ResolveBody
      resolveBodies.push(body)
      state.status = 'RESOLVED'
      state.resolvedAt = '2026-09-16T02:00:00Z'
      state.resolvedBy = 'choi.dw'
      state.resolutionNote = body.note
      state.resolvedTxnId = body.resolvedTxnId
      return new HttpResponse(null, { status: 204 })
    }),
  )

  const user = await renderIssuesScreen('choi.dw') // SUPERVISOR — 종결은 SUPERVISOR만
  await user.click(await screen.findByRole('row', { name: new RegExp(ISSUE_TYPE) }))
  expect(await screen.findByRole('heading', { name: new RegExp(`이슈 #${ISSUE_ID}`) })).toBeInTheDocument()

  await user.click(screen.getByRole('button', { name: '종결' }))
  await user.type(screen.getByLabelText('종결 사유'), NOTE)
  await user.type(screen.getByLabelText(/정정 거래 ID/), '7')
  await user.click(screen.getByRole('button', { name: '종결 확정' }))

  // 목록에서 빠졌다 — 바로 위에서 그 줄을 클릭했으므로 이 부정 단언은 빈 단언이 아니다.
  expect(await screen.findByText('열려 있는 이슈가 없다')).toBeInTheDocument()
  expect(screen.queryByRole('row', { name: new RegExp(ISSUE_TYPE) })).not.toBeInTheDocument()

  // 상세는 그대로 열려 있고, 목록에 없는 종결 감사 정보를 보여준다.
  expect(screen.getByRole('heading', { name: new RegExp(`이슈 #${ISSUE_ID}`) })).toBeInTheDocument()
  expect(await screen.findByRole('heading', { name: '종결 정보' })).toBeInTheDocument()
  expect(screen.getByText('choi.dw')).toBeInTheDocument()
  expect(screen.getByText(NOTE)).toBeInTheDocument()
  expect(screen.getByText('#7')).toBeInTheDocument()
  expect(screen.getByText('종결됨')).toBeInTheDocument()

  expect(resolveBodies).toEqual([{ note: NOTE, resolvedTxnId: 7 }])
})
