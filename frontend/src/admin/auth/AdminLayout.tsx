import { useEffect, useId, useRef, useState } from 'react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router'
import { BrandLink } from '../../BrandLink'
import { useAdminAuth } from './useAdminAuth'
import styles from './AdminLayout.module.css'
import { FailureReturnLink } from '../ingest/FailureReturnLink'
import { UnsavedChangesDialog } from '../management/UnsavedChangesDialog'
import { AdminIcon, type AdminIconName } from '../shared/AdminIcon'

const workLinks = [['/admin/operations', '운영 홈', 'home'], ['/admin/complexes', '단지 관리', 'building'],
  ['/admin/announcements', '공고 관리', 'document'], ['/admin/failures', '실패·검토 항목', 'alert']] as const
const sourceLinks = [['/admin/ingest/announcements', '공고 수집'], ['/admin/ingest/complexes', '단지 수집'],
  ['/admin/sources', '원천 데이터']] as const
const memberLinks = [['/admin/users', '회원 관리'], ['/admin/notification-subscriptions', '알림 신청 관리'], ['/admin/notification-deliveries', '알림 발송 기록'], ['/admin/feedback', '사용자 의견']] as const
const extraIcons: Record<string, AdminIconName> = {
  '공고 수집': 'download', '단지 수집': 'layers', '원천 데이터': 'database',
  '회원 관리': 'user', '알림 신청 관리': 'bell', '알림 발송 기록': 'send', '사용자 의견': 'message',
}
function MenuIcon({ kind }: { kind: AdminIconName }) {
  return <AdminIcon name={kind} size={20} />
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
  useEffect(() => { setMenuOpen(false) }, [location.pathname])
  // 직접 펼친 메뉴 묶음은 다른 메뉴로 이동해도 접지 않는다. 현재 화면이 든 묶음은 이동할 때 펼친다.
  const activeGroup = sourceLinks.some(([path]) => location.pathname.startsWith(path)) ? 'source'
    : memberLinks.some(([path]) => location.pathname.startsWith(path)) ? 'member' : null
  const [openGroups, setOpenGroups] = useState<Record<string, boolean>>(() => activeGroup ? { [activeGroup]: true } : {})
  useEffect(() => { if (activeGroup) setOpenGroups(groups => groups[activeGroup] ? groups : { ...groups, [activeGroup]: true }) }, [activeGroup])
  const groupProps = (key: string) => ({ open: collapsed || Boolean(openGroups[key]),
    onToggle: (event: React.SyntheticEvent<HTMLDetailsElement>) => { if (collapsed) return; const open = event.currentTarget.open; setOpenGroups(groups => groups[key] === open ? groups : { ...groups, [key]: open }) } })

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
          <span className="admin-brand-context">관리자 페이지</span>
          {/* 메뉴 접기는 메뉴 폭과 같은 로고 줄 오른쪽 끝에 둔다. 메뉴 안에 따로 줄을 차지하지 않는다. */}
          <button type="button" className={styles.collapseButton} aria-label={collapsed ? '메뉴 펼치기' : '메뉴 접기'}
            title={collapsed ? '메뉴 펼치기' : '메뉴 접기'} aria-expanded={!collapsed} aria-controls={menuId} onClick={() => setCollapsed(value => !value)}>
            <AdminIcon name={collapsed ? 'sidebarOpen' : 'sidebarClose'} size={20} />
          </button>
        </div>
        <button className={styles.menuButton} ref={menuButton} type="button" aria-expanded={menuOpen}
          aria-controls={menuId} onClick={() => setMenuOpen(value => !value)}>
          <AdminIcon name={menuOpen ? 'close' : 'menu'} size={18} />
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
        <div className="admin-nav-group"><span>매일 하는 작업</span>
          {workLinks.map(([path, label, icon]) => <NavLink key={path} to={path} aria-label={label}><MenuIcon kind={icon} /><span className={styles.menuLabel}>{label}</span></NavLink>)}
        </div>
        <details className={styles.navTools} {...groupProps('source')}><summary>수집·원천 도구<AdminIcon name="chevronDown" size={16} className={styles.navChevron} /></summary><div className="admin-nav-group">{sourceLinks.map(([path, label]) => <NavLink key={path} to={path} aria-label={label}><MenuIcon kind={extraIcons[label]} /><span className={styles.menuLabel}>{label}</span></NavLink>)}</div></details>
        <details className={styles.navTools} {...groupProps('member')}><summary>회원·알림<AdminIcon name="chevronDown" size={16} className={styles.navChevron} /></summary><div className="admin-nav-group">{memberLinks.map(([path, label]) => <NavLink key={path} to={path} aria-label={label}><MenuIcon kind={extraIcons[label]} /><span className={styles.menuLabel}>{label}</span></NavLink>)}</div></details>
      </nav>
      {/* 모바일 서랍 메뉴 뒤 배경. 누르면 메뉴를 닫는다(키보드는 Escape로 닫는다). */}
      {menuOpen ? <div className={styles.backdrop} aria-hidden="true" onClick={() => { setMenuOpen(false); menuButton.current?.focus() }} /> : null}
      <main className="admin-content" id="admin-main">
        <FailureReturnLink />
        <Outlet />
        <UnsavedChangesDialog />
      </main>
    </div>
  )
}
