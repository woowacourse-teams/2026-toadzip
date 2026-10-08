import { setReplaySensitive } from '../../analytics/productAnalytics'
import { useLayoutEffect, useId, useRef, useState, type ReactNode, type RefObject } from 'react'
import { createPortal } from 'react-dom'
import { notificationPreparationTitle } from '../../public-housing/interest/NotificationInterestContext'
import { NotificationSettings } from '../../public-housing/interest/NotificationSettings'
import styles from './MemberMenuModal.module.css'

export function MemberMenuModal({ view, onClose, returnFocusRef, children }: {
  readonly view: 'inbox' | 'settings' | 'account'
  readonly onClose: () => void
  readonly returnFocusRef: RefObject<HTMLElement | null>
  readonly children?: ReactNode
}) {
  const dialogRef = useRef<HTMLDialogElement>(null)
  const closeRef = useRef<HTMLButtonElement>(null)
  const titleId = useId()
  const title = view === 'account' ? '마이페이지' : '알림 보관함'
  const [activeTab, setActiveTab] = useState<'received' | 'settings'>(view === 'settings' ? 'settings' : 'received')
  const receivedRef = useRef<HTMLButtonElement>(null)
  const settingsRef = useRef<HTMLButtonElement>(null)
  const tabId = useId()
  useLayoutEffect(() => {
    const previous = returnFocusRef.current ?? document.activeElement
    const dialog = dialogRef.current
    setReplaySensitive('member_menu', true)
    dialog?.showModal()
    closeRef.current?.focus({ preventScroll: true })
    return () => {
      dialog?.close()
      setReplaySensitive('member_menu', false)
      if (previous instanceof HTMLElement && previous.isConnected) previous.focus({ preventScroll: true })
    }
  }, [returnFocusRef])

  return createPortal(<dialog ref={dialogRef} className={`${styles.dialog} ph-no-capture`} aria-labelledby={titleId} aria-modal="true"
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
    {view !== 'account' ? <>
      <div className={styles.tabs} role="tablist" aria-label="알림 구분"
        onKeyDown={(event) => {
          if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
          event.preventDefault()
          const next = event.key === 'Home' ? 'received' : event.key === 'End' ? 'settings' : activeTab === 'received' ? 'settings' : 'received'
          setActiveTab(next)
          ;(next === 'received' ? receivedRef : settingsRef).current?.focus()
        }}>
        <button ref={receivedRef} id={`${tabId}-received`} type="button" role="tab" aria-selected={activeTab === 'received'}
          aria-controls={`${tabId}-panel-received`} tabIndex={activeTab === 'received' ? 0 : -1} onClick={() => setActiveTab('received')}>받은 알림</button>
        <button ref={settingsRef} id={`${tabId}-settings`} type="button" role="tab" aria-selected={activeTab === 'settings'}
          aria-controls={`${tabId}-panel-settings`} tabIndex={activeTab === 'settings' ? 0 : -1} onClick={() => setActiveTab('settings')}>알림 설정</button>
      </div>
      <div className={styles.panel} id={`${tabId}-panel-received`} role="tabpanel" aria-labelledby={`${tabId}-received`} hidden={activeTab !== 'received'} tabIndex={0}>
        {activeTab === 'received' && <section className={styles.preparation}>
          <div className={styles.illustration} aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round">
              <path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4" />
            </svg>
          </div>
          <h3>{notificationPreparationTitle}</h3>
        </section>}
      </div>
      <div className={styles.panel} id={`${tabId}-panel-settings`} role="tabpanel" aria-labelledby={`${tabId}-settings`} hidden={activeTab !== 'settings'} tabIndex={0}>
        {activeTab === 'settings' && <NotificationSettings />}
      </div>
      <div className={styles.footer}>
        <button className={styles.confirm} type="button" onClick={onClose}>확인</button>
      </div>
    </> : <div className={styles.account}>{children}</div>}
  </dialog>, document.body)
}
