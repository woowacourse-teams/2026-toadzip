import { Link } from 'react-router'
import { loadNotificationSubscriptions, loadNotificationTrend } from '../notifications/api'
import { listDeliveries } from '../notifications/deliveryApi'
import type { OperationsHelp } from './OperationsHome'
import { OperationsIcon } from './OperationsIcon'
import { OperationsMetric } from './OperationsMetric'
import { comparison, operationCount } from './presentation'
import { OperationsTrend } from './OperationsTrend'
import { CheckedAt, OperationsStatus } from './OperationsStatus'
import { useOperationsQuery } from './useOperationsQuery'
import styles from './OperationsHome.module.css'

const loadSummary = (signal: AbortSignal) => loadNotificationSubscriptions(new URLSearchParams({ page: '0', size: '1', status: 'ACTIVE' }), signal)
const loadTrend = (signal: AbortSignal) => loadNotificationTrend('days=90', signal)
const loadFailures = (signal: AbortSignal) => listDeliveries(new URLSearchParams({ status: 'FAILED', page: '0', size: '1' }), signal)
const loadRetries = (signal: AbortSignal) => listDeliveries(new URLSearchParams({ status: 'RETRY', page: '0', size: '1' }), signal)
function total(points: { activations: number | null; cancellations: number | null }[], days: number, key: 'activations' | 'cancellations') {
  return points.length !== days || points.some(point => point[key] === null) ? null : points.reduce((sum, point) => sum + (point[key] ?? 0), 0)
}
export function OperationsNotifications({ revision, days, onHelp }: { revision: number; days: number; onHelp: (key: OperationsHelp) => void }) {
  const summary = useOperationsQuery(loadSummary, revision)
  const trend = useOperationsQuery(loadTrend, revision)
  const failures = useOperationsQuery(loadFailures, revision)
  const retries = useOperationsQuery(loadRetries, revision)
  const points = trend.data?.days.slice(-days) ?? []
  const previous = trend.data?.days.slice(-days * 2, -days) ?? []
  const activations = total(points, days, 'activations'), cancellations = total(points, days, 'cancellations')
  return <section className={styles.panel} aria-label="알림">
    <div className={styles.panelTop}><div className={styles.panelTitle}><OperationsIcon name="bell" /><h2>알림</h2><button className={styles.info} type="button" aria-label="알림 집계 기준" onClick={() => onHelp('notifications')}><OperationsIcon name="info" /></button></div><Link to="/admin/notification-subscriptions">관리 ↗</Link></div>
    <div className={styles.panelBody}><div className={styles.stats}><OperationsMetric title="활성 알림" value={summary.data?.summary.activeSubscriptions} query={summary} href="/admin/notification-subscriptions?status=ACTIVE" primary /><OperationsMetric title="신청 유저" value={summary.data?.summary.activeMembers} query={summary} href="/admin/notification-subscriptions?status=ACTIVE" /></div><OperationsStatus query={summary} />
      <div className={styles.notificationChanges}>{([['activations', '신청', activations], ['cancellations', '취소', cancellations]] as const).map(([key, label, value]) => <Link key={key} aria-label={`${label} ${operationCount(value, trend)} · 최근 ${days}일`} to={`/admin/notification-subscriptions?view=trend&days=${days}`}>
        {label} <strong aria-label={`최근 ${days}일 ${label}`}>{operationCount(value, trend)}</strong><span>{trend.data ? comparison(value, total(previous, days, key)) : '—'}</span></Link>)}<span>이전 {days}일 대비</span></div>
      <OperationsStatus query={trend} />{trend.data ? <OperationsTrend points={points.map(point => ({ day: point.day, count: point.activations, secondary: point.cancellations }))} label="신청" secondaryLabel="취소" /> : <p className={styles.chartEmpty}>{trend.pending ? '조회 중' : '조회 실패'}</p>}
      <div className={styles.deliveryRow}><Link to="/admin/notification-deliveries?status=FAILED"><OperationsIcon name="alert" />발송 실패 <strong>{operationCount(failures.data?.totalElements, failures)}</strong></Link><Link to="/admin/notification-deliveries?status=RETRY">재시도 대기 <strong>{operationCount(retries.data?.totalElements, retries)}</strong></Link></div>
      <OperationsStatus query={failures} /><OperationsStatus query={retries} />
      <button type="button" className={styles.messageLink} onClick={() => onHelp('messages')}><OperationsIcon name="message" />카카오톡 메시지 발송<span>준비중</span><span aria-hidden="true">↗</span></button><CheckedAt value={summary.data?.summary.asOf ?? null} />
    </div>
  </section>
}
