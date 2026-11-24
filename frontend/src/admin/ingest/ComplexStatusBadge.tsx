import type { DataPipelineExecution } from './api'
import { AdminIcon } from '../shared/AdminIcon'
import styles from './ComplexIngest.module.css'

const statuses = {
  waiting: ['대기', 'neutral', 'clock'],
  running: ['진행 중', 'info', 'progress'],
  pending: ['접수 중', 'info', 'progress'],
  completed: ['완료', 'success', 'check'],
  partial: ['일부 실패', 'warning', 'alert'],
  failed: ['실패', 'danger', 'close'],
  skipped: ['건너뜀', 'neutral', 'dash'],
  withSkips: ['일부 건너뜀', 'warning', 'dash'],
  stopped: ['중지됨', 'neutral', 'square'],
  stopping: ['중지 요청 중', 'warning', 'square'],
  unexecuted: ['미실행', 'neutral', 'dash'],
} as const
export type ComplexStatus = keyof typeof statuses
const complexExecutionStatus: Record<DataPipelineExecution['status'], ComplexStatus> = {
  IDLE: 'unexecuted', RUNNING: 'running', COMPLETED: 'completed', COMPLETED_WARNINGS: 'partial',
  COMPLETED_WITH_SKIPS: 'withSkips', FAILED: 'failed', STOPPED: 'stopped',
}

export function ComplexStatusBadge({ status, id }: { status: ComplexStatus | DataPipelineExecution['status']; id?: string }) {
  const displayStatus = status in complexExecutionStatus ? complexExecutionStatus[status as DataPipelineExecution['status']] : status as ComplexStatus
  const [label, tone, icon] = statuses[displayStatus]
  return <span id={id} className={styles.statusBadge} data-tone={tone} data-status={displayStatus}>
    <AdminIcon name={icon} size={14} className={displayStatus === 'running' || displayStatus === 'pending' ? styles.statusSpinner : undefined} />
    {label}
  </span>
}
