import { afterAll, afterEach, beforeAll, beforeEach, expect } from 'vitest'
import { cleanup } from '@testing-library/react'
import '@testing-library/jest-dom/vitest'
import { clearCredentials } from '../api/client'
import { server } from './server'

// 처리되지 않은 요청은 실패시킨다. 이 테스트들이 보려는 것은 클라이언트 상태 전이이고, 그러려면
// 화면이 "무엇을 언제 요청했는가"가 테스트가 세운 그림과 정확히 같아야 한다 — 손대지 않은 요청이
// 조용히 통과하면 그 그림이 틀렸다는 신호를 놓친다.
//
// onUnhandledRequest: 'error'만으로는 이 약속이 서지 않는다(실측 확인) — 이 앱의 요청은 거의 다
// react-query의 queryFn 안에서 일어나는데, 'error' 전략이 만드는 예외는 fetch를 던지는 쪽(queryFn)
// 에서 거부(reject)될 뿐이라 react-query가 평소처럼 쿼리 에러 상태로 삼켜버린다 — 콘솔에 MSW 경고만
// 남고 테스트는 그대로 초록불이다. 대신 request:unhandled 이벤트를 직접 모아 afterEach에서 비어
// 있음을 단언한다 — 이 이벤트는 요청이 어디서(queryFn 안이든 밖이든) 일어났든 예외 없이 발생한다.
let unhandledRequests: string[] = []

beforeAll(() => server.listen())

beforeEach(() => {
  unhandledRequests = []
  server.events.on('request:unhandled', ({ request }) => {
    unhandledRequests.push(`${request.method} ${new URL(request.url).pathname}`)
  })
})

afterEach(() => {
  cleanup()
  // 단언보다 정리를 먼저 한다. 미처리 요청 단언이 터지면 이 뒤의 정리가 통째로 건너뛰어져
  // 핸들러·자격 증명·주소가 다음 테스트로 새고, 그러면 한 테스트의 실패가 뒤 테스트들을 엉뚱하게
  // 무너뜨려 진짜 원인 자리를 가린다. 단언은 정리를 다 끝낸 뒤 맨 마지막에 한다.
  const unhandled = [...unhandledRequests]
  server.resetHandlers()
  server.events.removeAllListeners()
  // api/client.ts의 자격 증명은 모듈 변수 + sessionStorage다. 테스트 파일 하나 안에서는 모듈이
  // 공유되므로, 앞 테스트의 로그인 상태가 남으면 다음 테스트가 로그인 화면이 아니라 이미 로그인된
  // 상태에서 시작한다. 두 저장소를 모두 비워 매번 같은 출발점을 만든다.
  clearCredentials()
  sessionStorage.clear()
  localStorage.clear()
  // 주소도 되돌린다. BrowserRouter가 jsdom의 history를 실제로 밀기 때문에, 앞 테스트에서 이동한
  // 경로가 남으면 다음 테스트가 기본 화면(/stock)이 아닌 곳에서 시작한다.
  window.history.replaceState(null, '', '/')

  expect(unhandled, `처리되지 않은 요청: ${unhandled.join(', ') || '없음'}`).toEqual([])
})

afterAll(() => server.close())
