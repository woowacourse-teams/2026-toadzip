import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
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
        <Link className="brand-link" to="/" aria-label="두꺼비집 홈">
          <span className="brand-mark" aria-hidden="true">⌂</span>
          <span className="brand-name">두꺼비집</span>
        </Link>
        <Link className="user-login-back" to="/">지도로 돌아가기</Link>
      </header>
      <main className="user-login-main">
        <section className="user-login-intro" aria-label="서비스 소개">
          <span className="user-login-eyebrow">내게 맞는 공공주택을 찾는 곳</span>
          <h1>좋은 집을 찾는 시간,<br />두꺼비집과 함께.</h1>
          <p>관심 있는 집과 모집 공고를 한곳에서 살펴보세요.</p>
          <div className="user-login-illustration" aria-hidden="true">
            <span className="user-login-sun" />
            <span className="user-login-house user-login-house--back" />
            <span className="user-login-house user-login-house--front" />
            <span className="user-login-ground" />
          </div>
        </section>
        <section className="user-login-panel" aria-label="로그인">
          <div className="user-login-content">
            <span className="user-login-step">두꺼비집 시작하기</span>
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
                <p>사용 중인 계정으로 간편하게 시작하세요.</p>
                {loginFailed && <p className="user-login-alert" role="alert">로그인을 완료하지 못했습니다. 다시 시도해 주세요.</p>}
                {session === 'error' && <p className="user-login-alert" role="alert">로그인 상태를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.</p>}
                {session === 'loading' ? (
                  <p role="status">로그인 상태를 확인하는 중…</p>
                ) : (
                  <div className="user-login-actions">
                    <a className="user-login-provider user-login-provider--kakao" href={socialLoginUrl('kakao')}>
                      <span className="user-login-provider-mark" aria-hidden="true">●</span>카카오로 계속하기
                    </a>
                    <a className="user-login-provider user-login-provider--google" href={socialLoginUrl('google')}>
                      <span className="user-login-google-mark" aria-hidden="true">G</span>Google로 계속하기
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
