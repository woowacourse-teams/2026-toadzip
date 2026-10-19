import { useEffect, useId, useRef, useState } from 'react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router'
import { BrandLink } from '../../BrandLink'
import { useAdminAuth } from './useAdminAuth'
import styles from './AdminLayout.module.css'

const workLinks = [['/admin/quality', '검토할 데이터', 'review'], ['/admin/complexes', '단지 관리', 'building'],
  ['/admin/announcements', '공고 관리', 'document'], ['/admin/complex-coverage', '단지 누락 대조', 'search']] as const
const sourceLinks = [['/admin/ingest', '수집·정제'], ['/admin/ingest-v2', '공고 수집·검토'],
  ['/admin/sources', '원천 데이터'], ['/admin/failures', '실패·검토 항목'], ['/admin/locations', '주소 데이터']] as const
const memberLinks = [['/admin/users', '회원 관리'], ['/admin/notification-subscriptions', '알림 신청 관리'], ['/admin/feedback', '사용자 의견']] as const
function MenuIcon({ kind }: { kind: string }) {
  const path = kind === 'building' ? 'M4 21V3h12v18M16 9h4v12M8 7h4M8 11h4M8 15h4M8 21v-3h4v3'
    : kind === 'document' ? 'M6 3h9l4 4v14H6zM14 3v5h5M9 12h7M9 16h7'
    : kind === 'search' ? 'M21 21l-5-5M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0'
    : 'M9 3h6v4H9zM6 5H4v16h16V5h-2M8 13l2 2 5-5M8 18h8'
  return <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={path} /></svg>
}

export function AdminLayout() {
  const { logout, session } = useAdminAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const menuId = useId()
  const menuButton = useRef<HTMLButtonElement>(null)
  const [menuOpen, setMenuOpen] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const current = [...workLinks, ...sourceLinks, ...memberLinks].find(([path]) => location.pathname === path || location.pathname.startsWith(`${path}/`))
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
          <span className="admin-brand-context">운영 관리</span>
        </div>
        <div className={styles.location}><span>관리자 작업공간</span><b>/</b><strong>{current?.[1] ?? '데이터 관리'}</strong></div>
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
        <div className={styles.navIntro}><span className={styles.navMark}>관리자 작업실</span><p>공식 근거로 신뢰할 수 있는 정보를 만듭니다.</p></div>
        <div className="admin-nav-group"><span>매일 하는 작업</span>
          {workLinks.map(([path, label, icon]) => <NavLink key={path} to={path}><MenuIcon kind={icon} /><span>{label}</span></NavLink>)}
        </div>
        <details className={styles.navTools} open={sourceLinks.some(([path]) => location.pathname.startsWith(path))}><summary>수집·원천 도구</summary><div className="admin-nav-group">{sourceLinks.map(([path, label]) => <NavLink key={path} to={path}>{label}</NavLink>)}</div></details>
        <details className={styles.navTools} open={memberLinks.some(([path]) => location.pathname.startsWith(path))}><summary>회원·알림</summary><div className="admin-nav-group">{memberLinks.map(([path, label]) => <NavLink key={path} to={path}>{label}</NavLink>)}</div></details>
        <div className={styles.navFoot}>공공주택 복덕방<br /><span>데이터 운영 공간</span></div>
      </nav>
      <main className="admin-content" id="admin-main">
        <Outlet />
      </main>
    </div>
  )
}
