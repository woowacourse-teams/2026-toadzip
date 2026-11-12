import { useLayoutEffect, useRef, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import styles from './ConditionModal.module.css'

/** 화면 위에 뜨는 창. 열 때 제목으로 초점을 옮기고 닫으면 원래 자리로 돌려준다. */
export function ConditionModal({ labelledBy, describedBy, variant, role, onCancel, children, focusRef }: {
  readonly labelledBy: string
  readonly describedBy?: string
  /** full: 3단계 입력 · sheet: 부분 입력·유도 · confirm: 확인 */
  readonly variant: 'full' | 'sheet' | 'confirm'
  readonly role?: 'dialog' | 'alertdialog'
  readonly onCancel: () => void
  readonly children: ReactNode
  readonly focusRef?: React.RefObject<HTMLElement | null>
}) {
  const dialogRef = useRef<HTMLDialogElement>(null)
  useLayoutEffect(() => {
    const previous = document.activeElement
    const dialog = dialogRef.current
    dialog?.showModal()
    focusRef?.current?.focus({ preventScroll: true })
    return () => {
      dialog?.close()
      if (previous instanceof HTMLElement && previous.isConnected) previous.focus({ preventScroll: true })
    }
  }, [focusRef])
  return createPortal(
    <dialog ref={dialogRef} role={role} aria-labelledby={labelledBy} aria-describedby={describedBy} className={`${styles.dialog} ${styles[variant]} ph-no-capture`}
      onCancel={(event) => { event.preventDefault(); onCancel() }}
      onKeyDown={(event) => event.stopPropagation()}>
      {children}
    </dialog>,
    document.body,
  )
}
