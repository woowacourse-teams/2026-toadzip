import { Navigate, Outlet } from 'react-router'
import { useAdminAuth } from './useAdminAuth'

export function RequireAdmin() {
  const { session, isLoading, error } = useAdminAuth()

  if (isLoading) {
    return <main className="admin-auth-state">관리자 인증 상태를 확인하고 있습니다.</main>
  }
  if (error) {
    return <main className="admin-auth-state">{error}</main>
  }
  if (!session) {
    return <Navigate to="/admin/login" replace />
  }
  return <Outlet />
}
