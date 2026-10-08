import { useEffect, useId, useRef, type ReactNode, type RefObject } from 'react'
import { createPortal } from 'react-dom'
import { Link } from 'react-router'
import { notificationPreparationDescription, notificationPreparationNotice, notificationPreparationTitle } from '../../public-housing/interest/NotificationInterestContext'
import styles from './MemberMenuModal.module.css'

export function MemberMenuModal({ view, onClose, returnFocusRef, children }: {
  readonly view: 'inbox' | 'account'
  readonly onClose: () => void
  readonly returnFocusRef: RefObject<HTMLElement | null>
  readonly children?: ReactNode
}) {
  const dialogRef = useRef<HTMLDialogElement>(null)
  const closeRef = useRef<HTMLButtonElement>(null)
  const titleId = useId()
  const title = view === 'inbox' ? '알림 보관함' : '마이페이지'
  useEffect(() => {
    const previous = returnFocusRef.current ?? document.activeElement
    const dialog = dialogRef.current
    dialog?.showModal()
    closeRef.current?.focus({ preventScroll: true })
    return () => {
      dialog?.close()
      if (previous instanceof HTMLElement && previous.isConnected) previous.focus({ preventScroll: true })
    }
  }, [returnFocusRef])

  return createPortal(<dialog ref={dialogRef} className={styles.dialog} aria-labelledby={titleId}
    onCancel={(event) => { event.preventDefault(); onClose() }}
    onKeyDown={(event) => event.stopPropagation()}
    onClick={(event) => {
      if (event.target !== event.currentTarget) return
      const rect = event.currentTarget.getBoundingClientRect()
      if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) onClose()
    }}>
    <header className={styles.header}>
      <h2 id={titleId}>{title}</h2>
      <button ref={closeRef} className={styles.close} type="button" aria-label={`${title} 닫기`} onClick={onClose}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><path d="m6 6 12 12M6 18 18 6" /></svg>
      </button>
    </header>
    {view === 'inbox' ? <>
      <section className={styles.preparation}>
        <div className={styles.illustration} aria-hidden="true">
          <svg viewBox="0 0 48 48" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
            <path d="M15 19a9 9 0 0 1 18 0c0 10 4 12 4 12H11s4-2 4-12ZM20 36a4 4 0 0 0 8 0M23 7h2" />
            <path d="m36 11 3-3M38 18h4M9 11 6 8" />
          </svg>
        </div>
        <span className={styles.badge}>준비 중</span>
        <h3>{notificationPreparationTitle}</h3>
        <p>{notificationPreparationDescription}</p>
        <p className={styles.notice}>{notificationPreparationNotice}</p>
      </section>
      <div className={styles.footer}>
        <Link className={styles.management} aria-label="알림 관리" to="/mypage/notifications" onClick={onClose}>
          <span><strong>알림 관리</strong><small>저장한 관심 지역·단지·공고 확인하기</small></span><span aria-hidden="true">→</span>
        </Link>
        <button className={styles.confirm} type="button" onClick={onClose}>확인</button>
      </div>
    </> : <div className={styles.account}>{children}</div>}
  </dialog>, document.body)
}
