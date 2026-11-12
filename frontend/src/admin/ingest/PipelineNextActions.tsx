import { Link } from 'react-router'
import type { DataPipelineExecution } from './api'
import { retiredComplexRefinementNotice } from './pipelineLabels'
import styles from './PipelineWorkflow.module.css'

export function PipelineNextActions({ execution, onRefine, disabled = false }: {
  execution: DataPipelineExecution; onRefine?: () => void; disabled?: boolean
}) {
  const { type, status } = execution
  if (status === 'IDLE' || status === 'RUNNING' || type === 'ANNOUNCEMENT_REGISTRATION') return null
  if (type === 'SH_ANNOUNCEMENT_COLLECTION' || type === 'GH_ANNOUNCEMENT_COLLECTION') {
    if (status !== 'COMPLETED' && status !== 'COMPLETED_WARNINGS' && status !== 'COMPLETED_WITH_SKIPS') return null
    const provider = type === 'SH_ANNOUNCEMENT_COLLECTION' ? 'SH' : 'GH'
    return <div className={styles.nextActions} aria-label="다음 작업">
      {status === 'COMPLETED_WITH_SKIPS' && <p>호출 제한으로 남은 글을 받지 못했습니다. 이미 받은 페이지는 정제했으며, 다시 실행하면 남은 글을 이어서 받습니다.</p>}
      <p>정제로 만든 공고는 미확정이라 사용자에게 보이지 않습니다. PDF 보정에서 공급행을 단지에 연결하고 보정을 적용하면 곧바로 사용자에게 보입니다.</p>
      <div className={styles.actions}>
        <Link to={`/admin/announcements?unconfirmed=true&provider=${provider}`}>공개 전 확인이 필요한 공고 보기</Link>
      </div>
    </div>
  }

  const complex = type.startsWith('COMPLEX_')
  const collection = type.endsWith('_COLLECTION')
  const manualRefinement = needsManualRefinement(execution)
  const completed = status === 'COMPLETED' || status === 'COMPLETED_WARNINGS'
  if (!manualRefinement && !completed) return null

  return <div className={styles.nextActions} aria-label="다음 작업">
    {complex && (collection || manualRefinement) && <p>{retiredComplexRefinementNotice}</p>}
    {!complex && manualRefinement && <p>자동 정제가 실행되지 않았습니다. 수집 결과를 확인한 뒤 저장된 원천으로 정제할 수 있습니다.</p>}
    {!complex && (collection || manualRefinement) && <>
      <p className="ingest-meta">정제는 현재 저장된 원천 전체를 사용합니다. 이전 수집의 원천이 포함될 수 있습니다.</p>
      {onRefine ? <button type="button" disabled={disabled} onClick={onRefine}>
        저장된 원천으로 공고 정제 실행
      </button> : <Link to={`/admin/failures?domain=announcement&category=all&executionId=${encodeURIComponent(execution.executionId ?? '')}`}>
        공고 정제 화면으로 이동</Link>}
    </>}
    {!collection && !manualRefinement && <>
      <div className={styles.actions}>
        <Link to={complex ? '/admin/complexes' : '/admin/announcements'}>{complex ? '단지' : '공고'} 저장 결과 확인</Link>
        {complex && <Link to="/admin/ingest/announcements">다음: 공고 갱신</Link>}
      </div>
      {complex && <p className="ingest-meta">기존 공고의 단지 연결을 다시 시도하려면 공고 수집 화면에서 LH 공고 갱신을 실행해 주세요.</p>}
    </>}
  </div>
}

function needsManualRefinement(execution: DataPipelineExecution): boolean {
  if (!execution.type.endsWith('_SYNC') || !['FAILED', 'STOPPED', 'COMPLETED_WITH_SKIPS'].includes(execution.status)) return false
  const refinementIndex = execution.type === 'COMPLEX_SYNC' ? 3 : 5
  return execution.currentStepIndex < refinementIndex
    && !(execution.completedStepResults ?? []).some((step) => step.step.startsWith('MAP_') || step.step.startsWith('ENRICH_'))
}
