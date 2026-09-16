import { expect, it } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { setCredentials } from '../api/client'
import { server } from '../test/server'
import { meHandler } from '../test/fakeApi'
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
 * <p>입고 폼의 나머지(멱등 키, 미리보기, 상대 줄)는 여전히 테스트가 없다 — 이 파일은 게이트만
 * 다룬다. 폼이 서버 규칙을 다시 계산하지 않기 때문이다(README의 프론트엔드 테스트 범위).
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
