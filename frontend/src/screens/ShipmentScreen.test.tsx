import { expect, it } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { UserEvent } from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { setCredentials } from '../api/client'
import { server } from '../test/server'
import { authenticate, meHandler, unauthorized } from '../test/fakeApi'
import { ShipmentScreen } from './ShipmentScreen'

/**
 * 출고 화면의 두 단계 사이에서 <b>물리 줄이 어떻게 옮겨지는가</b>를 고정한다.
 *
 * <p>로트는 사람이 고르지 않는다 — 할당 요청에는 SKU·수량만 넣고, 서버가 FEFO로 고른 로케이션·로트·수량이
 * 응답의 {@code lines}로 돌아온다. 화면은 그것을 들고 있다가 출고 확정 때 그대로 실어 보낸다.
 * ShipmentScreen.tsx의 주석은 "그래서 물리 줄이 할당과 어긋날 일이 없다(ORPHAN_CONSUME 방지)"고
 * 주장하는데, 그 주장을 지키는 것이 코드에는 아무것도 없었다. 서버의 AllocationLinesTest는 <b>응답에
 * lines가 들어 있다</b>는 것까지만 지킨다 — 화면이 그 줄을 올바르게 골라 실어 보내는지는 별개다.
 *
 * <p>여기서 보는 것은 클라이언트 상태와 화면이 보낸 요청 본문뿐이다. FEFO 자체가 맞는지는 서버 것이고,
 * 이 테스트들이 판정할 수 있는 것도 아니다 — 그래서 가짜 서버는 <b>화면이 추측으로는 만들 수 없는</b>
 * 줄을 돌려준다(수량을 로트 둘로 쪼개고 로케이션도 다르게 둔다). 화면이 FEFO를 다시 구현했거나 입력값에서
 * 줄을 만들어냈다면 그 모양이 나올 수 없다.
 *
 * <p>요청 횟수는 세지 않는다. 값이 있는 것은 <b>보낸 본문</b>이고, 모아서 전부 검사한다.
 */

const WAREHOUSE = 'ICN01' // park.jh(OPERATOR)의 유일한 창고 — 두 폼이 기본으로 고르는 값과 같아야 한다.

interface AllocateBody {
  orderLineRef: string
  warehouseCode: string
  skuCode: string
  qty: number
  allowInCount: boolean
}

interface AllocationLine {
  allocationId: number
  locationCode: string
  skuCode: string
  lotNo: string
  qty: number
}

interface ShipmentBody {
  orderLineRef: string
  shipmentSeq: number
  warehouseCode: string
  lines: { locationCode: string; skuCode: string; lotNo: string; qty: number }[]
  consumeAllocationIds: number[]
}

interface ReleaseBody {
  allocationIds: number[]
}

/**
 * POST /api/allocations — 주문라인별로 서버가 예약한 물리 줄을 미리 정해 두고 그대로 돌려준다.
 *
 * <p>주문번호·창고를 실제로 검사한다. 아무 요청에나 같은 응답을 주는 관대한 mock이면, 화면이 주문번호를
 * 잃어버리거나 창고를 안 보내도 초록불이 된다.
 */
function allocateHandler(
  byOrderLineRef: Record<string, { allocationIds: number[]; lines: AllocationLine[] }>,
  captured: AllocateBody[],
) {
  return http.post('*/api/allocations', async ({ request }) => {
    if (!authenticate(request)) return unauthorized()
    const body = (await request.json()) as AllocateBody
    captured.push(body)
    if (body.warehouseCode !== WAREHOUSE) {
      return HttpResponse.json({ code: 'FORBIDDEN', message: '권한이 없다' }, { status: 403 })
    }
    const response = byOrderLineRef[body.orderLineRef]
    if (!response) {
      return HttpResponse.json(
        { code: 'NOT_FOUND', message: `모르는 주문라인이다: ${body.orderLineRef}` },
        { status: 404 },
      )
    }
    return HttpResponse.json(response)
  })
}

/** h3 제목으로 그 섹션의 컨테이너를 집는다 — 두 단계의 폼이 '창고'·'주문번호(orderLineRef)' 라벨을 같이 쓴다. */
function section(title: string): HTMLElement {
  const heading = screen.getByRole('heading', { name: title })
  const container = heading.parentElement
  if (!container) {
    throw new Error(`섹션 컨테이너를 찾지 못했다: ${title}`)
  }
  return container
}

async function renderShipmentScreen(): Promise<UserEvent> {
  // 화면만 떼어 그리므로 AuthProvider를 거치지 않는다 — 자격 증명은 직접 심는다. 할당·출고 폼은
  // OPERATOR 이상에게만 보이므로 application.yml 기준 OPERATOR인 park.jh로 로그인한다.
  setCredentials({ username: 'park.jh', password: 'zerosum' })
  const user = userEvent.setup()
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
  })
  render(
    <QueryClientProvider client={queryClient}>
      <ShipmentScreen />
    </QueryClientProvider>,
  )
  // /api/me가 오기 전에는 "불러오는 중…"뿐이다 — 폼이 그려질 때까지 기다린 뒤 돌려준다.
  await screen.findByRole('heading', { name: '1단계 — 할당 요청' })
  return user
}

/** 1단계 폼을 채워 할당을 요청하고, 할당 목록에 줄이 생길 때까지 기다린다. */
async function allocate(user: UserEvent, orderLineRef: string, skuCode: string, qty: number): Promise<void> {
  const form = section('1단계 — 할당 요청')
  await user.type(within(form).getByLabelText('주문번호(orderLineRef)'), orderLineRef)
  await user.type(within(form).getByLabelText('SKU'), skuCode)
  await user.type(within(form).getByLabelText('수량'), String(qty))
  await user.click(within(form).getByRole('button', { name: '할당 요청' }))
  // 목록에 뜨기 전에 다음 입력을 시작하면, 폼이 비워지는 시점과 겹쳐 입력이 섞인다.
  await within(section('이번 세션에서 요청한 할당')).findByText(orderLineRef)
}

/** 2단계 폼을 채워 출고를 확정한다. 소진할 할당은 라벨의 'ID n'으로 고른다. */
async function confirmShipment(user: UserEvent, orderLineRef: string, allocationIdLabels: string[]): Promise<void> {
  const panel = section('2단계 — 출고 확정')
  for (const label of allocationIdLabels) {
    await user.click(within(panel).getByRole('checkbox', { name: new RegExp(`ID ${label}$`) }))
  }
  await user.type(within(panel).getByLabelText('주문번호(orderLineRef)'), orderLineRef)
  await user.click(within(panel).getByRole('button', { name: '출고 확정' }))
}

/**
 * 할당 응답의 물리 줄이 그대로 출고 줄이 된다.
 *
 * <p>이게 어긋나면 서버는 ORPHAN_CONSUME으로 거절하거나(운이 좋은 경우) 할당이 예약하지 않은 재고를
 * 빼간다 — 예약과 소진이 다른 로트를 가리키는 순간 재고는 조용히 틀어진다. 화면이 FEFO를 다시 구현하거나
 * 추측하지 않는다는 증거가 이 단언이다.
 */
it('할당 응답의 물리 줄과 할당 id가 그대로 출고 요청에 실린다', async () => {
  const ALLOCATION_ID = 11
  // 수량 10을 로트 둘로 쪼개고 로케이션도 다르게 둔다 — 입력값(SKU·수량)만으로는 만들 수 없는 모양이다.
  const serverLines: AllocationLine[] = [
    { allocationId: ALLOCATION_ID, locationCode: 'ICN01-A-01', skuCode: 'SKU-200002', lotNo: 'L20260910-B', qty: 7 },
    { allocationId: ALLOCATION_ID, locationCode: 'ICN01-B-02', skuCode: 'SKU-200002', lotNo: 'L20261120-C', qty: 3 },
  ]
  const allocateBodies: AllocateBody[] = []
  const shipmentBodies: ShipmentBody[] = []

  server.use(
    meHandler,
    allocateHandler({ 'SO-1001': { allocationIds: [ALLOCATION_ID], lines: serverLines } }, allocateBodies),
    http.post('*/api/shipments', async ({ request }) => {
      if (!authenticate(request)) return unauthorized()
      shipmentBodies.push((await request.json()) as ShipmentBody)
      return HttpResponse.json({ txnId: 501 })
    }),
  )

  const user = await renderShipmentScreen()
  await allocate(user, 'SO-1001', 'SKU-200002', 10)
  await confirmShipment(user, 'SO-1001', [String(ALLOCATION_ID)])

  expect(await screen.findByText('출고가 확정됐다 — 거래 #501')).toBeInTheDocument()

  // 할당 요청은 로트를 고르지 않는다 — SKU와 수량만 보낸다(로트를 화면이 정하기 시작하면 FEFO가 깨진다).
  expect(allocateBodies).toEqual([
    { orderLineRef: 'SO-1001', warehouseCode: WAREHOUSE, skuCode: 'SKU-200002', qty: 10, allowInCount: false },
  ])
  // 출고 줄은 할당 응답의 lines와 로케이션·SKU·로트·수량까지, 그리고 순서까지 같아야 한다.
  expect(shipmentBodies).toEqual([
    {
      orderLineRef: 'SO-1001',
      shipmentSeq: 1,
      warehouseCode: WAREHOUSE,
      lines: [
        { locationCode: 'ICN01-A-01', skuCode: 'SKU-200002', lotNo: 'L20260910-B', qty: 7 },
        { locationCode: 'ICN01-B-02', skuCode: 'SKU-200002', lotNo: 'L20261120-C', qty: 3 },
      ],
      consumeAllocationIds: [ALLOCATION_ID],
    },
  ])
})

/**
 * 할당을 여러 개 만들어 둔 뒤 하나만 골라 출고하면, 고르지 않은 쪽의 줄은 섞여 나가지 않는다.
 *
 * <p>화면은 할당 목록을 세션 내내 들고 있다(조회 API가 없어 새로고침하면 사라진다). 그래서 "지금 고른 것"과
 * "가지고 있는 것"을 혼동하기 쉬운 자리다 — 혼동하면 아직 출고하지 않은 다른 주문의 재고가 함께 빠져나가고,
 * 그 주문은 나중에 예약해 둔 재고가 없다는 사실을 출고 시점에야 알게 된다.
 *
 * <p>두 할당의 로케이션·로트를 다르게 둬서 섞였는지가 본문에서 바로 드러나게 한다.
 */
it('할당을 둘 만든 뒤 하나만 골라 출고하면 고른 쪽만 나간다', async () => {
  const allocateBodies: AllocateBody[] = []
  const shipmentBodies: ShipmentBody[] = []

  server.use(
    meHandler,
    allocateHandler(
      {
        'SO-1001': {
          allocationIds: [11],
          lines: [
            { allocationId: 11, locationCode: 'ICN01-A-01', skuCode: 'SKU-200002', lotNo: 'L20260910-B', qty: 5 },
          ],
        },
        'SO-1002': {
          allocationIds: [22],
          lines: [
            { allocationId: 22, locationCode: 'ICN01-B-02', skuCode: 'SKU-200002', lotNo: 'L20261120-C', qty: 4 },
          ],
        },
      },
      allocateBodies,
    ),
    http.post('*/api/shipments', async ({ request }) => {
      if (!authenticate(request)) return unauthorized()
      shipmentBodies.push((await request.json()) as ShipmentBody)
      return HttpResponse.json({ txnId: 502 })
    }),
  )

  const user = await renderShipmentScreen()
  await allocate(user, 'SO-1001', 'SKU-200002', 5)
  await allocate(user, 'SO-1002', 'SKU-200002', 4)

  // 둘 다 고를 수 있는 상태에서 하나만 고른다는 것이 이 테스트의 전제다.
  const panel = section('2단계 — 출고 확정')
  expect(within(panel).getByRole('checkbox', { name: /ID 11$/ })).toBeInTheDocument()
  expect(within(panel).getByRole('checkbox', { name: /ID 22$/ })).toBeInTheDocument()

  await confirmShipment(user, 'SO-1002', ['22'])

  expect(await screen.findByText('출고가 확정됐다 — 거래 #502')).toBeInTheDocument()
  // 할당은 둘 다 실제로 요청됐다 — 하나만 나간 것이 아니라 "둘 중 하나만 골랐다"는 상황이 맞다.
  expect(allocateBodies.map((b) => b.orderLineRef)).toEqual(['SO-1001', 'SO-1002'])
  expect(shipmentBodies).toEqual([
    {
      orderLineRef: 'SO-1002',
      shipmentSeq: 1,
      warehouseCode: WAREHOUSE,
      lines: [{ locationCode: 'ICN01-B-02', skuCode: 'SKU-200002', lotNo: 'L20261120-C', qty: 4 }],
      consumeAllocationIds: [22],
    },
  ])
})

/**
 * 할당 해제는 그 할당의 id만 보낸다.
 *
 * <p>해제 요청에는 창고도 주문번호도 없고 할당 id 집합뿐이다 — 서버는 그 id로 창고를 되짚어 권한을
 * 검사하고(AllocationController#release) 같은 집합에서 멱등 키를 파생한다. 즉 id 집합이 곧 요청의
 * 전부다. 여기에 다른 할당이 섞이면 아직 출고하지 않은 주문의 예약이 함께 풀리고, 그 재고는 그
 * 순간부터 다른 주문에 팔릴 수 있다.
 */
it('할당 해제는 그 줄의 할당 id만 보낸다', async () => {
  const allocateBodies: AllocateBody[] = []
  const releaseBodies: ReleaseBody[] = []

  server.use(
    meHandler,
    allocateHandler(
      {
        'SO-1001': {
          allocationIds: [11],
          lines: [
            { allocationId: 11, locationCode: 'ICN01-A-01', skuCode: 'SKU-200002', lotNo: 'L20260910-B', qty: 5 },
          ],
        },
        'SO-1002': {
          allocationIds: [22],
          lines: [
            { allocationId: 22, locationCode: 'ICN01-B-02', skuCode: 'SKU-200002', lotNo: 'L20261120-C', qty: 4 },
          ],
        },
      },
      allocateBodies,
    ),
    http.delete('*/api/allocations', async ({ request }) => {
      if (!authenticate(request)) return unauthorized()
      releaseBodies.push((await request.json()) as ReleaseBody)
      return new HttpResponse(null, { status: 204 })
    }),
  )

  const user = await renderShipmentScreen()
  await allocate(user, 'SO-1001', 'SKU-200002', 5)
  await allocate(user, 'SO-1002', 'SKU-200002', 4)

  const list = section('이번 세션에서 요청한 할당')
  const row = within(list).getByRole('row', { name: /SO-1001/ })
  await user.click(within(row).getByRole('button', { name: '할당 해제' }))

  // 상태가 바뀌었다는 것은 요청이 실제로 성공했고 화면이 그 할당만 표시를 바꿨다는 뜻이다.
  expect(await within(row).findByText('해제됨')).toBeInTheDocument()
  expect(within(list).getByRole('row', { name: /SO-1002/ })).toHaveTextContent('활성')
  // 해제된 할당은 2단계의 선택지에서도 빠진다 — 바로 위에서 둘 다 있었음을 확인했으므로 빈 단언이 아니다.
  const panel = section('2단계 — 출고 확정')
  expect(within(panel).queryByRole('checkbox', { name: /ID 11$/ })).not.toBeInTheDocument()
  expect(within(panel).getByRole('checkbox', { name: /ID 22$/ })).toBeInTheDocument()

  expect(allocateBodies.map((b) => b.orderLineRef)).toEqual(['SO-1001', 'SO-1002'])
  expect(releaseBodies).toEqual([{ allocationIds: [11] }])
})
