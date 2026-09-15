import { setupServer } from 'msw/node'

/**
 * 테스트 전체가 함께 쓰는 MSW 서버.
 *
 * <p>여기 있는 MSW는 계약(응답 모양)을 검증하는 도구가 아니다 — 내가 쓴 mock으로 내 가정을 다시
 * 확인할 뿐이라 서버와 어긋나는 것은 애초에 잡히지 않는다. 응답 모양은 백엔드 테스트가 지킨다
 * (web/src/test/.../StockVisibilityTest.java 등). 이 서버의 역할은 로그인·로그아웃·재로그인처럼
 * <b>클라이언트 상태가 옮겨 다니는 과정</b>을 돌려보기 위한 배경을 깔아주는 것뿐이다.
 *
 * <p>기본 핸들러를 두지 않는다. 각 테스트가 server.use()로 자기가 기대하는 응답만 세워야, 그
 * 테스트가 어떤 상황을 가정하는지 파일 하나만 읽고도 알 수 있다.
 */
export const server = setupServer()
