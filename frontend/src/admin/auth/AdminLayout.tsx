import { useEffect, useId, useRef, useState } from 'react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router'
import { BrandLink } from '../../BrandLink'
import { useAdminAuth } from './useAdminAuth'
import styles from './AdminLayout.module.css'
import { FailureReturnLink } from '../ingest/FailureReturnLink'
import { UnsavedChangesDialog } from '../management/UnsavedChangesDialog'

const workLinks = [['/admin/operations', '운영 홈', 'home'], ['/admin/complexes', '단지 관리', 'building'],
  ['/admin/announcements', '공고 관리', 'document'], ['/admin/failures', '실패·검토 항목', 'alert']] as const
const sourceLinks = [['/admin/ingest/announcements', '공고 수집'], ['/admin/ingest/complexes', '단지 수집'],
  ['/admin/sources', '원천 데이터'], ['/admin/locations', '주소 데이터']] as const
const memberLinks = [['/admin/users', '회원 관리'], ['/admin/notification-subscriptions', '알림 신청 관리'], ['/admin/notification-deliveries', '알림 발송 기록'], ['/admin/feedback', '사용자 의견']] as const
const extraIcons: Record<string, string> = {
  '공고 수집': 'document', '단지 수집': 'building', '원천 데이터': 'database', '실패·검토 항목': 'warning',
  '주소 데이터': 'pin', '회원 관리': 'users', '알림 신청 관리': 'bell', '알림 발송 기록': 'send', '사용자 의견': 'message',
}
function MenuIcon({ kind }: { kind: string }) {
  const paths: Record<string, string> = {
    database: 'M4 6c0-4 16-4 16 0s-16 4-16 0v12c0 4 16 4 16 0V6M4 12c0 4 16 4 16 0',
    warning: 'M12 3 2 21h20L12 3zM12 9v5M12 17v1',
    pin: 'M12 22s8-8 8-14A8 8 0 0 0 4 8c0 6 8 14 8 14zM9 9a3 3 0 1 0 6 0 3 3 0 1 0-6 0',
    users: 'M8 12a4 4 0 1 0 0-8 4 4 0 1 0 0 8M1 22v-3c0-6 14-6 14 0v3M17 4a4 4 0 0 1 0 8M19 15c4 0 4 4 4 7',
    bell: 'M4 17h16l-2-4V9a6 6 0 0 0-12 0v4l-2 4M9 21h6',
    send: 'm2 11 20-9-9 20-3-9-8-2zM10 13 22 2',
    message: 'M3 3h18v14H9l-6 5V3zM7 8h10M7 12h6',
  }
  const path = paths[kind] ?? (kind === 'building' ? 'M4 21V3h12v18M16 9h4v12M8 7h4M8 11h4M8 15h4M8 21v-3h4v3'
    : kind === 'document' ? 'M6 3h9l4 4v14H6zM14 3v5h5M9 12h7M9 16h7'
    : kind === 'home' ? 'M3 11l9-7 9 7M5 9.5V20h5v-6h4v6h5V9.5'
    : kind === 'alert' ? 'M12 3 2 20h20L12 3zM12 10v4M12 17h.01'
    : 'M9 3h6v4H9zM6 5H4v16h16V5h-2M8 13l2 2 5-5M8 18h8')
  return <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={path} /></svg>
}

export function AdminLayout() {
  const { logout, session } = useAdminAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const menuId = useId()
  const menuButton = useRef<HTMLButtonElement>(null)
  const [menuOpen, setMenuOpen] = useState(false)
  const [collapsed, setCollapsed] = useState(false)
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
    <div className={`admin-layout ${styles.layout}`} data-collapsed={collapsed}><a className="admin-skip" href="#admin-main">본문으로 건너뛰기</a>
      <header className="admin-header">
        <div className="admin-brand">
          <BrandLink to="/admin/operations" label="관리자 운영 홈으로" />
          <span className="admin-brand-context">관리자 작업실</span>
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
        <button type="button" className={styles.collapseButton} aria-label={collapsed ? '메뉴 펼치기' : '메뉴 접기'}
          title={collapsed ? '메뉴 펼치기' : '메뉴 접기'}
          aria-expanded={!collapsed} aria-controls={menuId} onClick={() => setCollapsed(value => !value)}>
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <rect x="3" y="4" width="18" height="16" rx="2" /><path d="M9 4v16" />
            <path d={collapsed ? 'm13 9 3 3-3 3' : 'm16 9-3 3 3 3'} />
          </svg>
        </button>
        <div className="admin-nav-group"><span>매일 하는 작업</span>
          {workLinks.map(([path, label, icon]) => <NavLink key={path} to={path} aria-label={label}><MenuIcon kind={icon} /><span className={styles.menuLabel}>{label}</span></NavLink>)}
        </div>
        <details className={styles.navTools} open={collapsed || sourceLinks.some(([path]) => location.pathname.startsWith(path))}><summary>수집·원천 도구</summary><div className="admin-nav-group">{sourceLinks.map(([path, label]) => <NavLink key={path} to={path} aria-label={label}><MenuIcon kind={extraIcons[label]} /><span className={styles.menuLabel}>{label}</span></NavLink>)}</div></details>
        <details className={styles.navTools} open={collapsed || memberLinks.some(([path]) => location.pathname.startsWith(path))}><summary>회원·알림</summary><div className="admin-nav-group">{memberLinks.map(([path, label]) => <NavLink key={path} to={path} aria-label={label}><MenuIcon kind={extraIcons[label]} /><span className={styles.menuLabel}>{label}</span></NavLink>)}</div></details>
        <div className={styles.navFoot}>공공주택 복덕방<br /><span>데이터 운영 공간</span></div>
      </nav>
      <main className="admin-content" id="admin-main">
        <FailureReturnLink />
        <Outlet />
        <UnsavedChangesDialog />
      </main>
    </div>
  )
}
