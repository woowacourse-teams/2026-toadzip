import { useState } from 'react'
import { Link, Outlet, useNavigate } from 'react-router'
import { useAdminAuth } from './useAdminAuth'

export function AdminLayout() {
  const { logout, session } = useAdminAuth()
  const navigate = useNavigate()
  const [error, setError] = useState<string | null>(null)

  function handleLogout() {
    void signOut()
  }

  async function signOut() {
    setError(null)
    try {
      await logout()
      navigate('/admin/login', { replace: true })
    } catch (requestError) {
      if (requestError instanceof Error) {
        setError(requestError.message)
      }
      if (!(requestError instanceof Error)) {
        setError('로그아웃 요청을 처리하지 못했습니다.')
      }
    }
  }

  return (
    <div className="admin-layout">
      <header className="admin-header">
        <span>공공주택 복덕방 관리자</span>
        <div>
          <span>{session?.loginIdentifier}</span>
          <button onClick={handleLogout} type="button">로그아웃</button>
        </div>
      </header>
      <nav className="admin-nav" aria-label="관리 메뉴">
        <Link to="/admin">데이터 등록</Link>
        <Link to="/admin/notification-cancellations">알림 취소 요청</Link>
      </nav>
      {error ? <p className="form-error admin-layout-error">{error}</p> : null}
      <main className="admin-content">
        <Outlet />
      </main>
    </div>
  )
}
