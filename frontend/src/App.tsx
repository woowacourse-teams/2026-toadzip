import { Route, Routes } from 'react-router'
import { AdminAuthProvider } from './admin/auth/AdminAuthProvider'
import { AdminHome } from './admin/auth/AdminHome'
import { AdminLayout } from './admin/auth/AdminLayout'
import { LoginPage } from './admin/auth/LoginPage'
import { RequireAdmin } from './admin/auth/RequireAdmin'

function Home() {
  return (
    <main>
      <h1>두꺼비집</h1>
      <p>프론트엔드 개발 환경이 준비되었습니다.</p>
    </main>
  )
}

function NotFound() {
  return (
    <main>
      <h1>페이지를 찾을 수 없습니다.</h1>
      <p>입력한 주소를 다시 확인해 주세요.</p>
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
