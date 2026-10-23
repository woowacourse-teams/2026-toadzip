import { Link } from 'react-router'
import type { DataPipelineExecution } from './api'
import styles from './PipelineWorkflow.module.css'

export function PipelineNextActions({ execution, onRefine, disabled = false }: {
  execution: DataPipelineExecution; onRefine?: () => void; disabled?: boolean
}) {
  const { type, status } = execution
  if (status === 'IDLE' || status === 'RUNNING'
    || type === 'ANNOUNCEMENT_REGISTRATION' || type === 'SH_ANNOUNCEMENT_COLLECTION') return null

  const complex = type.startsWith('COMPLEX_')
  const collection = type.endsWith('_COLLECTION')
  const manualRefinement = needsManualRefinement(execution)
  const completed = status === 'COMPLETED' || status === 'COMPLETED_WARNINGS'
  if (!manualRefinement && !completed) return null

  return <div className={styles.nextActions} aria-label="다음 작업">
    {manualRefinement && <p>자동 정제가 실행되지 않았습니다. 수집 결과를 확인한 뒤 저장된 원천으로 정제할 수 있습니다.</p>}
    {complex && (collection || manualRefinement) && <>
      <p>새 단지 주소가 추가되었다면 위치정보 ZIP을 업로드한 뒤 단지를 정제해 주세요. 기존 좌표가 준비되어 있으면 바로 정제할 수 있습니다.</p>
      <Link to="/admin/locations">다음: 위치정보 ZIP 업로드</Link>
    </>}
    {(collection || manualRefinement) && <>
      <p className="ingest-meta">정제는 현재 저장된 원천 전체를 사용합니다. 이전 수집의 원천이 포함될 수 있습니다.</p>
      {onRefine ? <button type="button" disabled={disabled} onClick={onRefine}>
        저장된 원천으로 {complex ? '단지' : '공고'} 정제 실행
      </button> : <Link to={`/admin/failures?domain=${complex ? 'complex' : 'announcement'}&category=all&executionId=${encodeURIComponent(execution.executionId ?? '')}`}>
        {complex ? '단지' : '공고'} 정제 화면으로 이동</Link>}
    </>}
    {!collection && !manualRefinement && <>
      <div className={styles.actions}>
        <Link to={complex ? '/admin/complexes' : '/admin/announcements'}>{complex ? '단지' : '공고'} 저장 결과 확인</Link>
        {complex && <Link to="/admin/ingest/announcements">다음: 공고 갱신</Link>}
      </div>
      {complex && <p className="ingest-meta">기존 공고의 단지 연결을 다시 시도하려면 공고 수집 화면의 ‘수집·정제 따로 실행’에서 공고 정제를 실행해 주세요.</p>}
    </>}
  </div>
}

function needsManualRefinement(execution: DataPipelineExecution): boolean {
  if (!execution.type.endsWith('_SYNC') || !['FAILED', 'STOPPED', 'COMPLETED_WITH_SKIPS'].includes(execution.status)) return false
  const refinementIndex = execution.type === 'COMPLEX_SYNC' ? 3 : 5
  return execution.currentStepIndex < refinementIndex
    && !(execution.completedStepResults ?? []).some((step) => step.step.startsWith('MAP_') || step.step.startsWith('ENRICH_'))
}
