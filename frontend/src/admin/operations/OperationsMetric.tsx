import { Link } from 'react-router'
import type { OperationsQuery } from './useOperationsQuery'
import styles from './OperationsHome.module.css'
import { operationCount } from './presentation'

export function OperationsMetric({ title, value, query, href, primary = false, blue = false, pendingFilter = false }: {
  title: string; value: number | null | undefined; query: OperationsQuery<unknown>; href: string; primary?: boolean; blue?: boolean; pendingFilter?: boolean
}) {
  return <Link className={`${styles.metric} ${primary ? styles.primary : ''} ${blue ? styles.blue : ''}`} to={href}
    aria-label={`${title} ${operationCount(value, query)}`} title={pendingFilter ? '해당 필터 추가 예정 · 기존 관리 목록으로 이동' : undefined}>
    <span className={styles.metricLabel}>{title}<span aria-hidden="true">↗</span></span>
    <strong className={styles.number} data-unavailable={value == null || query.pending || !!query.error} aria-label={`${title} 건수`}>{operationCount(value, query)}</strong>
  </Link>
}
