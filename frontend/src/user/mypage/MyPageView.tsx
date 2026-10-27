import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Link, useLocation, useNavigate } from 'react-router'
import { Button } from '../../design-system/components/Button'
import { IconButton } from '../../design-system/components/IconButton'
import { captureProductEvent } from '../../analytics/productAnalytics'
import { AnnouncementStatusBadge } from '../../public-housing/components/AnnouncementStatusBadge'
import { RequestError } from '../../public-housing/components/RequestError'
import { useMobileViewport } from '../../public-housing/components/useMobileViewport'
import { NotificationInterestButton } from '../../public-housing/interest/NotificationInterest'
import { useNotificationInterests } from '../../public-housing/interest/NotificationInterestContext'
import type { NotificationTargetType } from '../../public-housing/interest/notificationInterestRepository'
import { MobileInstallAction } from '../../install/MobileInstall'
import { NotificationDeviceList } from '../../notifications/NotificationDeviceList'
import { NotificationPushControl } from '../../notifications/NotificationPushControl'
import { PrivacyLinks } from '../../privacy/Privacy'
import { logoutUser } from '../auth/api'
import { LoginModal } from '../auth/LoginModal'
import { clearLoginReturn, lastExplorerPath, loginPageUrl, readLoginReturn, rememberLoginReturn } from '../auth/loginReturn'
import { BookmarkButton } from '../bookmarks/BookmarkButton'
import { useBookmarks } from '../bookmarks/BookmarkContext'
import type { BookmarkItem, BookmarkType } from '../bookmarks/bookmarkRepository'
import { useMyPageReturnState } from './mypageNavigation'
import styles from './MyPageView.module.css'

type Kind = NotificationTargetType
type Sub = { readonly section: 'bookmarks'; readonly kind: BookmarkType } | { readonly section: 'notifications'; readonly kind: Kind }

const kindLabels: Record<Kind, string> = { COMPLEX: '단지', ANNOUNCEMENT: '공고', REGION: '지역' }

// /mypage는 요약 화면이다. 넓은 화면은 고른 관리 화면(기본: 북마크)을 오른쪽에 함께 보여준다.
function readSub(pathname: string, search: string): Sub | null {
  const type = new URLSearchParams(search).get('type')
  if (pathname === '/mypage/bookmarks') return { section: 'bookmarks', kind: type === 'ANNOUNCEMENT' ? 'ANNOUNCEMENT' : 'COMPLEX' }
  if (pathname === '/mypage/notifications') return { section: 'notifications', kind: type === 'ANNOUNCEMENT' || type === 'REGION' ? type : 'COMPLEX' }
  return null
}
function subPath(sub: Sub) {
  return { pathname: `/mypage/${sub.section}`, search: sub.kind === 'COMPLEX' ? '' : `?type=${sub.kind}` }
}
function subTitle(sub: Sub) {
  return sub.section === 'notifications' ? '알림 관리' : '북마크 관리'
}

export function MyPageView({ onOpenComplex, onOpenAnnouncement, onBrowseAnnouncements, onClose }: {
  readonly onOpenComplex: (id: string) => void
  readonly onOpenAnnouncement: (id: string) => void
  readonly onBrowseAnnouncements: () => void
  readonly onClose: () => void
}) {
  const bookmarks = useBookmarks()
  const interests = useNotificationInterests()
  const location = useLocation()
  const navigate = useNavigate()
  const mobile = useMobileViewport()
  const returnState = useMyPageReturnState()
  const selected = readSub(location.pathname, location.search)
  const sub: Sub = selected ?? { section: 'bookmarks', kind: 'COMPLEX' }
  // 넓은 화면은 두 목록을 함께 보여주므로 분류를 목록마다 기억한다.
  const [savedKind, setSavedKind] = useState<BookmarkType>(selected?.section === 'bookmarks' ? selected.kind : 'COMPLEX')
  const [alarmKind, setAlarmKind] = useState<Kind>(selected?.section === 'notifications' ? selected.kind : 'COMPLEX')
  useEffect(() => {
    if (selected?.section === 'bookmarks') setSavedKind(selected.kind)
    if (selected?.section === 'notifications') setAlarmKind(selected.kind)
  }, [selected?.section, selected?.kind])
  function selectKind(next: Sub) {
    if (next.section === 'bookmarks') setSavedKind(next.kind); else setAlarmKind(next.kind)
    void navigate(subPath(next), { replace: true })
  }

  const mode = bookmarks?.mode ?? 'loading'
  const loginResult = new URLSearchParams(location.search).get('login')
  const loginRequested = loginResult === 'required' || loginResult === 'failed'
  const [login, setLogin] = useState(false)
  const loginButton = useRef<HTMLButtonElement>(null)
  useEffect(() => {
    if (mode !== 'member') return
    const returnTo = readLoginReturn(true)
    if (returnTo) { clearLoginReturn(); void navigate(returnTo, { replace: true }); return }
    if (!loginRequested) return
    const params = new URLSearchParams(location.search); params.delete('login')
    void navigate({ pathname: location.pathname, search: params.toString() }, { replace: true })
  }, [mode, loginRequested, location, navigate])
  useEffect(() => {
    if (!mobile || mode !== 'guest' || !(login || loginRequested)) return
    const returnTo = location.pathname + location.search
    rememberLoginReturn(returnTo)
    void navigate(loginPageUrl(returnTo, loginResult === 'failed'), { replace: loginRequested })
  }, [mobile, mode, login, loginRequested, location, navigate, loginResult])
  function closeLogin() {
    setLogin(false)
    if (!loginRequested) return
    const params = new URLSearchParams(location.search); params.delete('login')
    void navigate({ pathname: location.pathname, search: params.toString() }, { replace: true })
  }

  const [loggingOut, setLoggingOut] = useState(false)
  const [logoutError, setLogoutError] = useState('')
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

  // 마이페이지는 지도를 덮으므로 제목 줄에서 언제든 지도로 돌아갈 수 있게 한다.
  const header = <header className={styles.pageHeader}>
    <h1>마이페이지</h1>
    <IconButton label="지도로 돌아가기" title="지도로 돌아가기" onClick={onClose}>
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" aria-hidden="true" focusable="false"><path d="m6 6 12 12M18 6 6 18" /></svg>
    </IconButton>
  </header>
  if (mode !== 'member') return <div className={`${styles.page} ph-no-capture`} data-view="hub">
    {header}
    <div className={styles.single}>
      {mode === 'loading' && <p className={styles.status} role="status">마이페이지를 불러오는 중…</p>}
      {mode === 'error' && <section className={styles.card}><RequestError title="마이페이지를 불러오지 못했어요" onRetry={() => bookmarks?.reload()} /></section>}
      {mode === 'guest' && <section className={`${styles.card} ${styles.guest}`}>
        <strong>로그인하고 관심 주택을 챙겨 보세요</strong>
        <p>저장한 단지·공고와 새 공고 알림을 한곳에서 관리할 수 있어요.</p>
        <Button ref={loginButton} onClick={() => setLogin(true)}>로그인</Button>
      </section>}
      <PrivacyLinks />
    </div>
    {!mobile && mode === 'guest' && (login || loginRequested) && <LoginModal loginFailed={loginResult === 'failed'} sessionError={false} onClose={closeLogin} returnFocusRef={loginButton} />}
  </div>

  const ready = bookmarks?.listStatus === 'ready'
  const saved = ready ? bookmarks.items : null
  const alarms = interests?.mode === 'member' ? interests.targets : null
  const breakdown = (items: readonly { readonly type: Kind }[], kinds: readonly Kind[]) => kinds.map(kind => `${kindLabels[kind]} ${items.filter(item => item.type === kind).length}`).join(' · ')

  return <div className={`${styles.page} ph-no-capture`} data-view={selected ? 'sub' : 'hub'}>
    <div className={styles.columns}>
      <aside className={styles.hub}>
        {header}
        <section className={`${styles.card} ${styles.account}`} aria-label="계정">
          <span>{bookmarks?.user?.email ?? '등록된 이메일이 없어요'}</span>
          <Button variant="secondary" disabled={loggingOut} onClick={() => void logout()}>로그아웃</Button>
        </section>
        {logoutError && <p className={styles.error} role="alert">{logoutError}</p>}
        <UrgentCard onOpenAnnouncement={onOpenAnnouncement} onBrowseAnnouncements={onBrowseAnnouncements} />
        <nav className={`${styles.card} ${styles.counts}`} aria-label="내 관심 주택">
          <HubRow to={{ section: 'bookmarks', kind: savedKind }} current={sub.section === 'bookmarks'} label="북마크 관리"
            value={saved ? `${saved.length}개` : '—'} detail={saved ? breakdown(saved, ['COMPLEX', 'ANNOUNCEMENT']) : undefined} />
          <HubRow to={{ section: 'notifications', kind: alarmKind }} current={sub.section === 'notifications'} label="알림 관리"
            value={alarms ? `${alarms.length}개` : '—'} detail={alarms ? breakdown(alarms, ['COMPLEX', 'ANNOUNCEMENT', 'REGION']) : undefined} />
        </nav>
        <nav className={`${styles.card} ${styles.feedback}`} aria-label="서비스">
          <Link className={styles.row} to="/feedback" state={returnState}><span className={styles.rowLabel}>의견 보내기</span><Chevron /></Link>
        </nav>
        <footer className={styles.privacy}><PrivacyLinks /></footer>
      </aside>

      <main className={styles.lists}>
        <section id="mypage-saved" className={`${styles.card} ${styles.sub}`} aria-labelledby="mypage-saved-title" data-current={sub.section === 'bookmarks' || undefined}>
          <header className={styles.subHeader}>
            <Link className={styles.back} to="/mypage" aria-label="마이페이지로 돌아가기"><Chevron back /></Link>
            <h2 id="mypage-saved-title">{subTitle({ section: 'bookmarks', kind: savedKind })}</h2>
          </header>
          <BookmarkList kind={savedKind} onSelectKind={kind => selectKind({ section: 'bookmarks', kind })} onOpenComplex={onOpenComplex} onOpenAnnouncement={onOpenAnnouncement} />
        </section>
        <section id="mypage-notifications" className={`${styles.card} ${styles.sub}`} aria-labelledby="mypage-alarm-title" data-current={sub.section === 'notifications' || undefined}>
          <header className={styles.subHeader}>
            <Link className={styles.back} to="/mypage" aria-label="마이페이지로 돌아가기"><Chevron back /></Link>
            <h2 id="mypage-alarm-title">{subTitle({ section: 'notifications', kind: alarmKind })}</h2>
            <ClearAllAlarms />
          </header>
          <AlarmList kind={alarmKind} onSelectKind={kind => selectKind({ section: 'notifications', kind })} onOpenComplex={onOpenComplex} onOpenAnnouncement={onOpenAnnouncement} />
        </section>
      </main>
    </div>
  </div>
}


function HubRow({ to, current, label, value, detail }: {
  readonly to: Sub; readonly current: boolean; readonly label: string; readonly value: string; readonly detail?: string
}) {
  return <Link className={styles.row} to={subPath(to)} aria-current={current ? 'page' : undefined}>
    <span className={styles.rowLabel}>{label}{detail && <small>{detail}</small>}</span>
    <span className={styles.rowValue}>{value}</span><Chevron />
  </Link>
}

function UrgentCard({ onOpenAnnouncement, onBrowseAnnouncements }: {
  readonly onOpenAnnouncement: (id: string) => void
  readonly onBrowseAnnouncements: () => void
}) {
  const bookmarks = useBookmarks()
  if (bookmarks?.listStatus !== 'ready') return null
  const urgent = bookmarks.items.filter(item => item.type === 'ANNOUNCEMENT' && item.available
    && (item.status === 'APPLYING' || item.status === 'BEFORE_APPLICATION')).sort(byApplicationUrgency)[0]
  if (!urgent) return <section className={`${styles.card} ${styles.urgent}`}>
    <p className={styles.urgentLabel}>곧 마감하는 공고</p>
    <strong>지금 접수 중인 저장 공고가 없어요</strong>
    <button type="button" className={styles.inlineLink} onClick={onBrowseAnnouncements}>공고 찾아보기<Chevron /></button>
  </section>
  return <section className={`${styles.card} ${styles.urgent}`}>
    <p className={styles.urgentLabel}>{urgent.status === 'APPLYING' ? '곧 마감하는 공고' : '곧 접수를 시작하는 공고'}</p>
    <button type="button" className={styles.urgentBody} onClick={() => onOpenAnnouncement(urgent.targetId)}>
      <AnnouncementBadge item={urgent} />
      <strong>{urgent.name}</strong>
      <span className={styles.meta}>{scheduleText(urgent)}</span>
      <Chevron />
    </button>
  </section>
}

function BookmarkList({ kind, onSelectKind, onOpenComplex, onOpenAnnouncement }: {
  readonly kind: BookmarkType
  readonly onSelectKind: (kind: BookmarkType) => void
  readonly onOpenComplex: (id: string) => void
  readonly onOpenAnnouncement: (id: string) => void
}) {
  const bookmarks = useBookmarks()
  if (!bookmarks || bookmarks.listStatus === 'loading') return <p className={styles.status} role="status">불러오는 중…</p>
  if (bookmarks.listStatus === 'error') return <RequestError title="북마크를 불러오지 못했어요" onRetry={bookmarks.reload} />
  const items = bookmarks.items.filter(item => item.type === kind)
  if (kind === 'ANNOUNCEMENT') items.sort(byApplicationUrgency)
  return <KindTabs id="mypage-saved" label="북마크 유형" kinds={['COMPLEX', 'ANNOUNCEMENT']} selected={kind} onSelect={onSelectKind}
    count={value => bookmarks.items.filter(item => item.type === value).length}>
    {items.length === 0 ? <div className={styles.empty}>
      <strong>저장한 {kind === 'COMPLEX' ? '단지가' : '공고가'} 없어요</strong>
      <p>상세에서 북마크 아이콘을 누르면 여기에 모여요.</p>
    </div> : <ul className={styles.list}>
    {items.map(item => <li key={item.targetId} data-closed={item.status === 'CLOSED' || item.status === 'CANCELLED' || undefined}>
      <button type="button" className={styles.item} disabled={!item.available}
        onClick={() => kind === 'COMPLEX' ? onOpenComplex(item.targetId) : onOpenAnnouncement(item.targetId)}>
        <span className={styles.titleLine}>
          {kind === 'ANNOUNCEMENT' && <AnnouncementBadge item={item} countdown={false} />}
          <strong className={styles.name}>{item.name}</strong>
        </span>
        <span className={styles.meta}>{!item.available ? '지금은 상세 정보를 볼 수 없어요' : kind === 'COMPLEX' ? item.address ?? '주소 정보 없음' : scheduleText(item)}</span>
      </button>
      <div className={styles.actions} onClickCapture={() => focusSelectedTab('mypage-saved')}>
        {item.available && <NotificationInterestButton iconOnly target={{ type: item.type, id: item.targetId, name: item.name }} source="SETTING" />}
        <BookmarkButton iconOnly type={item.type} targetId={item.targetId} name={item.name} />
      </div>
    </li>)}
    </ul>}
  </KindTabs>
}

function AlarmList({ kind, onSelectKind, onOpenComplex, onOpenAnnouncement }: {
  readonly kind: Kind
  readonly onSelectKind: (kind: Kind) => void
  readonly onOpenComplex: (id: string) => void
  readonly onOpenAnnouncement: (id: string) => void
}) {
  const context = useNotificationInterests()
  if (!context || context.mode === 'loading') return <p className={styles.status} role="status">불러오는 중…</p>
  if (context.mode === 'error') return <RequestError title="알림을 불러오지 못했어요" onRetry={context.refreshStatus} />
  if (context.mode !== 'member') return null
  const targets = context.targets.filter(target => target.type === kind)
  return <KindTabs id="mypage-notifications" label="알림 대상 유형" kinds={['COMPLEX', 'ANNOUNCEMENT', 'REGION']} selected={kind} onSelect={onSelectKind}
    count={value => context.targets.filter(target => target.type === value).length}>
    {targets.length === 0
      ? <div className={styles.empty}><strong>등록한 {kindLabels[kind]} 알림이 없어요.</strong><p>상세나 지역 검색에서 종 아이콘을 누르면 새 공고를 알려드려요.</p></div>
      : <ul className={styles.list}>
        {targets.map(target => <li key={target.id}>
          {target.type === 'REGION'
            ? <div className={styles.item}><strong className={styles.name}>{target.name}</strong><span className={styles.meta}>이 지역의 새 공고</span></div>
            : <button type="button" className={styles.item} onClick={() => target.type === 'COMPLEX' ? onOpenComplex(target.id) : onOpenAnnouncement(target.id)}>
              <strong className={styles.name}>{target.name}</strong>
              <span className={styles.meta}>{target.type === 'COMPLEX' ? '이 단지의 새 공고' : '접수 일정 변경'}</span>
            </button>}
          <div className={styles.actions} onClickCapture={() => focusSelectedTab('mypage-notifications')}><NotificationInterestButton iconOnly target={target} source="SETTING" /></div>
        </li>)}
      </ul>}
    {context.batchError && <p className={styles.error} role="alert">{context.batchError}</p>}
    <section className={styles.devices} aria-label="이 기기의 알림 수신"><NotificationPushControl /><MobileInstallAction /><NotificationDeviceList /></section>
  </KindTabs>
}

// 행이 사라지는 해제 동작 전에 포커스를 현재 분류 탭으로 옮겨 키보드 위치를 잃지 않게 한다.
function focusSelectedTab(id: string) {
  document.querySelector<HTMLElement>(`#${id}-tabs [role="tab"][aria-selected="true"]`)?.focus()
}

function KindTabs<K extends Kind>({ id, label, kinds, selected, count, onSelect, children }: {
  readonly id: string
  readonly label: string
  readonly kinds: readonly K[]
  readonly selected: K
  readonly count: (kind: K) => number
  readonly onSelect: (kind: K) => void
  readonly children: ReactNode
}) {
  const tabs = useRef<Array<HTMLButtonElement | null>>([])
  return <>
    <div id={`${id}-tabs`} className={styles.kinds} role="tablist" aria-label={label}>
      {kinds.map((kind, index) => <button key={kind} ref={(element) => { tabs.current[index] = element }} id={`${id}-tab-${kind}`}
        type="button" role="tab" aria-selected={selected === kind} aria-controls={`${id}-panel`} tabIndex={selected === kind ? 0 : -1}
        aria-label={`${kindLabels[kind]} ${count(kind)}`} onClick={() => onSelect(kind)}
        onKeyDown={(event) => {
          const next = event.key === 'ArrowRight' ? (index + 1) % kinds.length
            : event.key === 'ArrowLeft' ? (index + kinds.length - 1) % kinds.length
            : event.key === 'Home' ? 0 : event.key === 'End' ? kinds.length - 1 : null
          if (next === null) return
          event.preventDefault()
          onSelect(kinds[next]!)
          tabs.current[next]?.focus()
        }}>
        {kindLabels[kind]} <span>{count(kind)}</span>
      </button>)}
    </div>
    <div id={`${id}-panel`} role="tabpanel" aria-labelledby={`${id}-tab-${selected}`}>{children}</div>
  </>
}

// 일괄 해제는 모든 분류에 적용되므로 분류 칩이 아닌 제목 줄에 둔다.
function ClearAllAlarms() {
  const context = useNotificationInterests()
  const [confirming, setConfirming] = useState(false)
  if (context?.mode !== 'member' || context.targets.length === 0) return null
  return <>
    <button type="button" className={styles.textButton} disabled={context.blocked || confirming} onClick={() => setConfirming(true)}>모든 알림 끄기</button>
    {confirming && <div className={styles.clearAll} role="group" aria-label="모든 알림 끄기 확인">
      <p>단지·공고·지역 알림 {context.targets.length}개를 모두 끌까요?</p>
      <div><Button variant="secondary" onClick={() => setConfirming(false)}>취소</Button>
        <Button variant="secondary" disabled={context.blocked} onClick={(event) => { focusSelectedTab('mypage-notifications'); setConfirming(false); context.clearAll(event.currentTarget) }}>모두 끄기</Button></div>
    </div>}
  </>
}

function Chevron({ back = false }: { readonly back?: boolean }) {
  return <svg className={styles.chevron} aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
    <path d={back ? 'm15 5-7 7 7 7' : 'm9 5 7 7-7 7'} />
  </svg>
}

function AnnouncementBadge({ item, countdown: showCountdown = true }: { readonly item: BookmarkItem; readonly countdown?: boolean }) {
  const tone = item.status === 'APPLYING' ? 'applying' : item.status === 'BEFORE_APPLICATION' ? 'upcoming'
    : item.status === 'CLOSED' ? 'closed' : item.status === 'CANCELLED' ? 'cancelled' : 'unknown'
  const label = { applying: '접수중', upcoming: '공고중', closed: '접수마감', cancelled: '공고취소', unknown: '공고문 확인' }[tone]
  const target = tone === 'applying' ? item.applicationEndDate : tone === 'upcoming' ? item.applicationStartDate : null
  const days = target ? daysUntil(target) : null
  const countdown = !showCountdown || days === null || days < 0 ? null : {
    visible: `${tone === 'upcoming' ? '접수 시작 ' : ''}${days === 0 ? 'D-Day' : `D-${days}`}`,
    accessible: days === 0 ? `${tone === 'upcoming' ? '접수 시작일' : '접수 마감일'} 당일` : `접수 ${tone === 'upcoming' ? '시작' : '마감'}까지 ${days}일`,
  }
  return <AnnouncementStatusBadge label={label} tone={tone} countdown={countdown} />
}

function scheduleText(item: BookmarkItem) {
  return item.applicationStartDate && item.applicationEndDate
    ? `접수 ${formatDate(item.applicationStartDate)} ~ ${formatDate(item.applicationEndDate)}` : '접수 일정 미정'
}
const urgencyOrder: Record<string, number> = { APPLYING: 0, BEFORE_APPLICATION: 1 }
function byApplicationUrgency(a: BookmarkItem, b: BookmarkItem) {
  const order = (urgencyOrder[a.status ?? ''] ?? 2) - (urgencyOrder[b.status ?? ''] ?? 2)
  if (order !== 0) return order
  const date = (item: BookmarkItem) => (item.status === 'APPLYING' ? item.applicationEndDate : item.applicationStartDate) ?? '9999-12-31'
  return a.status === 'APPLYING' || a.status === 'BEFORE_APPLICATION' ? date(a).localeCompare(date(b)) : date(b).localeCompare(date(a))
}
function daysUntil(date: string) {
  const today = new Date(); today.setHours(0, 0, 0, 0)
  return Math.round((new Date(`${date}T00:00:00`).getTime() - today.getTime()) / 86_400_000)
}
function formatDate(date: string) {
  return date.slice(5).replace('-', '.')
}
