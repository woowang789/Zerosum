import { useQuery } from '@tanstack/react-query'
import { apiGet } from '../api/client'

export interface MeResponse {
  username: string
  roles: string[]
  warehouses: string[]
}

/**
 * 로그인한 사용자의 역할·창고(/api/me). 창고 드롭다운을 채우고 역할에 따라 버튼을 감추는 데 쓴다.
 *
 * 주의 — 이건 화면을 정리하는 용도일 뿐 보안이 아니다. 여기서 받은 roles·warehouses를 믿고 버튼을
 * 숨겨도, 실제 인가는 서버(AccessGuard·WarehouseScopeArgumentResolver)가 매 요청마다 다시 검사한다.
 * 버튼을 숨겼다고 요청 자체가 막히는 건 아니라는 뜻이다.
 */
export function useMe() {
  return useQuery({
    queryKey: ['me'],
    queryFn: () => apiGet<MeResponse>('/api/me'),
    staleTime: Infinity, // 세션 동안 역할·창고는 바뀌지 않는다
  })
}
