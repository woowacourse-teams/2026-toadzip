import { useState, type FormEvent } from 'react'
import { Navigate, useNavigate } from 'react-router'
import { useAdminAuth } from './useAdminAuth'

export function LoginPage() {
  const { session, isLoading, login } = useAdminAuth()
  const navigate = useNavigate()
  const [loginIdentifier, setLoginIdentifier] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [isSubmitting, setIsSubmitting] = useState(false)

  if (!isLoading && session) {
    return <Navigate to="/admin" replace />
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
      navigate('/admin', { replace: true })
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
        {error ? <p className="form-error">{error}</p> : null}
        <button disabled={isSubmitting} type="submit">
          {isSubmitting ? '로그인 중…' : '로그인'}
        </button>
      </form>
    </main>
  )
}
