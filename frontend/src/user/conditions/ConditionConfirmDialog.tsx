import { useId, useRef } from 'react'
import { Button } from '../../design-system/components/Button'
import { ConditionModal } from './ConditionModal'
import styles from './ConditionConfirmDialog.module.css'

export function ConditionConfirmDialog({ title, body, cancelLabel, confirmLabel, danger = false, confirmDisabled = false, onCancel, onConfirm, onDismiss }: {
  readonly title: string
  readonly body: string
  readonly cancelLabel: string
  readonly confirmLabel: string
  readonly danger?: boolean
  readonly confirmDisabled?: boolean
  readonly onCancel: () => void
  readonly onConfirm: () => void
  /** Esc로 닫을 때. 없으면 onCancel */
  readonly onDismiss?: () => void
}) {
  const titleId = useId()
  const titleRef = useRef<HTMLHeadingElement>(null)
  const bodyId = useId()
  return <ConditionModal variant="confirm" role="alertdialog" labelledBy={titleId} describedBy={bodyId} focusRef={titleRef} onCancel={onDismiss ?? onCancel}>
    <h2 id={titleId} ref={titleRef} tabIndex={-1} className={styles.title}>{title}</h2>
    <p id={bodyId} className={styles.body}>{body}</p>
    <div className={styles.actions}>
      <Button variant="secondary" onClick={onCancel}>{cancelLabel}</Button>
      <Button className={danger ? styles.danger : undefined} variant={danger ? 'secondary' : 'primary'} disabled={confirmDisabled} onClick={onConfirm}>{confirmLabel}</Button>
    </div>
  </ConditionModal>
}
