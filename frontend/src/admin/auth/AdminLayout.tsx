import { useEffect, useId, useRef, useState } from 'react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router'
import { BrandLink } from '../../BrandLink'
import { useAdminAuth } from './useAdminAuth'
import styles from './AdminLayout.module.css'

export function AdminLayout() {
  const { logout, session } = useAdminAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const menuId = useId()
  const menuButton = useRef<HTMLButtonElement>(null)
  const [menuOpen, setMenuOpen] = useState(false)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { setMenuOpen(false) }, [location.pathname])

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
    <div className={`admin-layout ${styles.layout}`}><a className="admin-skip" href="#admin-main">본문으로 건너뛰기</a>
      <header className="admin-header">
        <div className="admin-brand">
          <BrandLink />
          <span className="admin-brand-context">관리자</span>
        </div>
        <button className={styles.menuButton} ref={menuButton} type="button" aria-expanded={menuOpen}
          aria-controls={menuId} onClick={() => setMenuOpen(value => !value)}>
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><path d="M4 6h16M4 12h16M4 18h16" /></svg>
          메뉴
        </button>
        <div className="admin-header-account">
          <span title={session?.loginIdentifier}>{session?.loginIdentifier}</span>
          <button onClick={handleLogout} type="button">로그아웃</button>
        </div>
      </header>
      {error ? <p className="form-error admin-layout-error" role="alert">{error}</p> : null}
      <nav id={menuId} className="admin-navigation" aria-label="관리자 메뉴" data-open={menuOpen}
        onKeyDown={event => { if (event.key === 'Escape' && menuOpen) { setMenuOpen(false); menuButton.current?.focus() } }}>
        <div className="admin-nav-group"><span>데이터 관리</span>
          <NavLink to="/admin/complexes">단지 관리</NavLink>
          <NavLink to="/admin/announcements">공고 관리</NavLink>
        </div>
        <div className="admin-nav-group"><span>수집 운영</span>
          <NavLink to="/admin/ingest">수집·정제</NavLink>
          <NavLink to="/admin/ingest-v2">공고 수집·검토</NavLink>
          <NavLink to="/admin/sources">원천 데이터</NavLink>
          <NavLink to="/admin/failures">실패·검토 항목</NavLink>
          <NavLink to="/admin/locations">주소 데이터</NavLink>
        </div>
        <div className="admin-nav-group"><span>회원·알림</span>
          <NavLink to="/admin/users">회원 관리</NavLink>
          <NavLink to="/admin/notification-subscriptions">알림 신청 관리</NavLink>
          <NavLink to="/admin/feedback">사용자 의견</NavLink>
        </div>
      </nav>
      <main className="admin-content" id="admin-main">
        <Outlet />
      </main>
    </div>
  )
}
