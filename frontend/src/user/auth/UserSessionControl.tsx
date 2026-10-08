import { captureProductEvent, setProductAuthState, setReplaySensitive } from '../../analytics/productAnalytics'
import { useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate, useSearchParams } from 'react-router'
import { getCurrentUser, logoutUser } from './api'
import { LoginModal } from './LoginModal'

type SessionState = 'loading' | 'guest' | 'signed-in' | 'error'

export function UserSessionControl({ onLogout, presentation = 'default' }: { readonly onLogout?: () => void; readonly presentation?: 'default' | 'rail' }) {
  const [session, setSession] = useState<SessionState>('loading')
  const [isLoggingOut, setIsLoggingOut] = useState(false)
  const [logoutError, setLogoutError] = useState(false)
  const [isLoginOpen, setIsLoginOpen] = useState(false)
  const [searchParams] = useSearchParams()
  const { hash } = useLocation()
  const navigate = useNavigate()
  const loginButtonRef = useRef<HTMLButtonElement>(null)
  const loginResult = searchParams.get('login')
  const loginRequested = loginResult === 'required' || loginResult === 'failed'
  const remainingParams = new URLSearchParams(searchParams)
  remainingParams.delete('login')
  const remainingSearch = remainingParams.toString()

  useEffect(() => {
    setProductAuthState(session === 'signed-in' ? 'member' : session === 'guest' ? 'guest' : 'unknown')
  }, [session])

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

  function closeLogin() {
    setIsLoginOpen(false)
    if (!loginRequested) return
    void navigate({ search: remainingSearch, hash }, { replace: true })
  }

  const accountActions = <>
    <span className="service-login-status"><span aria-hidden="true" />로그인됨</span>
    <button className="service-logout-button" type="button" disabled={isLoggingOut}
      onClick={() => void handleLogout()}>
      {isLoggingOut ? '로그아웃 중…' : '로그아웃'}
    </button>
  </>

  return (
    <div className="service-user-control">
      {session === 'loading' && (
        <span className="service-user-loading" role="status">로그인 확인 중…</span>
      )}
      {session === 'guest' && (
        <button ref={loginButtonRef} className="service-login-link" type="button"
          aria-haspopup="dialog" onClick={() => { setReplaySensitive('login_modal', true); setIsLoginOpen(true) }}>로그인</button>
      )}
      {session === 'error' && (
        <button ref={loginButtonRef} className="service-session-retry" type="button" aria-label="로그인 상태 다시 확인" onClick={() => void retrySessionCheck()}>
          {presentation === 'rail' ? '로그인 재확인' : '로그인 상태 다시 확인'}
        </button>
      )}
      {session === 'signed-in' && (
        presentation === 'rail' ? <details className="service-account-menu" onToggle={(event) => {
          if (event.currentTarget.open) captureProductEvent('account_menu_opened')
        }}>
          <summary className="service-login-link">마이페이지</summary>
          <div className="service-account-menu__panel" aria-label="계정 관리">{accountActions}</div>
        </details> : accountActions
      )}
      {logoutError && <span className="service-user-error" role="alert">로그아웃 실패</span>}
      {(isLoginOpen || loginRequested) && (session === 'guest' || session === 'error') && (
        <LoginModal entryPoint={isLoginOpen ? 'button' : loginResult === 'failed' ? 'failed_return' : 'required_redirect'} loginFailed={loginResult === 'failed'} sessionError={session === 'error'}
          onClose={closeLogin} returnFocusRef={loginButtonRef} />
      )}
    </div>
  )
}
