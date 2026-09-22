import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { getCurrentUser, logoutUser } from './api'

type SessionState = 'loading' | 'guest' | 'signed-in' | 'error'

export function UserSessionControl() {
  const [session, setSession] = useState<SessionState>('loading')
  const [isLoggingOut, setIsLoggingOut] = useState(false)
  const [logoutError, setLogoutError] = useState(false)

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
    setIsLoggingOut(true)
    setLogoutError(false)
    try {
      await logoutUser()
      setSession('guest')
    } catch {
      setLogoutError(true)
    } finally {
      setIsLoggingOut(false)
    }
  }

  return (
    <div className="service-user-control">
      {session === 'loading' && (
        <span className="service-user-loading" role="status">로그인 확인 중…</span>
      )}
      {session === 'guest' && (
        <Link className="service-login-link" to="/login">로그인</Link>
      )}
      {session === 'error' && (
        <button className="service-session-retry" type="button" onClick={() => void retrySessionCheck()}>
          로그인 상태 다시 확인
        </button>
      )}
      {session === 'signed-in' && (
        <>
          <span className="service-login-status">
            <span aria-hidden="true" />
            로그인됨
          </span>
          <button
            className="service-logout-button"
            type="button"
            disabled={isLoggingOut}
            onClick={() => void handleLogout()}
          >
            {isLoggingOut ? '로그아웃 중…' : '로그아웃'}
          </button>
        </>
      )}
      {logoutError && <span className="service-user-error" role="alert">로그아웃 실패</span>}
    </div>
  )
}
