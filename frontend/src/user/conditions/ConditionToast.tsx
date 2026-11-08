import { useEffect } from 'react'
import { createPortal } from 'react-dom'
import styles from './ConditionToast.module.css'

export function ConditionToast({ message, onDone }: { readonly message: { readonly text: string; readonly key: number } | null; readonly onDone: () => void }) {
  useEffect(() => {
    if (!message) return
    const timer = setTimeout(onDone, 3600)
    return () => clearTimeout(timer)
  }, [message, onDone])
  if (!message) return null
  return createPortal(<div className={styles.region} role="status" aria-live="polite">
    <p key={message.key} className={styles.toast}>{message.text}</p>
  </div>, document.body)
}
