import { PrivacyLinks } from '../../privacy/Privacy'
import type { RefObject } from 'react'
import { usePrivacyNotices } from '../../privacy/usePrivacy'
import { consentStore } from '../../privacy/consentStore'
import { captureProductEvent } from '../../analytics/productAnalytics'
import { socialLoginUrl } from './api'
import { LoginBrand } from './LoginBrand'
import styles from './LoginModal.module.css'

interface Props {
  loginFailed: boolean
  sessionError: boolean
  titleId: string
  descriptionId: string
  alertRef: RefObject<HTMLDivElement | null>
  providerRef: RefObject<HTMLAnchorElement | null>
  modalId?: string
  page?: boolean
  onBeginLogin?: () => void
}

export function LoginContent({ loginFailed, sessionError, titleId, descriptionId, alertRef, providerRef, modalId, page = false, onBeginLogin }: Props) {
  const { notices, error: noticeError, retry: retryNotice } = usePrivacyNotices()
  const policyVersion = notices.find(notice => notice.key === 'PRIVACY_POLICY')?.version
  return <>
        {!page && <>
          <header className={styles.header}><h2 id={titleId}>로그인</h2></header>
          <LoginBrand descriptionId={descriptionId} />
        </>}
        {(loginFailed || sessionError) && (
          <div ref={alertRef} className={styles.alert} role="alert" tabIndex={-1}>
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
              <circle cx="12" cy="12" r="9" /><path d="M12 7v6m0 3v1" />
            </svg>
            <strong>{loginFailed ? '로그인을 완료하지 못했어요.' : '로그인 상태를 확인하지 못했어요.'}</strong>
            {loginFailed && <p>인증이 취소되었거나 로그인 요청이 만료되었을 수 있어요.</p>}
            <p>아래 버튼을 눌러 다시 시도해 주세요.</p>
          </div>
        )}
        <div className={styles.actions}>
          <a ref={providerRef} className={`${styles.provider} ${styles.kakao}`} href={socialLoginUrl('kakao', policyVersion)} onClick={() => { captureProductEvent('login_provider_clicked', { provider: 'kakao', ...(page ? { surface: 'login_page' } : { login_modal_id: modalId }) }); onBeginLogin?.(); consentStore.beginAuthTransition('oauth') }}>
            <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
              <path d="M12 3C6.48 3 2 6.36 2 10.5c0 2.63 1.82 4.94 4.57 6.28l-1.16 4.13 4.73-2.98c.6.05 1.23.07 1.86.07 5.52 0 10-3.36 10-7.5S17.52 3 12 3Z" />
            </svg>
            카카오톡으로 로그인
          </a>
          <a className={`${styles.provider} ${styles.google}`} href={socialLoginUrl('google', policyVersion)} onClick={() => { captureProductEvent('login_provider_clicked', { provider: 'google', ...(page ? { surface: 'login_page' } : { login_modal_id: modalId }) }); onBeginLogin?.(); consentStore.beginAuthTransition('oauth') }}>
            <img className={styles.googleMark} src="/auth/google-g.png" width="20" height="20" alt="" />
            Google로 로그인
          </a>
        </div>
        {!page && <PrivacyLinks policyVersion={policyVersion} />}
        {noticeError && <p role="alert">개인정보처리방침 안내를 불러오지 못했어요. 로그인은 계속할 수 있어요. <button type="button" onClick={retryNotice}>다시 시도</button></p>}
  </>
}
