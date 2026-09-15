import { type ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter, Navigate, NavLink, Route, Routes } from 'react-router-dom'
import { AuthProvider, useAuth } from './auth/AuthContext'
import { LoginForm } from './auth/LoginForm'
import { CountScreen } from './screens/CountScreen'
import { IssuesScreen } from './screens/IssuesScreen'
import { LedgerScreen } from './screens/LedgerScreen'
import { ProposalsScreen } from './screens/ProposalsScreen'
import { ReceiptScreen } from './screens/ReceiptScreen'
import { ShipmentScreen } from './screens/ShipmentScreen'
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

  // 로그인·로그아웃 시점의 캐시 비우기는 AuthContext(login/logout)가 인증 상태 전환과 같은 호출 안에서
  // 처리한다 — 여기서 렌더 후에 다시 비우면, 이미 새 사용자로 렌더되어 시작된 쿼리를 구독만 남긴 채
  // 지워버려 영영 pending으로 남는다.
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
            <Route path="/ledger" element={<LedgerScreen />} />
            <Route path="/receipts" element={<ReceiptScreen />} />
            <Route path="/shipments" element={<ShipmentScreen />} />
            <Route path="/proposals" element={<ProposalsScreen />} />
            <Route path="/issues" element={<IssuesScreen />} />
            <Route path="/counts" element={<CountScreen />} />
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
        <li className="sidebar-nav-label">재고</li>
        <li>
          <NavItem to="/stock" icon={<BoxIcon />} label="재고 현황" />
        </li>
        <li>
          <NavItem to="/ledger" icon={<LedgerIcon />} label="원장 조회" />
        </li>

        <li className="sidebar-nav-label">입출고</li>
        <li>
          <NavItem to="/receipts" icon={<ReceiptIcon />} label="입고" />
        </li>
        <li>
          <NavItem to="/shipments" icon={<ShipmentIcon />} label="출고" />
        </li>

        <li className="sidebar-nav-label">운영</li>
        <li>
          <NavItem to="/proposals" icon={<ClipboardIcon />} label="제안 승인" />
        </li>
        <li>
          <NavItem to="/issues" icon={<AlertIcon />} label="정합 이슈" />
        </li>
        <li>
          <NavItem to="/counts" icon={<CountIcon />} label="실사" />
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

function LedgerIcon() {
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
      <path d="M8 6h13" />
      <path d="M8 12h13" />
      <path d="M8 18h13" />
      <path d="M3 6h.01" />
      <path d="M3 12h.01" />
      <path d="M3 18h.01" />
    </svg>
  )
}

function ReceiptIcon() {
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
      <path d="M12 3v12" />
      <path d="M7 10l5 5 5-5" />
      <path d="M5 21h14" />
    </svg>
  )
}

function ShipmentIcon() {
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
      <path d="M12 21V9" />
      <path d="M7 14l5-5 5 5" />
      <path d="M5 3h14" />
    </svg>
  )
}

function CountIcon() {
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
      <rect x="4" y="5" width="16" height="16" rx="2" />
      <path d="M8 10h.01" />
      <path d="M8 14h.01" />
      <path d="M8 18h.01" />
      <path d="M12 10h6" />
      <path d="M12 14h6" />
      <path d="M12 18h6" />
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
