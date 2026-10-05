import { useState } from 'react'
import { NavLink, Outlet, useNavigate } from 'react-router'
import { BrandLink } from '../../BrandLink'
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
    <div className="admin-layout"><a className="admin-skip" href="#admin-main">본문으로 건너뛰기</a>
      <header className="admin-header">
        <div className="admin-brand">
          <BrandLink />
          <span className="admin-brand-context">관리자</span>
        </div>
        <div className="admin-header-account">
          <span>{session?.loginIdentifier}</span>
          <button onClick={handleLogout} type="button">로그아웃</button>
        </div>
      </header>
      {error ? <p className="form-error admin-layout-error">{error}</p> : null}
      <nav className="admin-navigation" aria-label="관리자 메뉴">
        <div className="admin-nav-group"><span>데이터 관리</span>
          <NavLink to="/admin/complexes">단지 관리</NavLink>
          <NavLink to="/admin/announcements">공고 관리</NavLink>
        </div>
        <div className="admin-nav-group"><span>수집 운영</span>
          <NavLink to="/admin/ingest">수집·정제</NavLink>
          <NavLink to="/admin/sources">원천 데이터</NavLink>
          <NavLink to="/admin/failures">실패·검토 항목</NavLink>
          <NavLink to="/admin/locations">주소 데이터</NavLink>
        </div>
        <div className="admin-nav-group"><span>알림 운영</span>
          <NavLink to="/admin/notification-cancellations">알림 취소 요청</NavLink>
        </div>
        <div className="admin-nav-group"><span>회원 운영</span>
          <NavLink to="/admin/users">회원 관리</NavLink>
        </div>
      </nav>
      <main className="admin-content" id="admin-main">
        <Outlet />
      </main>
    </div>
  )
}
