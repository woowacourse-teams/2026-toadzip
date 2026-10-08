import { useEffect, useRef, useState } from 'react'
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router'
import { getCurrentUser, logoutUser } from './api'
import { LoginModal } from './LoginModal'
import { MemberMenuModal } from './MemberMenuModal'
import { useMobileViewport } from '../../public-housing/components/useMobileViewport'

type SessionState = 'loading' | 'guest' | 'signed-in' | 'error'

export function UserSessionControl({ onLogout, presentation = 'default', sessionOverride, onSessionRetry }: { readonly onLogout?: () => void; readonly presentation?: 'default' | 'rail'; readonly sessionOverride?: SessionState; readonly onSessionRetry?: () => void }) {
  const [localSession, setSession] = useState<SessionState>('loading')
  const session = sessionOverride ?? localSession
  const [isLoggingOut, setIsLoggingOut] = useState(false)
  const [logoutError, setLogoutError] = useState(false)
  const [isLoginOpen, setIsLoginOpen] = useState(false)
  const [memberView, setMemberView] = useState<'inbox' | 'account' | null>(null)
  const memberTrigger = useRef<HTMLButtonElement>(null)
  const mobile = useMobileViewport()
  const [searchParams] = useSearchParams()
  const { hash } = useLocation()
  const navigate = useNavigate()
  const loginButtonRef = useRef<HTMLButtonElement>(null)
  const inboxRequested = searchParams.get('inbox') === 'open'
  const loginResult = searchParams.get('login')
  const loginRequested = loginResult === 'required' || loginResult === 'failed'
  const remainingParams = new URLSearchParams(searchParams)
  remainingParams.delete('login')
  const remainingSearch = remainingParams.toString()

  useEffect(() => {
    if (session !== 'signed-in' || !loginRequested) return
    void navigate({ search: remainingSearch, hash }, { replace: true })
  }, [session, loginRequested, remainingSearch, hash, navigate])

  useEffect(() => {
    let active = true
    getCurrentUser().then(
      (user) => { if (active) setSession(user ? 'signed-in' : 'guest') },
      () => { if (active) setSession('error') },
    )
    return () => { active = false }
  }, [])

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
    setIsLoggingOut(true)
    setLogoutError(false)
    try {
      await logoutUser()
      setSession('guest')
      onLogout?.()
    } catch {
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
    params.delete('inbox')
    void navigate({ search: params.toString(), hash }, { replace: true })
  }

  const accountActions = <>
    <Link className="service-notification-management" to="/mypage/notifications">알림 관리</Link>
    <Link className="service-account-feedback" to="/feedback">제보/의견 보내기</Link>
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
          aria-haspopup="dialog" onClick={() => setIsLoginOpen(true)}>로그인</button>
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
        </svg><span>{mobile ? '알림' : '알림 보관함'}</span>
      </button>}
      {session === 'signed-in' && (
        presentation === 'rail' ? mobile ? <button className="service-login-link" type="button" aria-haspopup="dialog"
          onClick={(event) => { memberTrigger.current = event.currentTarget; setMemberView('account') }}>마이</button> : <details className="service-account-menu">
          <summary className="service-login-link">마이페이지</summary>
          <div className="service-account-menu__panel" aria-label="계정 관리">{accountActions}</div>
        </details> : accountActions
      )}
      {session === 'signed-in' && (memberView || inboxRequested) && <MemberMenuModal view={memberView ?? 'inbox'} onClose={closeMember} returnFocusRef={memberTrigger}>
        {accountActions}
        {logoutError && <p role="alert">로그아웃하지 못했어요. 다시 시도해 주세요.</p>}
      </MemberMenuModal>}
      {logoutError && <span className="service-user-error" role="alert">로그아웃 실패</span>}
      {(isLoginOpen || loginRequested || inboxRequested) && (session === 'guest' || session === 'error') && (
        <LoginModal loginFailed={loginResult === 'failed'} sessionError={session === 'error'}
          onClose={closeLogin} returnFocusRef={loginButtonRef} />
      )}
    </div>
  )
}
