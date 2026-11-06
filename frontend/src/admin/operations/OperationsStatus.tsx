import type { OperationsQuery } from './useOperationsQuery'
import styles from './OperationsHome.module.css'

import { operationTime } from './presentation'

export function CheckedAt({ value }: { value: string | null }) {
  return value ? <p className={styles.checked}>조회 <time dateTime={value}>{operationTime(value)}</time> · 한국 시각</p> : null
}
export function OperationsStatus({ query }: { query: OperationsQuery<unknown> }) {
  if (query.pending) return <p className={styles.message} role="status">현황을 불러오는 중…</p>
  if (query.error) return <div className={styles.error}><p role="alert">조회 실패: {query.error}</p>
    <button type="button" onClick={query.retry}>다시 불러오기</button></div>
  return null
}
