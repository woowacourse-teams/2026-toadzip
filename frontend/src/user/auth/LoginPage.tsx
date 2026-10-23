import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { setReplaySensitive } from '../../analytics/productAnalytics'
import { PrivacyLinks } from '../../privacy/Privacy'
import { getCurrentUser } from './api'
import { LoginContent } from './LoginContent'
import { clearLoginReturn, readLoginReturn, rememberLoginReturn, safeLoginReturn } from './loginReturn'
import styles from './LoginPage.module.css'

export function LoginPage() {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const returnTo = safeLoginReturn(params.get('returnTo') ?? readLoginReturn())
  const loginFailed = params.get('login') === 'failed'
  const [sessionError, setSessionError] = useState(false)
  const alertRef = useRef<HTMLDivElement>(null)
  const providerRef = useRef<HTMLAnchorElement>(null)
  const titleId = useId()
  const descriptionId = useId()
  useLayoutEffect(() => {
    setReplaySensitive('login_page', true)
    ;(alertRef.current ?? providerRef.current)?.focus({ preventScroll: true })
    return () => setReplaySensitive('login_page', false)
  }, [])
  useEffect(() => {
    let active = true
    getCurrentUser().then(user => {
      if (!active || !user) return
      clearLoginReturn()
      void navigate(returnTo, { replace: true })
    }).catch(() => { if (active) setSessionError(true) })
    return () => { active = false }
  }, [navigate, returnTo])
  const cancelUrl = new URL(returnTo, window.location.origin)
  cancelUrl.searchParams.delete('inbox')
  return <main className={`${styles.page} ph-no-capture`} aria-labelledby={titleId}>
    <Link className={styles.back} to={cancelUrl.pathname + cancelUrl.search + cancelUrl.hash} replace onClick={clearLoginReturn}>
      <span aria-hidden="true">← </span>지도로 돌아가기
    </Link>
    <div className={styles.content}>
      <LoginContent page loginFailed={loginFailed} sessionError={sessionError} titleId={titleId}
        descriptionId={descriptionId} alertRef={alertRef} providerRef={providerRef}
        onBeginLogin={() => rememberLoginReturn(returnTo, true)} />
    </div>
    <footer className={styles.footer}><PrivacyLinks /></footer>
  </main>
}
