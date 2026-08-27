import { useState } from 'react'
import { Outlet, useNavigate } from 'react-router'
import { useAdminAuth } from './useAdminAuth'

export function AdminLayout() {
  const { logout, session } = useAdminAuth()
  const navigate = useNavigate()
  const [error, setError] = useState<string | null>(null)

  function handleLogout() {
    void signOut()
  }

  async function signOut() {
    setError(null)
    try {
      await logout()
      navigate('/admin/login', { replace: true })
    } catch (requestError) {
      if (requestError instanceof Error) {
        setError(requestError.message)
      }
      if (!(requestError instanceof Error)) {
        setError('로그아웃 요청을 처리하지 못했습니다.')
      }
    }
  }

  return (
    <div className="admin-layout">
      <header className="admin-header">
        <span>두꺼비집 관리자</span>
        <div>
          <span>{session?.loginIdentifier}</span>
          <button onClick={handleLogout} type="button">로그아웃</button>
        </div>
      </header>
      {error ? <p className="form-error admin-layout-error">{error}</p> : null}
      <main className="admin-content">
        <Outlet />
      </main>
    </div>
  )
}
