import { Link, useSearchParams } from 'react-router'

export function FailureReturnLink() {
  const [params] = useSearchParams()
  const listParams = new URLSearchParams(params.get('returnTo')?.split('?')[1] ?? '')
  const returnTo = params.get('failureReturnTo') ?? listParams.get('failureReturnTo')
  if (!returnTo || !/^\/admin\/failures(?:\?|$)/.test(returnTo)) return null
  return <nav aria-label="진행 중인 오류 처리" className="management-heading">
    <Link to={returnTo}>오류 처리로 돌아가 결과 확인</Link>
  </nav>
}
