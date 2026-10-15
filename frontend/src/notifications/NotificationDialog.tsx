import { useId, useLayoutEffect, useRef, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { IconButton } from '../design-system/components/IconButton'
import styles from './NotificationDialog.module.css'

export function NotificationDialog({ title, children, onClose }: {
  readonly title: string; readonly children: ReactNode; readonly onClose: () => void
}) {
  const dialogRef = useRef<HTMLDialogElement>(null)
  const titleRef = useRef<HTMLHeadingElement>(null)
  const titleId = useId()
  useLayoutEffect(() => {
    const previous = document.activeElement
    const dialog = dialogRef.current
    dialog?.showModal()
    titleRef.current?.focus()
    return () => {
      dialog?.close()
      if (previous instanceof HTMLElement && previous.isConnected) previous.focus({ preventScroll: true })
    }
  }, [])
  return createPortal(<dialog ref={dialogRef} className={`${styles.dialog} ph-no-capture`} aria-labelledby={titleId}
    onCancel={event => { event.preventDefault(); event.stopPropagation(); onClose() }} onKeyDown={event => event.stopPropagation()}>
    <header><h2 id={titleId} ref={titleRef} tabIndex={-1}>{title}</h2>
      <IconButton label="안내 닫기" onClick={onClose}><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><path d="m6 6 12 12M6 18 18 6" /></svg></IconButton>
    </header>
    {children}
  </dialog>, document.body)
}
