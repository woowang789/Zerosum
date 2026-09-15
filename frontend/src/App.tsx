import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter, Link, Navigate, Route, Routes } from 'react-router-dom'
import { AuthProvider, useAuth } from './auth/AuthContext'
import { LoginForm } from './auth/LoginForm'
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
          <Link to="/stock" className="sidebar-nav-item sidebar-nav-item-active">
            <BoxIcon />
            <span>재고 현황</span>
          </Link>
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
