import { Link, useSearchParams } from 'react-router'
import { usePrivacyNotices } from '../../privacy/usePrivacy'
import { consentStore } from '../../privacy/consentStore'
import { captureProductEvent, createAnalyticsId, setReplaySensitive } from '../../analytics/productAnalytics'
import { useLayoutEffect, useId, useRef, useState, type RefObject } from 'react'
import { createPortal } from 'react-dom'
import { IconButton } from '../../design-system/components/IconButton'
import { socialLoginUrl } from './api'
import styles from './LoginModal.module.css'

interface Props {
  readonly entryPoint?: 'button' | 'failed_return' | 'required_redirect'
  readonly loginFailed: boolean
  readonly sessionError: boolean
  readonly onClose: () => void
  readonly returnFocusRef: RefObject<HTMLButtonElement | null>
}

export function LoginModal({ loginFailed, sessionError, onClose, returnFocusRef, entryPoint = 'button' }: Props) {
  const [searchParams] = useSearchParams()
  const noticeChanged = searchParams.get('reason') === 'privacy-notice'
  const { notices, error: noticeError, retry: retryNotice } = usePrivacyNotices()
  const policyVersion = notices.find(notice => notice.key === 'PRIVACY_POLICY')?.version
  const [modalId] = useState(createAnalyticsId)
  const initialEntryPoint = useRef(entryPoint)
  const closing = useRef(false)
  const dialogRef = useRef<HTMLDialogElement>(null)
  const alertRef = useRef<HTMLDivElement>(null)
  const providerRef = useRef<HTMLAnchorElement>(null)
  const titleId = useId()
  const descriptionId = useId()

  useLayoutEffect(() => {
    const trigger = document.activeElement
    const returnTarget = returnFocusRef.current
    const dialog = dialogRef.current
    setReplaySensitive('login_modal', true)
    dialog?.showModal()
    captureProductEvent('login_modal_opened', { entry_point: initialEntryPoint.current, login_modal_id: modalId },
      { dedupeKey: `login-modal:${modalId}` })
    const initialFocus = alertRef.current ?? providerRef.current
    initialFocus?.focus({ preventScroll: true })
    return () => {
      dialog?.close()
      setReplaySensitive('login_modal', false)
      const target = returnTarget ?? trigger
      if (target instanceof HTMLElement && target.isConnected) target.focus({ preventScroll: true })
    }
  }, [returnFocusRef, modalId])

  function close(reason: 'button' | 'escape' | 'backdrop') {
    if (closing.current) return
    closing.current = true
    captureProductEvent('login_modal_closed', { login_modal_id: modalId, reason })
    onClose()
  }

  return createPortal(
    <dialog ref={dialogRef} className={`${styles.dialog} ph-no-capture`} aria-labelledby={titleId}
      aria-describedby={descriptionId}
      onCancel={(event) => { event.preventDefault(); close('escape') }}
      onKeyDown={(event) => event.stopPropagation()}
      onClick={(event) => {
        if (event.target !== event.currentTarget) return
        const rect = event.currentTarget.getBoundingClientRect()
        if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) close('backdrop')
      }}>
      <div className={styles.content}>
        <IconButton className={styles.close} label="로그인 닫기" onClick={() => close('button')}>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
            <path d="m5 5 14 14M19 5 5 19" />
          </svg>
        </IconButton>
        <header className={styles.header}>
          <span className={styles.brand}>공공주택 복덕방</span>
          <h2 id={titleId}>로그인</h2>
          <p id={descriptionId}>사용 중인 계정으로 간편하게 시작하세요.</p>
        </header>
        {(loginFailed || sessionError) && (
          <div ref={alertRef} className={styles.alert} role="alert" tabIndex={-1}>
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
              <circle cx="12" cy="12" r="9" /><path d="M12 7v6m0 3v1" />
            </svg>
            <strong>{noticeChanged ? '로그인 안내가 변경됐어요. 아래 안내를 확인하고 다시 시작해 주세요.' : loginFailed ? '로그인을 완료하지 못했습니다.' : '로그인 상태를 확인하지 못했습니다.'}</strong>
            <p>아래 버튼을 눌러 다시 시도해 주세요.</p>
          </div>
        )}
        <div className={styles.actions}>
          <a ref={providerRef} className={`${styles.provider} ${styles.kakao}`} href={policyVersion ? socialLoginUrl('kakao', policyVersion) : undefined} aria-disabled={!policyVersion} onClick={(event) => { if (!policyVersion) { event.preventDefault(); return }; captureProductEvent('login_provider_clicked', { provider: 'kakao', login_modal_id: modalId }); consentStore.beginAuthTransition('oauth') }}>
            <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
              <path d="M12 3C6.48 3 2 6.36 2 10.5c0 2.63 1.82 4.94 4.57 6.28l-1.16 4.13 4.73-2.98c.6.05 1.23.07 1.86.07 5.52 0 10-3.36 10-7.5S17.52 3 12 3Z" />
            </svg>
            카카오톡으로 로그인
          </a>
          <a className={`${styles.provider} ${styles.google}`} href={policyVersion ? socialLoginUrl('google', policyVersion) : undefined} aria-disabled={!policyVersion} onClick={(event) => { if (!policyVersion) { event.preventDefault(); return }; captureProductEvent('login_provider_clicked', { provider: 'google', login_modal_id: modalId }); consentStore.beginAuthTransition('oauth') }}>
            <img className={styles.googleMark} src="/auth/google-g.png" width="20" height="20" alt="" />
            Google로 로그인
          </a>
        </div>
        <p className={styles.policy}><Link to={policyVersion ? `/privacy/PRIVACY_POLICY/${policyVersion}` : '/privacy'}>개인정보처리방침</Link></p>
        {noticeError && <p role="alert">로그인 안내를 불러오지 못했어요. <button type="button" onClick={retryNotice}>다시 시도</button></p>}
      </div>
    </dialog>, document.body,
  )
}
