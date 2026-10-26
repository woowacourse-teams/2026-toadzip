import { ManagementTable, ManagementRow } from './ManagementTable'
import { useMyPageReturnState } from './mypageNavigation'
import { MapReturnLink } from '../../MapReturnLink'
import { useEffect, useRef, useState } from 'react'
import { Link, Navigate, NavLink, useLocation, useNavigate } from 'react-router'
import { BrandLink } from '../../BrandLink'
import { Button } from '../../design-system/components/Button'
import { DetailFact, DetailFacts } from '../../public-housing/components/DetailPrimitives'
import { NotificationSettings } from '../../public-housing/interest/NotificationSettings'
import { NotificationInterestButton } from '../../public-housing/interest/NotificationInterest'
import { useNotificationInterests } from '../../public-housing/interest/NotificationInterestContext'
import { PrivacyLinks } from '../../privacy/Privacy'
import { captureProductEvent } from '../../analytics/productAnalytics'
import { logoutUser } from '../auth/api'
import { lastExplorerPath, loginPageUrl, readLoginReturn, clearLoginReturn, rememberLoginReturn } from '../auth/loginReturn'
import { useMobileViewport } from '../../public-housing/components/useMobileViewport'
import { LoginModal } from '../auth/LoginModal'
import { useBookmarks } from '../bookmarks/BookmarkContext'
import { BookmarkButton } from '../bookmarks/BookmarkButton'
import type { BookmarkType } from '../bookmarks/bookmarkRepository'
import styles from './MyPage.module.css'
import { applicationStatusLabel } from '../../public-housing/presentation/announcementDetailPresentation'

const sections = [
  { path: 'bookmarks', label: '북마크', description: '관심 있는 단지와 공고를 모아뒀어요.' },
  { path: 'notifications', label: '알림 관리', description: '관심 주택의 알림과 이 기기의 수신 설정을 관리하세요.' },
  { path: 'account', label: '계정', description: '로그인한 계정 정보를 확인하세요.' },
] as const

export function MyPage() {
  const bookmarks = useBookmarks()
  const interests = useNotificationInterests()
  const location = useLocation()
  const navigate = useNavigate()
  const returnState = useMyPageReturnState()
  const mobile = useMobileViewport()
  const filter: BookmarkType = new URLSearchParams(location.search).get('type') === 'ANNOUNCEMENT' ? 'ANNOUNCEMENT' : 'COMPLEX'
  function setFilter(type: BookmarkType) {
    const params = new URLSearchParams(location.search)
    if (type === 'ANNOUNCEMENT') params.set('type', type)
    else params.delete('type')
    void navigate({ pathname: location.pathname, search: params.toString() }, { replace: true })
  }
  const [login, setLogin] = useState(false)
  const [loggingOut, setLoggingOut] = useState(false)
  const [logoutError, setLogoutError] = useState('')
  const loginButton = useRef<HTMLButtonElement>(null)
  const section = sections.find(item => location.pathname.endsWith(`/${item.path}`)) ?? sections[0]
  const loginResult = new URLSearchParams(location.search).get('login')
  const loginRequested = loginResult === 'required' || loginResult === 'failed'
  const mode = bookmarks?.mode ?? 'loading'
  function closeLogin() {
    setLogin(false)
    if (!loginRequested) return
    const params = new URLSearchParams(location.search); params.delete('login')
    void navigate({ pathname: location.pathname, search: params.toString(), hash: location.hash }, { replace: true })
  }
  useEffect(() => {
    if (mode !== 'member') return
    const returnTo = readLoginReturn(true)
    if (returnTo) { clearLoginReturn(); void navigate(returnTo, { replace: true }); return }
    if (!loginRequested) return
    const params = new URLSearchParams(location.search); params.delete('login')
    void navigate({ pathname: location.pathname, search: params.toString(), hash: location.hash }, { replace: true })
  }, [mode, loginRequested, location, navigate])
  useEffect(() => {
    if (!mobile || mode !== 'guest' || !(login || loginRequested)) return
    const returnTo = location.pathname + location.search + location.hash
    rememberLoginReturn(returnTo)
    void navigate(loginPageUrl(returnTo, loginResult === 'failed'), { replace: loginRequested })
  }, [mobile, mode, login, loginRequested, location, navigate, loginResult])
  async function logout() {
    if (loggingOut) return
    setLoggingOut(true); setLogoutError(''); captureProductEvent('logout_requested')
    try {
      await logoutUser()
      captureProductEvent('logout_succeeded')
      window.dispatchEvent(new Event('toadzip:logout')); interests?.resetSession()
      void navigate(lastExplorerPath())
    } catch { captureProductEvent('logout_failed', { failure_reason: 'request_failed' }); setLogoutError('로그아웃하지 못했어요. 다시 시도해 주세요.') }
    finally { setLoggingOut(false) }
  }
  const items = bookmarks?.items.filter(item => item.type === filter) ?? []
  if (location.pathname === '/mypage/info') return <Navigate to="/mypage/bookmarks" replace />
  return <div className={`${styles.page} ph-no-capture`}>
    <header className={styles.header}><BrandLink /><MapReturnLink /></header>
    <div className={styles.layout}>
      <aside className={styles.sidebar}>
        <p className={styles.eyebrow}>공공주택 복덕방</p><h1>마이페이지</h1>
        <nav aria-label="마이페이지 메뉴">{sections.map(item => <NavLink key={item.path} to={`/mypage/${item.path}`} className={() => section.path === item.path ? styles.active : ''} aria-current={section.path === item.path ? 'page' : undefined}><span>{item.label}</span><span aria-hidden="true">›</span></NavLink>)}</nav>
        <Link className={styles.feedbackLink} to="/feedback" state={returnState}>제보/의견 보내기 <span aria-hidden="true">↗</span></Link>
      </aside>
      <main className={styles.content}>
        <div className={styles.sectionHeader}><div><h2>{section.path === 'bookmarks' ? '내 북마크' : section.label}</h2><p className={styles.description}>{section.description}</p></div>{mode === 'member' && section.path === 'notifications' && <Link className={styles.secondaryLink} to="/notifications" state={returnState}>알림 보관함</Link>}</div>
        {mode === 'loading' && <div className={styles.notice} role="status">마이페이지를 불러오는 중…</div>}
        {mode === 'error' && <RetryNotice title="마이페이지를 불러오지 못했어요" onRetry={bookmarks?.reload} />}
        {mode === 'guest' && <section className={styles.notice}><NoticeIcon /><h3>로그인이 필요해요</h3><p>로그인하면 저장한 주택과 알림을 한곳에서 관리할 수 있어요.</p><Button ref={loginButton} onClick={() => setLogin(true)}>로그인</Button></section>}
        {mode === 'member' && section.path === 'bookmarks' && <>
          <div className={styles.filters} role="group" aria-label="북마크 유형">{(['COMPLEX', 'ANNOUNCEMENT'] as const).map(type => <button key={type} type="button" aria-label={`${type === 'COMPLEX' ? '단지' : '공고'} ${bookmarks?.listStatus === 'ready' ? bookmarks.items.filter(item => item.type === type).length : '불러오는 중'}`} aria-pressed={filter === type} onClick={() => setFilter(type)}><span>{type === 'COMPLEX' ? '단지' : '공고'}</span><span className={styles.count}>{bookmarks?.listStatus === 'ready' ? bookmarks.items.filter(item => item.type === type).length : '—'}</span></button>)}</div>
          {bookmarks?.listStatus === 'loading' && <div className={styles.notice} role="status">북마크를 불러오는 중…</div>}
          {bookmarks?.listStatus === 'error' && <RetryNotice title="북마크를 불러오지 못했어요" onRetry={bookmarks.reload} />}
          {bookmarks?.listStatus === 'ready' && (items.length === 0 ? <section className={styles.notice}><NoticeIcon /><h3>저장한 {filter === 'COMPLEX' ? '단지가' : '공고가'} 없어요</h3><p>지도에서 마음에 드는 주택을 찾아 북마크해 보세요.</p><Link className={styles.mapLink} to={lastExplorerPath()}>주택 찾아보기 <span aria-hidden="true">→</span></Link></section> : <>
            <p className={styles.resultCount}>{filter === 'COMPLEX' ? '단지' : '공고'} {items.length}개 <span>최근 저장순</span></p>
            <ManagementTable label={`${filter === 'COMPLEX' ? '단지' : '공고'} 북마크 내역`} statusLabel={filter === 'ANNOUNCEMENT' ? '접수 상태' : undefined} detailLabel={filter === 'COMPLEX' ? '주소' : '접수 일정'}>{items.map(item => <ManagementRow key={`${item.type}:${item.targetId}`} status={item.type === 'ANNOUNCEMENT' ? <div className={styles.cardMeta}><span data-status={item.status ?? undefined}>{applicationStatusLabel(item.status)}</span></div> : undefined} details={item.type === 'COMPLEX' ? item.address ?? '확인된 주소가 없어요' : item.applicationStartDate && item.applicationEndDate ? <><time dateTime={item.applicationStartDate}>{item.applicationStartDate}</time> ~ <time dateTime={item.applicationEndDate}>{item.applicationEndDate}</time></> : '확인된 일정이 없어요'} actions={<><BookmarkButton type={item.type} targetId={item.targetId} name={item.name} />{item.available && <NotificationInterestButton target={{ type: item.type, id: item.targetId, name: item.name }} source="SETTING" />}</>}>
              <h3>{item.available ? <Link state={returnState} to={`/explore?${item.type === 'COMPLEX' ? 'complexId' : 'announcementId'}=${encodeURIComponent(item.targetId)}`}>{item.name}</Link> : item.name}</h3>
              {!item.available && <p>현재 상세 정보를 조회할 수 없어요.</p>}
            </ManagementRow>)}</ManagementTable>
          </>)}
        </>}
        {mode === 'member' && section.path === 'notifications' && (interests?.mode === 'error' ? <RetryNotice title="알림 설정을 불러오지 못했어요" onRetry={interests.refreshStatus} /> : interests?.mode === 'guest' ? <RetryNotice title="로그인 상태를 다시 확인해 주세요" onRetry={() => { interests.refreshStatus(); bookmarks?.reload() }} /> : <NotificationSettings />)}
        {mode === 'member' && section.path === 'account' && <section className={styles.account}><DetailFacts columns={1} appearance="list"><DetailFact term="이메일" value={bookmarks?.user?.email ?? '등록된 이메일이 없어요'} /></DetailFacts><div className={styles.accountFooter}><p>현재 기기에서 로그아웃합니다.</p><Button disabled={loggingOut} onClick={() => void logout()}>{loggingOut ? '로그아웃 중…' : '로그아웃'}</Button></div>{logoutError && <p role="alert">{logoutError}</p>}</section>}

      </main>
    </div>
    <footer className={styles.footer}><strong>공공주택 복덕방</strong><PrivacyLinks /><p>이용 정보 수집 동의는 언제든 변경할 수 있어요.</p></footer>
    {!mobile && (login || loginRequested && mode === 'guest') && <LoginModal loginFailed={loginResult === 'failed'} sessionError={false} onClose={closeLogin} returnFocusRef={loginButton} />}
  </div>
}
function NoticeIcon() { return <svg className={styles.noticeIcon} aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="M6 3h12v18l-6-4-6 4z" /></svg> }
function RetryNotice({ title, onRetry }: { readonly title: string; readonly onRetry?: () => void }) {
  return <section className={styles.notice}><div role="alert"><h3>{title}</h3><p>잠시 후 다시 시도해 주세요.</p></div><Button onClick={onRetry}><svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M3 11a9 9 0 0 1 15.36-5.36L21 8M21 3v5h-5M21 13a9 9 0 0 1-15.36 5.36L3 16M3 21v-5h5" /></svg>다시 시도</Button></section>
}
