import { captureProductEvent, setProductAuthState, setReplaySensitive } from '../../analytics/productAnalytics'
import { useEffect, useRef, useState } from 'react'
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router'
import { NotificationBadge } from '../../notifications/NotificationBadge'
import { getCurrentUser, logoutUser } from './api'
import { LoginModal } from './LoginModal'
import { MemberMenuModal } from './MemberMenuModal'
import { useMobileViewport } from '../../public-housing/components/useMobileViewport'
import { PrivacyLinks } from '../../privacy/Privacy'
import { clearLoginReturn, loginPageUrl, readLoginReturn, rememberLoginReturn, rememberExplorerPath } from './loginReturn'
import { useExplorerReturn } from '../../public-housing/navigation/ExplorerReturnContext'

type SessionState = 'loading' | 'guest' | 'signed-in' | 'error'

export function UserSessionControl({ onLogout, presentation = 'default', sessionOverride, onSessionRetry }: { readonly onLogout?: () => void; readonly presentation?: 'default' | 'rail'; readonly sessionOverride?: SessionState; readonly onSessionRetry?: () => void }) {
  const [localSession, setSession] = useState<SessionState>('loading')
  const session = sessionOverride ?? localSession
  const [isLoggingOut, setIsLoggingOut] = useState(false)
  const [logoutError, setLogoutError] = useState(false)
  const [isLoginOpen, setIsLoginOpen] = useState(false)
  const [memberView, setMemberView] = useState<'inbox' | 'settings' | 'account' | null>(null)
  const memberTrigger = useRef<HTMLButtonElement>(null)
  const mobile = useMobileViewport()
  const explorerReturn = useExplorerReturn()
  const [searchParams] = useSearchParams()
  const { pathname, search, hash } = useLocation()
  const navigate = useNavigate()
  const loginButtonRef = useRef<HTMLButtonElement>(null)
  const inboxRequested = searchParams.get('inbox') === 'open'
  const loginResult = searchParams.get('login')
  const loginRequested = loginResult === 'required' || loginResult === 'failed'
  const remainingParams = new URLSearchParams(searchParams)
  remainingParams.delete('login')
  remainingParams.delete('reason')
  const remainingSearch = remainingParams.toString()

  useEffect(() => {
    setProductAuthState(session === 'signed-in' ? 'member' : session === 'guest' ? 'guest' : 'unknown')
  }, [session])

  useEffect(() => {
    if (session !== 'signed-in') return
    const returnTo = readLoginReturn(true)
    if (returnTo) {
      clearLoginReturn()
      void navigate(returnTo, { replace: true })
    } else if (loginRequested) {
      void navigate({ search: remainingSearch, hash }, { replace: true })
    }
  }, [session, loginRequested, remainingSearch, hash, navigate])

  useEffect(() => {
    if (!mobile || !(isLoginOpen || loginRequested || inboxRequested)
      || (session !== 'guest' && session !== 'error')) return
    const returnTo = loginResult === 'failed'
      ? readLoginReturn(true) ?? explorerReturn?.current ?? pathname + search + hash
      : explorerReturn?.current ?? pathname + search + hash
    rememberLoginReturn(returnTo)
    void navigate(loginPageUrl(returnTo, loginResult === 'failed'), { replace: !isLoginOpen })
  }, [mobile, isLoginOpen, loginRequested, inboxRequested, session, loginResult, pathname, search, hash, navigate, explorerReturn])

  useEffect(() => {
    if (sessionOverride !== undefined) return
    let active = true
    getCurrentUser().then(
      (user) => { if (active) setSession(user ? 'signed-in' : 'guest') },
      () => { if (active) setSession('error') },
    )
    return () => { active = false }
  }, [sessionOverride])

  async function retrySessionCheck() {
    if (onSessionRetry) { onSessionRetry(); return }
    setSession('loading')
    try {
      setSession(await getCurrentUser() ? 'signed-in' : 'guest')
    } catch {
      setSession('error')
    }
  }

  async function handleLogout() {
    if (isLoggingOut) return
    captureProductEvent('logout_requested')
    setIsLoggingOut(true)
    setLogoutError(false)
    try {
      await logoutUser()
      captureProductEvent('logout_succeeded')
      setSession('guest')
      onLogout?.()
    } catch {
      captureProductEvent('logout_failed', { failure_reason: 'request_failed' })
      setLogoutError(true)
    } finally {
      setIsLoggingOut(false)
    }
  }

  function closeMember() {
    setMemberView(null)
    if (!inboxRequested) return
    const params = new URLSearchParams(searchParams)
    params.delete('inbox')
    void navigate({ search: params.toString(), hash }, { replace: true })
  }

  useEffect(() => { if (session !== 'signed-in') setMemberView(null) }, [session])

  function closeLogin() {
    setIsLoginOpen(false)
    if (!loginRequested && !inboxRequested) return
    const params = new URLSearchParams(searchParams)
    params.delete('login')
    params.delete('reason')
    params.delete('inbox')
    void navigate({ search: params.toString(), hash }, { replace: true })
  }

  const accountActions = <>
    <button className="service-notification-management" type="button" aria-haspopup="dialog" onClick={(event) => {
      if (memberView !== 'account') memberTrigger.current = event.currentTarget
      setMemberView('settings')
    }}>알림 관리</button>
    <Link className="service-account-feedback" to="/feedback">제보/의견 보내기</Link>
    {mobile && <PrivacyLinks />}
    <span className="service-login-status"><span aria-hidden="true" />로그인됨</span>
    <button className="service-logout-button" type="button" disabled={isLoggingOut}
      onClick={() => void handleLogout()}>
      {isLoggingOut ? '로그아웃 중…' : '로그아웃'}
    </button>
  </>

  return (
    <div className="service-user-control" data-session={session}>
      {session === 'loading' && (
        <span className="service-user-loading" role="status">로그인 확인 중…</span>
      )}
      {session === 'guest' && (
        <button ref={loginButtonRef} className="service-login-link" type="button"
          aria-haspopup={mobile ? undefined : 'dialog'} onClick={() => { if (!mobile) setReplaySensitive('login_modal', true); setIsLoginOpen(true) }}>로그인</button>
      )}
      {session === 'error' && (
        <button ref={loginButtonRef} className="service-session-retry" type="button" aria-label="로그인 상태 다시 확인" onClick={() => void retrySessionCheck()}>
          {presentation === 'rail' ? '로그인 재확인' : '로그인 상태 다시 확인'}
        </button>
      )}
      {session === 'signed-in' && presentation === 'rail' && <button type="button" className="service-notification-inbox" aria-label="알림 보관함" aria-haspopup="dialog"
        onClick={(event) => { memberTrigger.current = event.currentTarget; setMemberView('inbox') }}>
        <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
          <path d="M4 4h16v16H4zM4 13h5l1 3h4l1-3h5" />
        </svg><span>{mobile ? '알림' : '알림 보관함'}</span><NotificationBadge />
      </button>}
      {session === 'signed-in' && (presentation === 'default' ? accountActions : <Link className="service-login-link" to="/mypage/bookmarks" onClick={() => { rememberExplorerPath(explorerReturn?.current ?? pathname + search + hash); captureProductEvent('account_menu_opened') }}>마이페이지</Link>)}
      {session === 'signed-in' && (memberView || inboxRequested) && <MemberMenuModal inboxOnly={presentation === 'rail'} key={memberView === 'account' ? 'account' : 'notifications'} view={memberView ?? 'inbox'} onClose={closeMember} returnFocusRef={memberTrigger}>
        {accountActions}
        {logoutError && <p role="alert">로그아웃하지 못했어요. 다시 시도해 주세요.</p>}
      </MemberMenuModal>}
      {logoutError && <span className="service-user-error" role="alert">로그아웃 실패</span>}
      {!mobile && (isLoginOpen || loginRequested || inboxRequested) && (session === 'guest' || session === 'error') && (
        <LoginModal entryPoint={isLoginOpen ? 'button' : loginResult === 'failed' ? 'failed_return' : 'required_redirect'} loginFailed={loginResult === 'failed'} sessionError={session === 'error'}
          onClose={closeLogin} returnFocusRef={loginButtonRef} />
      )}
    </div>
  )
}
