import { Navigate, Outlet, useLocation } from 'react-router'
import { BrandLink } from '../../BrandLink'
import { useAdminAuth } from './useAdminAuth'

export function RequireAdmin() {
  const { session, isLoading, error } = useAdminAuth()
  const location = useLocation()

  if (isLoading) {
    return <main className="admin-auth-state"><BrandLink /><p>관리자 인증 상태를 확인하고 있습니다.</p></main>
  }
  if (error) {
    return <main className="admin-auth-state"><BrandLink /><p>{error}</p></main>
  }
  if (!session) {
    // 로그인 후 보던 화면(검색 조건·오류 정보 포함)으로 돌아가도록 원래 주소를 넘긴다.
    return <Navigate to="/admin/login" replace state={{ from: `${location.pathname}${location.search}` }} />
  }
  return <Outlet />
}
