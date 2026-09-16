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
 * <p>단언하는 것은 <b>보낸 본문</b>이다. 본문을 배열로 모아 통째로 비교하므로 개수도 함께 고정되지만,
 * 그건 부수 효과지 목적이 아니다 — 값이 있는 것은 무엇을 보냈는가다.
 *
 * <p>버튼 한 번에 요청 하나가 나가는 자리들이라 이 형태가 경합이 되지 않는다. 화면이 같은 엔드포인트를
 * 스스로 두 번 부를 수 있는 자리(로그인이 /api/me를 부르고 useMe가 또 부르는 것처럼)였다면 개수를
 * 고정하는 순간 플레이크가 된다 — 실제로 그렇게 물린 적이 있다.
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

/** 화면만 떼어 그린다 — AuthProvider를 거치지 않으므로 자격 증명은 직접 심는다. 기다리지 않는다. */
function renderAs(username: string): UserEvent {
  setCredentials({ username, password: 'zerosum' })
  const user = userEvent.setup()
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
  })
  render(
    <QueryClientProvider client={queryClient}>
      <ShipmentScreen />
    </QueryClientProvider>,
  )
  return user
}

async function renderShipmentScreen(): Promise<UserEvent> {
  // 할당·출고 폼은 OPERATOR 이상에게만 보이므로 application.yml 기준 OPERATOR인 park.jh로 로그인한다.
  const user = renderAs('park.jh')
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
  // 주문번호는 입력하지 않는다 — 고른 예약에서 끌어온다. 끌어온 값이 기대와 같은지 여기서 확인한다.
  expect(within(panel).getByLabelText('주문번호(orderLineRef)')).toHaveValue(orderLineRef)
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

/**
 * 고른 뒤 해제한 할당은 출고 본문에 실리지 않는다.
 *
 * <p>후보 목록은 `status === 'active'`로 걸러 그리는데 선택 집합은 key만 들고 있었다. 그래서 체크한
 * 할당을 해제하면 체크박스가 목록에서 사라져 <b>선택을 되돌릴 수단이 없는 채로</b> 그 할당 id가
 * 출고 본문에 그대로 실려 나갔다 — 서버에서 예약이 이미 풀린 할당을 소진하겠다고 보내는 꼴이다.
 * 서버는 `ALLOC_NOT_ACTIVE`로 거절하니 재고가 틀어지지는 않지만, 사용자는 새로고침 말고는
 * 빠져나올 길이 없다(할당 목록은 조회 API가 없어 새로고침하면 통째로 사라진다).
 *
 * <p>고르는 것과 가지고 있는 것을 혼동하지 않는다는 앞 테스트와는 다른 축이다 — 여기서 보는 것은
 * 골라 둔 것이 <b>그 뒤에 무효가 됐을 때</b>다.
 */
it('고른 뒤 해제한 할당은 출고 본문에 실리지 않는다', async () => {
  const shipmentBodies: ShipmentBody[] = []
  const releaseBodies: ReleaseBody[] = []

  server.use(
    meHandler,
    allocateHandler(
      {
        'SO-2001': {
          allocationIds: [11],
          lines: [
            { allocationId: 11, locationCode: 'ICN01-A-01', skuCode: 'SKU-200002', lotNo: 'L20260910-B', qty: 5 },
          ],
        },
        'SO-2002': {
          allocationIds: [22],
          lines: [
            { allocationId: 22, locationCode: 'ICN01-B-02', skuCode: 'SKU-200002', lotNo: 'L20261120-C', qty: 4 },
          ],
        },
      },
      [],
    ),
    http.delete('*/api/allocations', async ({ request }) => {
      if (!authenticate(request)) return unauthorized()
      releaseBodies.push((await request.json()) as ReleaseBody)
      return new HttpResponse(null, { status: 204 })
    }),
    http.post('*/api/shipments', async ({ request }) => {
      if (!authenticate(request)) return unauthorized()
      shipmentBodies.push((await request.json()) as ShipmentBody)
      return HttpResponse.json({ txnId: 777 })
    }),
  )

  const user = await renderShipmentScreen()
  await allocate(user, 'SO-2001', 'SKU-200002', 5)
  await allocate(user, 'SO-2002', 'SKU-200002', 4)

  // 둘 다 고른다.
  const panel = section('2단계 — 출고 확정')
  await user.click(within(panel).getByRole('checkbox', { name: /ID 11$/ }))
  await user.click(within(panel).getByRole('checkbox', { name: /ID 22$/ }))

  // 그중 하나를 해제한다.
  const list = section('이번 세션에서 요청한 할당')
  const row = within(list).getByRole('row', { name: /SO-2001/ })
  await user.click(within(row).getByRole('button', { name: '할당 해제' }))
  expect(await within(row).findByText('해제됨')).toBeInTheDocument()
  expect(releaseBodies).toEqual([{ allocationIds: [11] }])

  expect(within(panel).getByLabelText('주문번호(orderLineRef)')).toHaveValue('SO-2002')
  await user.click(within(panel).getByRole('button', { name: '출고 확정' }))

  expect(await screen.findByText('출고가 확정됐다 — 거래 #777')).toBeInTheDocument()
  // 해제한 11번은 id도 물리 줄도 나가지 않는다.
  expect(shipmentBodies).toEqual([
    {
      orderLineRef: 'SO-2002',
      shipmentSeq: 1,
      warehouseCode: WAREHOUSE,
      lines: [{ locationCode: 'ICN01-B-02', skuCode: 'SKU-200002', lotNo: 'L20261120-C', qty: 4 }],
      consumeAllocationIds: [22],
    },
  ])
})

/**
 * 같은 주문번호로 두 번 할당해도 목록은 한 줄이고, 같은 재고가 두 번 출고되지 않는다.
 *
 * <p>할당은 `orderLineRef`로 멱등이다 — 같은 주문번호를 다시 보내면 서버는 새 예약을 만들지 않고
 * <b>같은 할당 id</b>를 돌려준다. 그런데 화면이 줄의 key를 시계(`Date.now()`)로 만들던 동안에는
 * 그때마다 새 줄이 생겨 같은 예약이 목록에 두 벌 쌓였고, 둘 다 고르면 같은 물리 줄이 출고 본문에
 * 두 번 실렸다. 화면에는 예약이 두 개로 보이는데 서버에는 하나뿐이다.
 *
 * <p>key를 할당 id에서 만들면 두 번째 응답이 첫 줄을 덮는다.
 */
it('같은 주문번호로 두 번 할당해도 줄이 늘지 않는다', async () => {
  const shipmentBodies: ShipmentBody[] = []

  server.use(
    meHandler,
    allocateHandler(
      {
        // 멱등이므로 두 번째 요청도 같은 id·같은 줄을 돌려준다.
        'SO-3001': {
          allocationIds: [33],
          lines: [
            { allocationId: 33, locationCode: 'ICN01-A-01', skuCode: 'SKU-200002', lotNo: 'L20260910-B', qty: 6 },
          ],
        },
      },
      [],
    ),
    http.post('*/api/shipments', async ({ request }) => {
      if (!authenticate(request)) return unauthorized()
      shipmentBodies.push((await request.json()) as ShipmentBody)
      return HttpResponse.json({ txnId: 888 })
    }),
  )

  const user = await renderShipmentScreen()
  await allocate(user, 'SO-3001', 'SKU-200002', 6)
  await allocate(user, 'SO-3001', 'SKU-200002', 6)

  const list = section('이번 세션에서 요청한 할당')
  expect(within(list).getAllByRole('row', { name: /SO-3001/ })).toHaveLength(1)

  const panel = section('2단계 — 출고 확정')
  expect(within(panel).getAllByRole('checkbox', { name: /ID 33$/ })).toHaveLength(1)

  await confirmShipment(user, 'SO-3001', ['33'])
  expect(await screen.findByText('출고가 확정됐다 — 거래 #888')).toBeInTheDocument()
  expect(shipmentBodies).toEqual([
    {
      orderLineRef: 'SO-3001',
      shipmentSeq: 1,
      warehouseCode: WAREHOUSE,
      lines: [{ locationCode: 'ICN01-A-01', skuCode: 'SKU-200002', lotNo: 'L20260910-B', qty: 6 }],
      consumeAllocationIds: [33],
    },
  ])
})

/**
 * 출고 차수와 allowInCount가 입력한 대로 나간다.
 *
 * <p>둘 다 기본값으로만 시험되면 화면이 값을 무시하고 상수를 보내도 드러나지 않는다. 출고 차수는
 * 멱등 키의 절반(`shipment:{orderLineRef}:{shipmentSeq}`)이라 1로 고정되면 <b>부분 출고 2차가
 * 1차의 재생이 되어 조용히 아무 거래도 생기지 않는다</b> — 화면은 성공으로 보인다.
 * allowInCount는 실사 중인 로케이션 재고를 끌어갈지 여부라 화면 자신이 위험을 경고하는 필드다.
 */
it('출고 차수와 allowInCount가 입력한 대로 나간다', async () => {
  const allocateBodies: AllocateBody[] = []
  const shipmentBodies: ShipmentBody[] = []

  server.use(
    meHandler,
    allocateHandler(
      {
        'SO-4001': {
          allocationIds: [44],
          lines: [
            { allocationId: 44, locationCode: 'ICN01-A-01', skuCode: 'SKU-200002', lotNo: 'L20260910-B', qty: 2 },
          ],
        },
      },
      allocateBodies,
    ),
    http.post('*/api/shipments', async ({ request }) => {
      if (!authenticate(request)) return unauthorized()
      shipmentBodies.push((await request.json()) as ShipmentBody)
      return HttpResponse.json({ txnId: 999 })
    }),
  )

  const user = await renderShipmentScreen()

  const form = section('1단계 — 할당 요청')
  await user.click(within(form).getByRole('checkbox', { name: /allowInCount/ }))
  await allocate(user, 'SO-4001', 'SKU-200002', 2)

  const panel = section('2단계 — 출고 확정')
  await user.click(within(panel).getByRole('checkbox', { name: /ID 44$/ }))
  const seq = within(panel).getByLabelText('출고 차수(shipmentSeq)')
  await user.clear(seq)
  await user.type(seq, '3')
  expect(within(panel).getByLabelText('주문번호(orderLineRef)')).toHaveValue('SO-4001')
  await user.click(within(panel).getByRole('button', { name: '출고 확정' }))

  expect(await screen.findByText('출고가 확정됐다 — 거래 #999')).toBeInTheDocument()
  expect(allocateBodies).toEqual([
    { orderLineRef: 'SO-4001', warehouseCode: WAREHOUSE, skuCode: 'SKU-200002', qty: 2, allowInCount: true },
  ])
  expect(shipmentBodies[0].shipmentSeq).toBe(3)
})

/**
 * 서버가 출고를 거절하면 그 이유가 화면에 뜬다.
 *
 * <p>쓰기 화면의 절반은 거절당하는 경로다. 재고가 모자라거나(INSUFFICIENT_STOCK) 할당과 물리 줄이
 * 어긋나면(ORPHAN_CONSUME) 서버가 막는데, 화면이 그 메시지를 삼키면 사용자는 버튼을 눌렀는데
 * 아무 일도 안 일어난 것으로 본다 — 그리고 다시 누른다. 성공 경로만 시험하면 오류 표시를 통째로
 * 지워도 테스트가 전부 초록불이다.
 *
 * <p>확정 문구가 뜨지 않는 것도 함께 본다. 거절됐는데 성공으로 보이는 것이 더 나쁘다.
 */
it('서버가 출고를 거절하면 그 이유가 화면에 뜬다', async () => {
  server.use(
    meHandler,
    allocateHandler(
      {
        'SO-5001': {
          allocationIds: [55],
          lines: [
            { allocationId: 55, locationCode: 'ICN01-A-01', skuCode: 'SKU-200002', lotNo: 'L20260910-B', qty: 9 },
          ],
        },
      },
      [],
    ),
    http.post('*/api/shipments', ({ request }) => {
      if (!authenticate(request)) return unauthorized()
      return HttpResponse.json(
        { code: 'ORPHAN_CONSUME', message: '소진하려는 할당의 잔액 행이 출고 줄에 없다' },
        { status: 409 },
      )
    }),
  )

  const user = await renderShipmentScreen()
  await allocate(user, 'SO-5001', 'SKU-200002', 9)
  await confirmShipment(user, 'SO-5001', ['55'])

  expect(await screen.findByText('소진하려는 할당의 잔액 행이 출고 줄에 없다')).toBeInTheDocument()
  expect(screen.queryByText(/출고가 확정됐다/)).not.toBeInTheDocument()
})

/**
 * 주문번호는 고른 예약에서 끌어오고, 두 주문에 걸치면 출고가 막힌다.
 *
 * <p>전에는 자유 입력이었다. SO-1001의 예약을 고른 채 SO-9999를 적으면 그대로 나갔고, 그러면
 * 원장에는 SO-9999로 나갔다고 남는데 예약은 SO-1001의 것이라 감사 기록이 갈라졌다. 더 나쁜 것은
 * {@code shipment:SO-9999:1}을 선점해 버리는 것이다 — 나중에 SO-9999를 진짜 출고하면 새 거래가
 * 생기지 않고 이 거래가 조용히 재생된다. 업무 식별자로 만든 키가 그 업무를 가리키지 않게 되는,
 * 실사 시작이 (창고, 로케이션)을 키로 삼아 재실사를 막던 것과 같은 부류의 결함이다.
 *
 * <p>서버도 같은 것을 대조하지만(ALLOC_ORDER_MISMATCH, ShipmentOrderLineTest), 화면에서는 애초에
 * 어긋나게 만들 수가 없어야 한다 — 눌러 봐야 거절당하는 버튼은 안내가 아니다.
 */
it('주문번호는 고른 예약에서 끌어오고, 두 주문에 걸치면 출고가 막힌다', async () => {
  server.use(
    meHandler,
    allocateHandler(
      {
        'SO-6001': {
          allocationIds: [61],
          lines: [
            { allocationId: 61, locationCode: 'ICN01-A-01', skuCode: 'SKU-200002', lotNo: 'L20260910-B', qty: 3 },
          ],
        },
        'SO-6002': {
          allocationIds: [62],
          lines: [
            { allocationId: 62, locationCode: 'ICN01-B-02', skuCode: 'SKU-200002', lotNo: 'L20261120-C', qty: 2 },
          ],
        },
      },
      [],
    ),
  )

  const user = await renderShipmentScreen()
  await allocate(user, 'SO-6001', 'SKU-200002', 3)
  await allocate(user, 'SO-6002', 'SKU-200002', 2)

  const panel = section('2단계 — 출고 확정')
  const field = within(panel).getByLabelText('주문번호(orderLineRef)')

  // 아무것도 고르지 않았으면 비어 있고 출고할 수 없다.
  expect(field).toHaveValue('')
  expect(within(panel).getByRole('button', { name: '출고 확정' })).toBeDisabled()

  // 하나를 고르면 그 주문 줄이 채워진다 — 사람이 적는 값이 아니다.
  await user.click(within(panel).getByRole('checkbox', { name: /ID 61$/ }))
  expect(field).toHaveValue('SO-6001')
  expect(within(panel).getByRole('button', { name: '출고 확정' })).toBeEnabled()

  // 다른 주문의 예약까지 고르면 출고가 막히고 이유가 보인다.
  await user.click(within(panel).getByRole('checkbox', { name: /ID 62$/ }))
  expect(field).toHaveValue('')
  expect(within(panel).getByRole('button', { name: '출고 확정' })).toBeDisabled()
  expect(screen.getByText(/주문 줄 2개에 걸쳐 있다/)).toBeInTheDocument()
})

/**
 * 해제한 할당은 출고가 성공해도 "해제됨"으로 남는다 — 화면이 서버와 다른 말을 하면 안 된다.
 *
 * <p>성공 처리가 <b>선택 집합 전체</b>를 소진 표시했다. 그런데 출고 본문에는 ACTIVE인 것만 실린다(바로
 * 위 테스트가 지킨다). 그래서 골랐다가 해제한 할당은 서버에서는 여전히 풀린 예약인데 화면에서만
 * "출고로 소진됨"으로 바뀌었다 — 재고가 틀어지지는 않지만, 이 화면은 할당 목록을 조회하는 API가 없어
 * 새로고침 말고는 진짜 상태를 다시 볼 방법이 없다. 사용자는 화면을 믿을 수밖에 없고, 그 화면이 틀렸다.
 *
 * <p>본문을 함께 단언한다. 표시만 보면 "아무것도 소진 표시하지 않는" 구현으로도 통과하기 때문이다 —
 * 실제로 보낸 22번이 "출고로 소진됨"이 되는 것까지 봐야 "보낸 것만 바꿨다"가 된다.
 */
it('해제한 할당은 출고 성공 뒤에도 해제됨으로 남는다', async () => {
  const shipmentBodies: ShipmentBody[] = []

  server.use(
    meHandler,
    allocateHandler(
      {
        'SO-3001': {
          allocationIds: [11],
          lines: [
            { allocationId: 11, locationCode: 'ICN01-A-01', skuCode: 'SKU-200002', lotNo: 'L20260910-B', qty: 5 },
          ],
        },
        'SO-3002': {
          allocationIds: [22],
          lines: [
            { allocationId: 22, locationCode: 'ICN01-B-02', skuCode: 'SKU-200002', lotNo: 'L20261120-C', qty: 4 },
          ],
        },
      },
      [],
    ),
    http.delete('*/api/allocations', ({ request }) => {
      if (!authenticate(request)) return unauthorized()
      return new HttpResponse(null, { status: 204 })
    }),
    http.post('*/api/shipments', async ({ request }) => {
      if (!authenticate(request)) return unauthorized()
      shipmentBodies.push((await request.json()) as ShipmentBody)
      return HttpResponse.json({ txnId: 888 })
    }),
  )

  const user = await renderShipmentScreen()
  await allocate(user, 'SO-3001', 'SKU-200002', 5)
  await allocate(user, 'SO-3002', 'SKU-200002', 4)

  const panel = section('2단계 — 출고 확정')
  await user.click(within(panel).getByRole('checkbox', { name: /ID 11$/ }))
  await user.click(within(panel).getByRole('checkbox', { name: /ID 22$/ }))

  // 고른 뒤 11번을 해제한다 — 선택 집합에는 키가 남아 있다.
  const list = section('이번 세션에서 요청한 할당')
  const releasedRow = within(list).getByRole('row', { name: /SO-3001/ })
  await user.click(within(releasedRow).getByRole('button', { name: '할당 해제' }))
  expect(await within(releasedRow).findByText('해제됨')).toBeInTheDocument()

  await user.click(within(panel).getByRole('button', { name: '출고 확정' }))
  expect(await screen.findByText('출고가 확정됐다 — 거래 #888')).toBeInTheDocument()

  // 보낸 것은 22번뿐이다.
  expect(shipmentBodies).toEqual([
    {
      orderLineRef: 'SO-3002',
      shipmentSeq: 1,
      warehouseCode: WAREHOUSE,
      lines: [{ locationCode: 'ICN01-B-02', skuCode: 'SKU-200002', lotNo: 'L20261120-C', qty: 4 }],
      consumeAllocationIds: [22],
    },
  ])

  // 그러므로 표시도 22번만 바뀐다. 11번은 서버에서 풀린 그대로다.
  const shippedRow = within(section('이번 세션에서 요청한 할당')).getByRole('row', { name: /SO-3002/ })
  expect(within(shippedRow).getByText('출고로 소진됨')).toBeInTheDocument()
  const stillReleasedRow = within(section('이번 세션에서 요청한 할당')).getByRole('row', { name: /SO-3001/ })
  expect(within(stillReleasedRow).getByText('해제됨')).toBeInTheDocument()
})

/**
 * 창고 권한이 하나도 없는 사용자에게는 이유를 말한다.
 *
 * <p>출고 화면의 게이트는 "me 로딩 중" 하나뿐이었다 — 창고가 0개면 로딩은 끝났으므로 통과해, 선택지
 * 0개짜리 창고 드롭다운이 달린 할당 폼이 그대로 떴다. 여기서 보는 것은 공용 게이트의 존재가 아니라
 * <b>이 화면이 그것을 거친다</b>는 사실이다(다섯 화면이 빠뜨렸던 것이 그쪽이다).
 */
it('창고 권한이 없는 사용자에게는 이유를 말한다', async () => {
  server.use(meHandler)

  renderAs('jung.hs') // 가공 픽스처 — OPERATOR지만 창고 권한이 없다

  expect(await screen.findByText(/접근할 수 있는 창고가 없다/)).toBeInTheDocument()
  expect(screen.queryByRole('heading', { name: '1단계 — 할당 요청' })).not.toBeInTheDocument()
})
