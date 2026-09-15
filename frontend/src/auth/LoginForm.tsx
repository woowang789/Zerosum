import { useState, type FormEvent } from 'react'
import { ApiError } from '../api/client'
import { useAuth } from './AuthContext'

export function LoginForm() {
  const { login } = useAuth()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    setSubmitting(true)
    try {
      await login(username, password)
    } catch (err) {
      // 401만 자격 증명 문제다. 나머지(서버 오류 등)를 같은 문구로 뭉뚱그리면 비밀번호가 맞는
      // 사용자가 비밀번호를 고치려 들게 된다.
      if (err instanceof ApiError && err.status === 401) {
        setError('아이디 또는 비밀번호가 올바르지 않다')
      } else if (err instanceof ApiError) {
        setError(`로그인에 실패했다 (${err.status})`)
      } else {
        setError('로그인 요청에 실패했다')
      }
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="login-page">
      <form className="login-card" onSubmit={handleSubmit}>
        <h1 className="login-title">Zerosum</h1>
        <p className="login-subtitle">재고 관리</p>

        <label className="field">
          <span className="field-label">아이디</span>
          <input
            type="text"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoComplete="username"
            autoFocus
            required
          />
        </label>

        <label className="field">
          <span className="field-label">비밀번호</span>
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
            required
          />
        </label>

        {error && <p className="login-error">{error}</p>}

        <button type="submit" className="btn btn-primary" disabled={submitting}>
          {submitting ? '확인 중…' : '로그인'}
        </button>
      </form>
    </div>
  )
}
