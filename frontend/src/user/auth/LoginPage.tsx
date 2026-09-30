import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { BrandLink } from '../../BrandLink'
import { getCurrentUser, logoutUser, socialLoginUrl } from './api'

type SessionState = 'loading' | 'guest' | 'signed-in' | 'error'

export function LoginPage() {
  const [searchParams] = useSearchParams()
  const [session, setSession] = useState<SessionState>('loading')
  const [message, setMessage] = useState<string | null>(null)
  const [isLoggingOut, setIsLoggingOut] = useState(false)
  const loginFailed = searchParams.get('login') === 'failed'

  useEffect(() => {
    let active = true
    getCurrentUser().then(
      (user) => { if (active) setSession(user ? 'signed-in' : 'guest') },
      () => { if (active) setSession('error') },
    )
    return () => { active = false }
  }, [])

  async function handleLogout() {
    setIsLoggingOut(true)
    setMessage(null)
    try {
      await logoutUser()
      setSession('guest')
    } catch {
      setMessage('로그아웃하지 못했습니다. 다시 시도해 주세요.')
    } finally {
      setIsLoggingOut(false)
    }
  }

  return (
    <div className="user-login-page">
      <header className="user-login-header">
        <BrandLink showName />
        <Link className="user-login-back" to="/">지도로 돌아가기</Link>
      </header>
      <main className="user-login-main">
        <section className="user-login-intro" aria-label="서비스 소개">
          <h1>공공주택, 한눈에.</h1>
          <img className="user-login-product-preview" src="/login-product-preview.svg" alt="" aria-hidden="true" />
        </section>
        <section className="user-login-panel" aria-label="로그인">
          <div className="user-login-content">
            <h2>{session === 'signed-in' ? '로그인되었습니다' : '로그인'}</h2>
            {session === 'signed-in' ? (
              <>
                <p>이제 관심 있는 주택을 둘러보세요.</p>
                <Link className="user-login-map-link" to="/">지도 둘러보기</Link>
                <button className="user-login-logout" type="button" onClick={() => void handleLogout()} disabled={isLoggingOut}>
                  {isLoggingOut ? '로그아웃 중…' : '로그아웃'}
                </button>
              </>
            ) : (
              <>
                <p>카카오 또는 Google 계정으로 시작하세요.</p>
                {loginFailed && <p className="user-login-alert" role="alert">로그인을 완료하지 못했습니다. 다시 시도해 주세요.</p>}
                {session === 'error' && <p className="user-login-alert" role="alert">로그인 상태를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.</p>}
                {session === 'loading' ? (
                  <p role="status">로그인 상태를 확인하는 중…</p>
                ) : (
                  <div className="user-login-actions">
                    <a className="user-login-provider user-login-provider--kakao" href={socialLoginUrl('kakao')}>
                      <img className="user-login-provider-icon" src="/auth/kakao-symbol.svg" alt="" aria-hidden="true" />
                      <span>카카오로 계속하기</span>
                    </a>
                    <a className="user-login-provider user-login-provider--google" href={socialLoginUrl('google')}>
                      <img className="user-login-provider-icon" src="/auth/google-g.png" alt="" aria-hidden="true" />
                      <span>Google로 계속하기</span>
                    </a>
                  </div>
                )}
                <p className="user-login-note">로그인을 진행하면 각 서비스의 인증 화면으로 이동합니다.</p>
              </>
            )}
            {message && <p className="user-login-alert" role="alert">{message}</p>}
          </div>
        </section>
      </main>
    </div>
  )
}
