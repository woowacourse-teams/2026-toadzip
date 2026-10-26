import { Button } from '../../design-system/components/Button'
import styles from './RequestError.module.css'

export function RequestError({ title, onRetry, retryLabel = '다시 시도' }: {
  readonly title: string; readonly onRetry: () => void; readonly retryLabel?: string
}) {
  return <div className={styles.error} role="alert"><div><strong>{title}</strong><p>잠시 후 다시 시도해 주세요.</p></div><Button onClick={onRetry}><svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M20 7v5h-5M20 12a8 8 0 1 0-2.34 5.66" /></svg>{retryLabel}</Button></div>
}
