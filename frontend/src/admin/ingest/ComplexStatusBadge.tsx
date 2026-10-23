import type { DataPipelineExecution } from './api'
import styles from './ComplexIngest.module.css'

const statuses = {
  waiting: ['대기', 'neutral', 'M12 7v5l3 2M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0'],
  running: ['진행 중', 'info', 'M12 3a9 9 0 1 1-9 9'],
  pending: ['접수 중', 'info', 'M12 3a9 9 0 1 1-9 9'],
  completed: ['완료', 'success', 'm5 12 4 4L19 6'],
  partial: ['일부 실패', 'warning', 'M12 8v5m0 3h.01M10.3 4.8 2.5 18a1.5 1.5 0 0 0 1.3 2h16.4a1.5 1.5 0 0 0 1.3-2L13.7 4.8a2 2 0 0 0-3.4 0'],
  failed: ['실패', 'danger', 'm7 7 10 10M7 17 17 7'],
  skipped: ['건너뜀', 'neutral', 'M5 12h14'],
  withSkips: ['일부 건너뜀', 'warning', 'M5 12h14'],
  stopped: ['중지됨', 'neutral', 'M8 5v14M16 5v14'],
  stopping: ['중지 요청 중', 'warning', 'M8 5v14M16 5v14'],
  unexecuted: ['미실행', 'neutral', 'M5 12h14'],
} as const
export type ComplexStatus = keyof typeof statuses
const complexExecutionStatus: Record<DataPipelineExecution['status'], ComplexStatus> = {
  IDLE: 'unexecuted', RUNNING: 'running', COMPLETED: 'completed', COMPLETED_WARNINGS: 'partial',
  COMPLETED_WITH_SKIPS: 'withSkips', FAILED: 'failed', STOPPED: 'stopped',
}

export function ComplexStatusBadge({ status, id }: { status: ComplexStatus | DataPipelineExecution['status']; id?: string }) {
  const displayStatus = status in complexExecutionStatus ? complexExecutionStatus[status as DataPipelineExecution['status']] : status as ComplexStatus
  const [label, tone, path] = statuses[displayStatus]
  return <span id={id} className={styles.statusBadge} data-tone={tone} data-status={displayStatus}>
    <svg className={displayStatus === 'running' || displayStatus === 'pending' ? styles.statusSpinner : undefined}
      viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={path} /></svg>
    {label}
  </span>
}
