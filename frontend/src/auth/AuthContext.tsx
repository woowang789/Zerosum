import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import {
  ApiError,
  apiGet,
  clearCredentials,
  getCredentials,
  setCredentials,
  setUnauthorizedHandler,
} from '../api/client'

// 로그인한 사용자가 어느 창고에 접근할 수 있는지 알려주는 API가 없다(/api/me 같은 것이 없음).
// 그래서 로그인 검증은 아무 창고 코드로 /api/stock을 한 번 불러 "401이 아니면 자격 증명은 유효하다"만
// 확인한다. 실제 창고 선택·403 처리는 재고 화면에서 한다.
const PROBE_WAREHOUSE = 'ICN01'

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
      async login(username, password) {
        setCredentials({ username, password })
        try {
          await apiGet(`/api/stock?warehouse=${PROBE_WAREHOUSE}`)
        } catch (err) {
          if (err instanceof ApiError && err.status === 401) {
            throw err
          }
          // 403(그 창고 권한 없음) 등은 자격 증명 자체는 맞다는 뜻이니 로그인은 성공으로 본다.
        }
        // park.jh로 로그인해도 choi.dw의 창고 목록이 보이던 문제 — 이전 사용자의 캐시(특히
        // staleTime: Infinity인 useMe())가 새 사용자 화면에 남지 않도록 setUsername 직전에 비운다.
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
