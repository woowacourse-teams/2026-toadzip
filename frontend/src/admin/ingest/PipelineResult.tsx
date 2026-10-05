import { Link } from 'react-router'
import type { DataPipelineExecution, DataPipelineType } from './api'
import { DataPipelineProgress, PipelineReport } from './DataPipelineProgress'
import { pipelineLabels, pipelineStatusLabels } from './pipelineLabels'
import { StoredDataTable } from '../shared/StoredDataTable'

export type PipelineViewState = {
  execution: DataPipelineExecution
  requestError: string | null
  errorResponse: unknown
}
export function PipelineResult({ type, state, collectionExecution, stopping, onStop, recoveryDisabled, onRefine }: {
  type: DataPipelineType, state: PipelineViewState, collectionExecution?: DataPipelineExecution,
  stopping: boolean, onStop: () => void, recoveryDisabled?: boolean, onRefine?: () => void,
}) {
  const label = pipelineLabels[type]
  const domain = type.startsWith('COMPLEX_') ? 'complex' : 'announcement'
  const { execution } = state
  const failureMessage = state.requestError ?? execution.failure?.message
  const serverResponse = state.requestError === null
    ? execution.failure?.serverResponse
    : state.errorResponse
  const includesCollection = type.endsWith('_COLLECTION') || type.endsWith('_SYNC')
  const collectionSummary = includesCollection
    && execution.status !== 'IDLE' && execution.status !== 'RUNNING'
    ? summarizeCollectionReports(execution)
    : null

  return (
    <article className={`data-pipeline-result${type.endsWith('_SYNC') ? ' data-pipeline-result-combined' : ''}`}>
      <h4>{label} 상태 <span className={`pipeline-badge pipeline-${execution.status.toLowerCase()}`}>{pipelineStatusLabels[execution.status]}</span></h4>
      {execution.status === 'RUNNING' ? <DataPipelineProgress execution={execution} label={label} /> : null}
      {execution.startedAt ? <p className="ingest-meta">마지막 실행 {new Date(execution.startedAt).toLocaleString('ko-KR')}</p> : null}
      {type.endsWith('_REFINEMENT') && collectionExecution ? (
        <p className="ingest-meta">
          {collectionExecution.startedAt
            ? `최근 수집 포함 실행: ${pipelineStatusLabels[collectionExecution.status]} · ${new Date(collectionExecution.startedAt).toLocaleString('ko-KR')}. `
            : '최근 수집 실행 기록이 없습니다. '}
          정제는 저장된 원천을 사용합니다. 이전 수집의 원천이 포함될 수 있습니다.
        </p>
      ) : null}
      {execution.status === 'IDLE' ? <p>아직 실행하지 않았습니다.</p> : null}
      {execution.status === 'RUNNING' ? (
        <div>
          <p role="status">{execution.stopRequested ? '중지 요청됨 · 진행 중인 처리가 끝나기를 기다립니다.' : runningMessage(execution)}</p>
          <button className="pipeline-stop" type="button" onClick={onStop}
            disabled={stopping || execution.stopRequested || !execution.executionId}>
            {stopping || execution.stopRequested ? '중지 요청 중…' : `${label} 실행 중지`}
          </button>
          <p className="ingest-meta">수집은 다음 외부 요청 전에, 정제는 현재 단계가 끝난 뒤 중지합니다. 이미 저장된 데이터는 유지됩니다.</p>
        </div>
      ) : null}
      {execution.status === 'STOPPED' ? <p role="status">{label} 실행이 중지되었습니다. 이미 저장한 데이터와 완료 단계는 유지됩니다.</p> : null}
      {execution.status === 'COMPLETED' ? (
        <p className="data-pipeline-success" role="status">{label} 작업을 완료했습니다.</p>
      ) : null}
      {execution.status === 'COMPLETED_WARNINGS' ? (
        <p className="data-pipeline-warning" role="status">
          {label} 작업을 완료했습니다. 처리되지 않은 원천 행이 있어 확인이 필요합니다.
        </p>
      ) : null}
      {execution.status === 'COMPLETED_WITH_SKIPS' ? (
        <p role="status">{label} 작업을 일부 단계 건너뜀으로 완료했습니다.</p>
      ) : null}
      {collectionSummary ? (
        <div role="group" aria-label={`${label} 결과 요약`}>
          <strong>보고된 수집 단계 합계</strong>
          <PipelineReport report={collectionSummary} />
        </div>
      ) : null}
      {includesCollection && execution.status.startsWith('COMPLETED') ? (
        <p className="ingest-meta">완료는 선택된 수집 대상을 처리한 결과입니다. 모든 {type.startsWith('COMPLEX_') ? '단지' : '공고'}의 최신 상태를 보장하지 않습니다.</p>
      ) : null}
      {needsManualRefinement(execution) ? (
        <div className="data-pipeline-warning">
          <p>자동 정제가 실행되지 않았습니다. 수집 결과를 확인한 뒤 저장된 원천으로 정제할 수 있습니다.</p>
          <p className="ingest-meta">정제는 현재 저장된 원천 전체를 사용합니다. 이전 수집의 원천이 포함될 수 있습니다.</p>
          {onRefine ? (
            <button type="button" disabled={recoveryDisabled} onClick={onRefine}>
              저장된 원천으로 {type.startsWith('COMPLEX_') ? '단지' : '공고'} 정제 실행
            </button>
          ) : <a href="#data-pipeline-title">상단에서 정제 단독 실행</a>}
        </div>
      ) : null}
      <details className="pipeline-details"><summary>단계별 결과·기술 상세</summary>
      {execution.completedSteps.length > 0 ? (
        <ol className="data-pipeline-steps">
          {execution.completedSteps.map((step) => <li key={step}>{step} 완료</li>)}
        </ol>
      ) : null}
      {execution.skippedSteps.length > 0 ? (
        <ul className="data-pipeline-steps">
          {execution.skippedSteps.map((step) => (
            <li key={step.stepName}>
              <strong>{step.stepName} 건너뜀</strong>
              <p>{step.reason}</p>
              {step.serverResponse !== null && step.serverResponse !== undefined ? (
                <details><summary>원본 응답</summary><StoredDataTable data={step.serverResponse} label={`${step.stepName} 건너뜀 응답`} /></details>
              ) : null}
            </li>
          ))}
        </ul>
      ) : null}
      {execution.partiallyFailedSteps.length > 0 ? (
        <ul className="data-pipeline-steps">
          {execution.partiallyFailedSteps.map((step) => (
            <li key={step.step}>
              <strong>{step.stepName} 원천 행 확인</strong>
              <PipelineReport report={step.report} />
              {step.report !== null && step.report !== undefined ? (
                <details><summary>원본 보고서</summary><StoredDataTable data={step.report} label={`${step.stepName} 누락 보고서`} /></details>
              ) : null}
            </li>
          ))}
        </ul>
      ) : null}
      {(execution.status === 'FAILED' || state.requestError !== null) && failureMessage ? (
        <div className="data-pipeline-error">
          <strong>{failureMessage}</strong>
          {serverResponse !== null && serverResponse !== undefined ? (
            <div><PipelineReport report={serverResponse} />
              <details><summary>서버 응답 상세</summary><StoredDataTable data={serverResponse} label="서버 응답" /></details>
            </div>
          ) : null}
        </div>
      ) : null}
      {(execution.completedStepResults ?? []).filter((step) =>
        !execution.partiallyFailedSteps.some((warning) => warning.step === step.step),
      ).map((step) => <details className="pipeline-completed-report" key={step.step}>
        <summary>{step.stepName} 처리 결과</summary><PipelineReport report={step.report} />
      </details>)}
      </details>
      {failureMessage ? <p role="alert" className="data-pipeline-error">{failureMessage}</p> : null}
      {type.endsWith('_SYNC') ? (
        <div>
          <Link className="pipeline-inspect" to={`/admin/failures?domain=${domain}&category=collection&executionId=${execution.executionId ?? ''}`}>{label} 수집 실패 요청 보기</Link>{' · '}
          <Link className="pipeline-inspect" to={`/admin/failures?domain=${domain}&category=${type === 'COMPLEX_SYNC' ? 'complex' : 'announcement'}&executionId=${execution.executionId ?? ''}`}>{label} 정제 실패 행 보기</Link>{' · '}
          <Link className="pipeline-inspect" to={`/admin/failures?domain=${domain}&category=${type === 'COMPLEX_SYNC' ? 'household' : 'enrichment'}&executionId=${execution.executionId ?? ''}`}>{label} 보강 실패 행 보기</Link>
        </div>
      ) : (
        <Link className="pipeline-inspect" to={`/admin/failures?domain=${domain}&category=${type === 'COMPLEX_REFINEMENT' ? 'complex' : type === 'ANNOUNCEMENT_REFINEMENT' ? 'announcement' : 'collection'}&executionId=${execution.executionId ?? ''}`}>{label} 실패 행·요청 보기</Link>
      )}
    </article>
  )
}

function summarizeCollectionReports(execution: DataPipelineExecution): Record<string, number> | null {
  const totals: Record<string, number> = {}
  const reportedSteps = new Map([
    ...(execution.completedStepResults ?? []), ...execution.partiallyFailedSteps,
  ].map((step) => [step.step, step]))
  const reports = [
    ...[...reportedSteps.values()].filter((step) => step.step.startsWith('COLLECT_')).map((step) => step.report),
    ...execution.skippedSteps.map((step) => step.serverResponse),
  ]
  for (const report of reports) {
    if (typeof report !== 'object' || report === null) continue
    for (const field of ['externalApiCallCount', 'storedRowCount', 'skippedRequestCount', 'failedRequestCount']) {
      const value = (report as Record<string, unknown>)[field]
      if (typeof value === 'number' && Number.isFinite(value)) {
        totals[field] = (totals[field] ?? 0) + value
      }
    }
  }
  return Object.keys(totals).length ? totals : null
}

function needsManualRefinement(execution: DataPipelineExecution): boolean {
  if (!execution.type.endsWith('_SYNC') || !['FAILED', 'STOPPED', 'COMPLETED_WITH_SKIPS'].includes(execution.status)) return false
  const refinementIndex = execution.type === 'COMPLEX_SYNC' ? 3 : 5
  return execution.currentStepIndex < refinementIndex
    && !(execution.completedStepResults ?? []).some((step) => step.step.startsWith('MAP_') || step.step.startsWith('ENRICH_'))
}

function runningMessage(execution: DataPipelineExecution): string {
  if (execution.currentStepName === null) {
    return '실행을 시작하고 있습니다.'
  }
  return `${execution.currentStepIndex}/${execution.totalStepCount} · ${execution.currentStepName} 실행 중`
}
