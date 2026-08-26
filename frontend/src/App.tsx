import { Link, Route, Routes } from 'react-router'
import { AdminAuthProvider } from './admin/auth/AdminAuthProvider'
import { AdminHome } from './admin/auth/AdminHome'
import { AdminLayout } from './admin/auth/AdminLayout'
import { LoginPage } from './admin/auth/LoginPage'
import { RequireAdmin } from './admin/auth/RequireAdmin'
import NaverMap from './maps/naver/NaverMap.tsx'

function Home() {
  return (
    <div className="app-shell">
      <header className="service-header" aria-label="서비스 헤더">
        <Link className="brand-link" to="/" aria-label="두꺼비집 홈">
          <span className="brand-mark" aria-hidden="true">
            <svg
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2.2"
              strokeLinecap="round"
              strokeLinejoin="round"
              focusable="false"
            >
              <path d="m4 10 8-6 8 6" />
              <path d="M6.5 9.5V20h11V9.5" />
              <path d="M10 20v-6h4v6" />
            </svg>
          </span>
          <span className="brand-name">두꺼비집</span>
          <span className="brand-tagline">공공임대 지도</span>
        </Link>
      </header>
      <main className="map-main">
        <NaverMap />
      </main>
    </div>
  )
}

function NotFound() {
  return (
    <main className="not-found-main">
      <h1>페이지를 찾을 수 없습니다.</h1>
      <p>입력한 주소를 다시 확인해 주세요.</p>
      <Link to="/">지도로 돌아가기</Link>
    </main>
  )
}

function AdminRoutes() {
  return (
    <AdminAuthProvider>
      <Routes>
        <Route path="login" element={<LoginPage />} />
        <Route element={<RequireAdmin />}>
          <Route element={<AdminLayout />}>
            <Route index element={<AdminHome />} />
          </Route>
        </Route>
        <Route path="*" element={<NotFound />} />
      </Routes>
    </AdminAuthProvider>
  )
}

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Home />} />
      <Route path="/admin/*" element={<AdminRoutes />} />
      <Route path="*" element={<NotFound />} />
    </Routes>
  )
}
