import { Button } from '../../design-system/components/Button'
import styles from './RequestError.module.css'

export function RequestError({ title, onRetry, retryLabel = '다시 시도' }: {
  readonly title: string; readonly onRetry: () => void; readonly retryLabel?: string
}) {
  return <div className={styles.error} role="alert"><div><strong>{title}</strong><p>잠시 후 다시 시도해 주세요.</p></div><Button onClick={onRetry}><svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M3 11a9 9 0 0 1 15.36-5.36L21 8M21 3v5h-5M21 13a9 9 0 0 1-15.36 5.36L3 16M3 21v-5h5" /></svg>{retryLabel}</Button></div>
}
