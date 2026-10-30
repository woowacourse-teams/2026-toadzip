import { useState, type FormEvent } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router'
import { BrandLink } from '../../BrandLink'
import { useAdminAuth } from './useAdminAuth'

export function LoginPage() {
  const { session, isLoading, login } = useAdminAuth()
  const navigate = useNavigate()
  const returnTo = adminReturnPath(useLocation().state)
  const [loginIdentifier, setLoginIdentifier] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [isSubmitting, setIsSubmitting] = useState(false)

  if (!isLoading && session) {
    return <Navigate to={returnTo} replace />
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    void submitLogin()
  }

  async function submitLogin() {
    setError(null)
    setIsSubmitting(true)
    try {
      await login(loginIdentifier, password)
      navigate(returnTo, { replace: true })
    } catch (requestError) {
      if (requestError instanceof Error) {
        setError(requestError.message)
      }
      if (!(requestError instanceof Error)) {
        setError('로그인 요청을 처리하지 못했습니다.')
      }
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <main className="admin-login-page">
      <form className="admin-login-form" onSubmit={handleSubmit}>
        <BrandLink className="admin-login-brand" />
        <h1>관리자 로그인</h1>
        <label>
          로그인 식별자
          <input
            autoComplete="username"
            onChange={(event) => setLoginIdentifier(event.target.value)}
            required
            value={loginIdentifier}
          />
        </label>
        <label>
          비밀번호
          <input
            autoComplete="current-password"
            onChange={(event) => setPassword(event.target.value)}
            required
            type="password"
            value={password}
          />
        </label>
        {/* 실패 문구가 나타나도 폼 높이가 바뀌지 않도록 문구 줄을 항상 둔다. */}
        <p className="form-error admin-login-error">{error}</p>
        <button disabled={isSubmitting} type="submit">
          {isSubmitting ? '로그인 중…' : '로그인'}
        </button>
      </form>
    </main>
  )
}

// 관리자 화면 안의 주소로만 돌아간다. 외부 주소나 로그인 화면 자체는 기본 화면으로 보낸다.
function adminReturnPath(state: unknown) {
  const from = typeof state === 'object' && state !== null && 'from' in state ? state.from : null
  if (typeof from !== 'string' || !/^\/admin(?:[/?]|$)/.test(from) || from.startsWith('/admin/login')) return '/admin'
  return from
}
