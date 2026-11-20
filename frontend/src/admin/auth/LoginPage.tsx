import { Navigate, useLocation } from 'react-router'
import { loginPageUrl } from '../../user/auth/loginReturn'

export function LoginPage() {
  const returnTo = adminReturnPath(useLocation().state)
  return <Navigate to={loginPageUrl(returnTo)} replace />
}

// 관리자 화면 안의 주소로만 돌아간다. 외부 주소나 로그인 화면 자체는 기본 화면으로 보낸다.
function adminReturnPath(state: unknown) {
  const from = typeof state === 'object' && state !== null && 'from' in state ? state.from : null
  if (typeof from !== 'string' || !/^\/admin(?:[/?#]|$)/.test(from) || from.startsWith('/admin/login')) return '/admin'
  return from
}
