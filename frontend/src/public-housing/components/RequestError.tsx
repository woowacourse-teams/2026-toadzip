import { Button } from '../../design-system/components/Button'
import styles from './RequestError.module.css'

export function RequestError({ title, onRetry, retryLabel = '다시 시도' }: {
  readonly title: string; readonly onRetry: () => void; readonly retryLabel?: string
}) {
  return <div className={styles.error} role="alert"><div><strong>{title}</strong><p>잠시 후 다시 시도해 주세요.</p></div><Button onClick={onRetry}><svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"><path d="M20 7v5h-5M4 17v-5h5M5 8a8 8 0 0 1 14-2l1 6M4 12l1 6a8 8 0 0 0 14-2" /></svg>{retryLabel}</Button></div>
}
