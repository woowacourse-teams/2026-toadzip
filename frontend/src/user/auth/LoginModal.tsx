import { captureProductEvent, createAnalyticsId, setReplaySensitive } from '../../analytics/productAnalytics'
import { useLayoutEffect, useId, useRef, useState, type RefObject } from 'react'
import { createPortal } from 'react-dom'
import { IconButton } from '../../design-system/components/IconButton'
import { LoginContent } from './LoginContent'
import styles from './LoginModal.module.css'

interface Props {
  readonly entryPoint?: 'button' | 'failed_return' | 'required_redirect'
  readonly loginFailed: boolean
  readonly sessionError: boolean
  readonly onClose: () => void
  readonly returnFocusRef: RefObject<HTMLButtonElement | null>
}

export function LoginModal({ loginFailed, sessionError, onClose, returnFocusRef, entryPoint = 'button' }: Props) {
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
        <LoginContent loginFailed={loginFailed} sessionError={sessionError} titleId={titleId}
          descriptionId={descriptionId} alertRef={alertRef} providerRef={providerRef} modalId={modalId} />
      </div>
    </dialog>, document.body,
  )
}
