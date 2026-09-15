import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import {
  apiGet,
  clearCredentials,
  getCredentials,
  setCredentials,
  setUnauthorizedHandler,
} from '../api/client'

interface AuthContextValue {
  username: string | null
  login: (username: string, password: string) => Promise<void>
  logout: () => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [username, setUsername] = useState<string | null>(() => getCredentials()?.username ?? null)
  const queryClient = useQueryClient()

  // 캐시 비우기는 인증 상태가 바뀌는 지점(로그인 성공·로그아웃)에서 setUsername과 같은 호출 안에서
  // 동기적으로 해야 한다. 렌더 후 useEffect에서 하면, 새 사용자로 바뀐 뒤 Shell이 먼저 렌더되어 자식
  // 화면들이 쿼리를 시작하고 나서야 clear()가 돌아 방금 시작된 쿼리의 구독만 남기고 지워버린다
  // (해당 쿼리는 영영 pending으로 남는다 — 새로고침해야 정상화됨).
  function logoutAndClear() {
    queryClient.clear()
    clearCredentials()
    setUsername(null)
  }

  useEffect(() => {
    // 세션 만료·비밀번호 변경 등으로 어느 요청에서든 401이 나면 로그인 화면으로 돌린다.
    setUnauthorizedHandler(() => logoutAndClear())
    return () => setUnauthorizedHandler(null)
  }, [])

  const value = useMemo<AuthContextValue>(
    () => ({
      username,
      // 자격 증명 확인은 /api/me로 한다. 이 엔드포인트는 인증만 되면 창고와 무관하게 200이므로,
      // "성공 = 자격 증명이 맞다"가 그대로 성립한다. 실패하면 이유를 가리지 않고 던진다.
      //
      // 전에는 하드코딩한 창고로 /api/stock을 찔러 보고 "401이 아니면 통과"로 봤다. 그 창고에
      // 권한이 없는 사용자(예: warehouses가 [YIT01]뿐인 lee.sm)는 403을 받고 catch 절 덕분에
      // 우연히 로그인되던 구조다 — 오늘 깨지지는 않지만, 로그인 성공 판정이 창고 하나와 catch-all에
      // 걸려 있었다. 그 뒤에 /api/me가 생겼으므로 제자리로 돌린다.
      async login(username, password) {
        setCredentials({ username, password })
        try {
          await apiGet('/api/me')
        } catch (err) {
          clearCredentials()
          throw err
        }
        // park.jh로 로그인해도 choi.dw의 창고 목록이 보이던 문제 — 이전 사용자의 캐시(특히
        // staleTime: Infinity인 useMe())가 새 사용자 화면에 남지 않도록 비운다.
        //
        // UI상 사용자 전환은 항상 로그아웃(logoutAndClear)을 거친 뒤라 그쪽의 queryClient.clear()가
        // 이미 비워 둔 뒤다 — 여기 있는 clear()는 방어적 중복이다. 그래서 "setUsername 직전이라는
        // 이 배치가 누수를 막는다"까지는 테스트가 보증하지 않는다(실측: 이 줄만 지워도 스위트는
        // 전부 통과한다). 테스트가 실제로 지키는 것은 "logoutAndClear·login 중 한쪽의 clear()라도
        // 남아 있으면 화면상 캐시 누수는 없다"는 더 약한 수준이다 — 둘 다 지워야 비로소 빨간불이 된다.
        queryClient.clear()
        setUsername(username)
      },
      logout: logoutAndClear,
    }),
    [username, queryClient],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) {
    throw new Error('useAuth는 AuthProvider 안에서만 쓸 수 있다')
  }
  return ctx
}
