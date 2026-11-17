import { Link } from 'react-router'
import { processingStages } from '../management/listFilterLabels'
import { loadTasksOverview } from './api'
import type { OperationsHelp } from './OperationsHome'
import { OperationsIcon } from './OperationsIcon'
import { CheckedAt, OperationsStatus } from './OperationsStatus'
import { operationCount, operationDuration } from './presentation'
import { useOperationsQuery } from './useOperationsQuery'
import styles from './OperationsHome.module.css'

/** 사람이 손대야 넘어가는 단계다. 추출 전·대기·확정은 기다리거나 끝난 단계라 강조하지 않는다. */
const actionStages = new Set<string>(['PDF_MISSING', 'EXTRACTION_FAILED', 'REVIEW_PENDING', 'RECHECK_REQUIRED'])

export function OperationsTasks({ revision, onHelp }: { revision: number; onHelp: (key: OperationsHelp) => void }) {
  const tasks = useOperationsQuery(loadTasksOverview, revision)
  const stages = tasks.data?.stages
  const open = stages ? Object.values(stages).reduce((sum, value) => sum + value, 0) : null
  const queue = tasks.data?.queue
  return <section className={`${styles.panel} ${styles.tasks}`} aria-label="처리할 일">
    <div className={styles.panelTop}><div className={styles.panelTitle}><OperationsIcon name="alert" /><h2>처리할 일</h2>
      <button className={styles.info} type="button" aria-label="처리할 일 집계 기준" onClick={() => onHelp('tasks')}><OperationsIcon name="info" /></button>
      <span className={styles.tasksScope}>마감 전 공고 {operationCount(open, tasks)}</span></div>
      <Link to="/admin/announcements?applicationStatus=OPEN">공고 목록 ↗</Link></div>
    <div className={styles.panelBody}>
      <ol className={styles.stageList} aria-label="공고 처리 단계">{Object.keys(processingStages).map(stage => <li key={stage}>
        <Link to={`/admin/announcements?applicationStatus=OPEN&stage=${stage}`} data-action={actionStages.has(stage) || undefined}
          aria-label={`${processingStages[stage]} ${operationCount(stages?.[stage], tasks)}`}>
          <span>{processingStages[stage]}</span><strong>{operationCount(stages?.[stage], tasks)}</strong></Link></li>)}</ol>
      <div className={styles.taskRows}>
        <div><span>AI 추출 대기</span><strong>{operationCount(queue?.waiting, tasks)}</strong>
          <small>처리 중 {operationCount(queue?.running, tasks)} · 가장 오래 기다림 {queue ? operationDuration(queue.oldestWaitSeconds) : '—'} · 최근 24시간 실패 {operationCount(queue?.recentFailed, tasks)}</small></div>
        <Link to="/admin/failures?domain=announcement&category=all" aria-label={`미해결 수집·정제 실패 ${operationCount(tasks.data?.unresolvedFailures, tasks)}`}><span>미해결 수집·정제 실패</span><strong>{operationCount(tasks.data?.unresolvedFailures, tasks)}</strong><small>실패 검토로 이동 →</small></Link>
      </div>
      <OperationsStatus query={tasks} /><CheckedAt value={tasks.data?.asOf ?? null} />
    </div>
  </section>
}
