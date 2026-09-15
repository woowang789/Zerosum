import { screen } from '@testing-library/react'
import type { UserEvent } from '@testing-library/user-event'

/**
 * 로그인 폼을 사람이 쓰듯 채워 제출한다.
 *
 * <p>성공·실패는 판정하지 않는다. 이 헬퍼를 쓰는 테스트들이 보려는 것이 바로 "제출한 뒤 무엇이
 * 달라지는가"라서, 여기서 미리 기다려 버리면 그 판정이 테스트 밖으로 숨는다.
 */
export async function submitLogin(user: UserEvent, username: string, password = 'zerosum'): Promise<void> {
  await user.clear(screen.getByLabelText('아이디'))
  await user.type(screen.getByLabelText('아이디'), username)
  await user.clear(screen.getByLabelText('비밀번호'))
  await user.type(screen.getByLabelText('비밀번호'), password)
  await user.click(screen.getByRole('button', { name: '로그인' }))
}
