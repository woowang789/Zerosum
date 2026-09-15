import { useEffect, useRef, type ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter, Navigate, NavLink, Route, Routes } from 'react-router-dom'
import { AuthProvider, useAuth } from './auth/AuthContext'
import { LoginForm } from './auth/LoginForm'
import { IssuesScreen } from './screens/IssuesScreen'
import { ProposalsScreen } from './screens/ProposalsScreen'
import { StockScreen } from './screens/StockScreen'

const queryClient = new QueryClient()

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <Shell />
      </AuthProvider>
    </QueryClientProvider>
  )
}

function Shell() {
  const { username } = useAuth()

  // QueryClient는 앱 전체에서 하나만 만들어 계속 쓴다(위의 모듈 상수) — 로그아웃 뒤 같은 탭에서
  // 다른 사용자로 로그인해도 인스턴스가 안 바뀐다는 뜻이다. useMe()는 staleTime: Infinity라 한 번
  // 받아오면 다시 불러오지 않으므로, 그냥 두면 이전 사용자의 역할·창고(/api/me)가 새 사용자 화면에
  // 그대로 남는다(실측 확인 — park.jh로 로그인해도 choi.dw의 창고 목록이 보였다). 로그인한 사용자가
  // 바뀔 때마다 캐시를 통째로 비워 이 문제를 막는다.
  const previousUsername = useRef(username)
  useEffect(() => {
    if (previousUsername.current !== username) {
      queryClient.clear()
      previousUsername.current = username
    }
  }, [username])

  if (!username) {
    return <LoginForm />
  }

  return (
    <BrowserRouter>
      <div className="app-layout">
        <Sidebar username={username} />
        <main className="app-main">
          <Routes>
            <Route path="/stock" element={<StockScreen />} />
            <Route path="/proposals" element={<ProposalsScreen />} />
            <Route path="/issues" element={<IssuesScreen />} />
            <Route path="*" element={<Navigate to="/stock" replace />} />
          </Routes>
        </main>
      </div>
    </BrowserRouter>
  )
}

function Sidebar({ username }: { username: string }) {
  const { logout } = useAuth()
  return (
    <nav className="sidebar">
      <div className="sidebar-brand">Zerosum</div>
      <ul className="sidebar-nav">
        <li>
          <NavItem to="/stock" icon={<BoxIcon />} label="재고 현황" />
        </li>
        <li>
          <NavItem to="/proposals" icon={<ClipboardIcon />} label="제안 승인" />
        </li>
        <li>
          <NavItem to="/issues" icon={<AlertIcon />} label="정합 이슈" />
        </li>
      </ul>
      <div className="sidebar-footer">
        <span className="sidebar-user">{username}</span>
        <button type="button" className="sidebar-logout" onClick={logout}>
          <LogoutIcon />
          <span>로그아웃</span>
        </button>
      </div>
    </nav>
  )
}

function NavItem({ to, icon, label }: { to: string; icon: ReactNode; label: string }) {
  return (
    <NavLink
      to={to}
      className={({ isActive }) => `sidebar-nav-item${isActive ? ' sidebar-nav-item-active' : ''}`}
    >
      {icon}
      <span>{label}</span>
    </NavLink>
  )
}

function BoxIcon() {
  return (
    <svg
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.75"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="M21 8l-9-5-9 5 9 5 9-5z" />
      <path d="M3 8v8l9 5 9-5V8" />
      <path d="M12 13v8" />
    </svg>
  )
}

function ClipboardIcon() {
  return (
    <svg
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.75"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <rect x="6" y="4" width="12" height="17" rx="2" />
      <path d="M9 4V3a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v1" />
      <path d="M9 11l2 2 4-4" />
    </svg>
  )
}

function AlertIcon() {
  return (
    <svg
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.75"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="M12 3l9 16H3l9-16z" />
      <path d="M12 10v4" />
      <path d="M12 17h.01" />
    </svg>
  )
}

function LogoutIcon() {
  return (
    <svg
      width="16"
      height="16"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.75"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
      <path d="M16 17l5-5-5-5" />
      <path d="M21 12H9" />
    </svg>
  )
}
