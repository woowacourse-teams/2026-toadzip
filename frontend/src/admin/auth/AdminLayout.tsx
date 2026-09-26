import { useState } from 'react'
import { NavLink, Outlet, useNavigate } from 'react-router'
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
      {error ? <p className="form-error admin-layout-error">{error}</p> : null}
      <nav className="admin-navigation" aria-label="관리자 메뉴">
        <NavLink to="/admin/ingest">수집·정제</NavLink>
        <NavLink to="/admin/complexes">단지 입력</NavLink>
        <NavLink to="/admin/announcements">공고 입력</NavLink>
        <NavLink to="/admin/locations">주소 데이터</NavLink>
      </nav>
      <main className="admin-content">
        <Outlet />
      </main>
    </div>
  )
}
