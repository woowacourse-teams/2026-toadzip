import { useId, useState } from 'react'
import { Button } from '../../design-system/components/Button'
import type { DataPipelineExecution } from './api'
import { PipelineReport } from './DataPipelineProgress'
import { pipelineStepDefinitions, pipelineStepSequences, pipelineStepState } from './pipelineSteps'
import { pipelineStatusLabels } from './pipelineLabels'
import { ComplexRunErrors } from './ComplexRunErrors'
import styles from './ComplexIngest.module.css'

const stateLabels = { waiting: '대기', running: '진행', success: '완료', partial: '일부 실패', failed: '실패', skipped: '건너뜀', stopped: '중지', unexecuted: '미실행' }

export function ComplexExecutionResult({ execution, phase, pending = false, stopping = false, onStop, showSummary = true, showErrors = true }: {
  execution: DataPipelineExecution; phase?: 'collection' | 'refinement'; pending?: boolean; stopping?: boolean; onStop?: () => void; showSummary?: boolean; showErrors?: boolean
}) {
  const [errorsOpen, setErrorsOpen] = useState(false)
  const errorsId = useId()
  const steps = pipelineStepSequences[execution.type].filter(step => !phase
    || (phase === 'collection' ? step.startsWith('COLLECT_') : !step.startsWith('COLLECT_')))
  const work = execution.workProgress
  const settled = execution.status !== 'IDLE' && execution.status !== 'RUNNING'
  const errorPhase = phase ?? (execution.type === 'COMPLEX_COLLECTION' ? 'collection'
    : execution.type === 'COMPLEX_REFINEMENT' ? 'refinement' : undefined)
  const activePhase = execution.type !== 'COMPLEX_SYNC' || !phase
    || steps.some(step => pipelineStepDefinitions[step] === execution.currentStepName)
    || (!execution.currentStepName && phase === 'collection')
  return <div className={styles.result} aria-label="실행 결과">
    {showSummary && <ComplexExecutionSummary execution={execution} pending={pending} />}
    {pending && <p role="status">실행 접수를 확인하고 있습니다. 확인 전에는 다시 실행할 수 없습니다.</p>}
    <ol className={styles.tasks} aria-label="실행 단계">
      {steps.map(step => {
        const name = pipelineStepDefinitions[step]
        const state = pipelineStepState(execution, step)
        const report = execution.partiallyFailedSteps.find(item => item.step === step || item.stepName === name)?.report
          ?? execution.completedStepResults?.find(item => item.step === step || item.stepName === name)?.report
        const skipped = execution.skippedSteps.find(item => item.stepName === name)
        return <li key={step} data-state={state}>
          <div><span className={styles.taskMark} aria-hidden="true">{state === 'success' ? '✓' : state === 'failed' || state === 'partial' ? '!' : '○'}</span>
            <strong>{name}</strong><span>{stateLabels[state]}</span></div>
          {state === 'running' && work && <div className={styles.work} role="status">
            <span>{work.label} · {work.completedCount.toLocaleString('ko-KR')} / {work.totalCount >= 0 ? work.totalCount.toLocaleString('ko-KR') : '전체 확인 중'} {work.unit}</span>
            {work.totalCount > 0 ? <progress aria-label={`${name} 진행률`} value={Math.min(work.completedCount, work.totalCount)} max={work.totalCount} /> : <progress aria-label={`${name} 진행 중`} />}
          </div>}
          {skipped && <p>{skipped.reason}</p>}
          {report != null && <PipelineReport report={report} />}
        </li>
      })}
    </ol>
    {execution.failure && <p className="form-error" role="alert">{execution.failure.message}</p>}
    {execution.status === 'RUNNING' && activePhase && onStop && <button type="button" disabled={stopping || execution.stopRequested || !execution.executionId}
      onClick={onStop}>{stopping || execution.stopRequested ? '중지 요청 중…' : '실행 중지'}</button>}
    {execution.stopRequested && execution.status === 'RUNNING' && <p role="status">현재 처리가 끝나면 중지합니다. 이미 저장한 데이터는 유지됩니다.</p>}
    {showErrors && settled && execution.executionId && <>
      <Button data-design-system className={styles.errorToggle} aria-label="결과 오류 조회"
        aria-expanded={errorsOpen} aria-controls={errorsId} onClick={() => setErrorsOpen(value => !value)}>
        결과 오류 조회
        <svg className={styles.disclosureChevron} width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="m6 9 6 6 6-6" /></svg>
      </Button>
      <div id={errorsId} role="region" aria-label="결과 오류" hidden={!errorsOpen} className={styles.errorPanel}>
        {errorsOpen && <ComplexRunErrors key={`${execution.executionId}-${errorPhase}`} executionId={execution.executionId} phase={errorPhase} />}
      </div>
    </>}
  </div>
}

export function ComplexExecutionSummary({ execution, pending = false }: { execution: DataPipelineExecution; pending?: boolean }) {
  if (pending || execution.status === 'IDLE') return null
  const timestamp = execution.finishedAt ?? execution.startedAt
  return <div className={styles.executionSummary} role="group" aria-label="실행 상태">
    <strong className={`pipeline-badge pipeline-${execution.status.toLowerCase()}`}>{pipelineStatusLabels[execution.status]}</strong>
    {timestamp && <div className={styles.summaryTime}>
      <span>{execution.finishedAt ? '종료 시각' : '시작 시각'}</span>
      <time dateTime={timestamp}>{new Date(timestamp).toLocaleString('ko-KR', {
        year: 'numeric', month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit',
      })}</time>
    </div>}
  </div>
}
