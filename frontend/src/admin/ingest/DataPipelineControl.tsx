import { pipelineLabels, pipelineStatusLabels } from './pipelineLabels'
import { Link } from 'react-router'
import { useEffect, useRef, useState } from 'react'
import {
  DataPipelineApiError,
  getDataPipelineStatus,
  startDataPipeline,
  stopDataPipeline,
  type DataPipelineExecution,
  type DataPipelineType,
} from './api'

import { DataPipelineProgress, PipelineReport } from './DataPipelineProgress'
import { LhAnnouncementQualityPanel } from './LhAnnouncementQualityPanel'


type PipelineViewState = {
  execution: DataPipelineExecution
  requestError: string | null
  errorResponse: unknown
}

const pollIntervalMilliseconds = 1_000
const pipelineTypes: readonly DataPipelineType[] = [
  'COMPLEX_COLLECTION',
  'COMPLEX_REFINEMENT',
  'ANNOUNCEMENT_COLLECTION',
  'ANNOUNCEMENT_REFINEMENT',
  'COMPLEX_SYNC',
  'ANNOUNCEMENT_SYNC',
]
const pipelineStepCounts: Record<DataPipelineType, number> = {
  COMPLEX_COLLECTION: 2,
  COMPLEX_REFINEMENT: 2,
  ANNOUNCEMENT_COLLECTION: 4,
  ANNOUNCEMENT_REFINEMENT: 2,
  COMPLEX_SYNC: 4,
  ANNOUNCEMENT_SYNC: 6,
}
const pipelineGroups = [
  {
    id: 'complex-pipelines',
    title: '단지 데이터',
    description: '단지 원천과 주택형 정보를 갱신합니다.',
    types: ['COMPLEX_SYNC', 'COMPLEX_COLLECTION', 'COMPLEX_REFINEMENT'],
  },
  {
    id: 'announcement-pipelines',
    title: '공고 데이터',
    description: '공고 원천과 상세·공급 정보를 갱신합니다.',
    types: ['ANNOUNCEMENT_SYNC', 'ANNOUNCEMENT_COLLECTION', 'ANNOUNCEMENT_REFINEMENT'],
  },
] as const satisfies readonly {
  id: string
  title: string
  description: string
  types: readonly DataPipelineType[]
}[]

export function DataPipelineControl() {
  const [pipelineStates, setPipelineStates] = useState(initialPipelineStates)
  const [stopping, setStopping] = useState<Partial<Record<DataPipelineType, boolean>>>({})
  const pollTimers = useRef<Partial<Record<DataPipelineType, number>>>({})
  const stateGenerations = useRef<Record<DataPipelineType, number>>({
    COMPLEX_COLLECTION: 0,
    COMPLEX_REFINEMENT: 0,
    ANNOUNCEMENT_COLLECTION: 0,
    ANNOUNCEMENT_REFINEMENT: 0,
    COMPLEX_SYNC: 0,
    ANNOUNCEMENT_SYNC: 0,
  })
  const mounted = useRef(true)
  const orderedGroups = [...pipelineGroups].sort((left, right) =>
    Number(right.types.some((type) => pipelineStates[type].execution.status === 'RUNNING'))
      - Number(left.types.some((type) => pipelineStates[type].execution.status === 'RUNNING'))
      || Math.max(...right.types.map((type) => Date.parse(pipelineStates[type].execution.startedAt ?? '') || 0))
        - Math.max(...left.types.map((type) => Date.parse(pipelineStates[type].execution.startedAt ?? '') || 0)),
  )
  const isAnyPipelineRunning = Object.values(pipelineStates)
    .some((state) => state.execution.status === 'RUNNING')

  useEffect(() => {
    mounted.current = true
    pipelineTypes.forEach((type) => void refresh(type))
    return () => {
      mounted.current = false
      pipelineTypes.forEach(clearPoll)
    }
  }, [])

  function handleRun(type: DataPipelineType) {
    if (isAnyPipelineRunning) {
      return
    }
    void execute(type)
  }

  async function stop(type: DataPipelineType) {
    const executionId = pipelineStates[type].execution.executionId
    if (!executionId || stopping[type]) return
    clearPoll(type)
    const generation = nextGeneration(type)
    setStopping((previous) => ({ ...previous, [type]: true }))
    try {
      const execution = await stopDataPipeline(executionId)
      if (mounted.current && isLatestGeneration(type, generation)) applyExecution(type, execution)
    } catch (error) {
      if (mounted.current && isLatestGeneration(type, generation)) {
        displayRequestFailure(type, error)
        schedulePoll(type)
      }
    } finally {
      if (mounted.current) setStopping((previous) => ({ ...previous, [type]: false }))
    }
  }

  async function execute(type: DataPipelineType) {
    clearPoll(type)
    const generation = nextGeneration(type)
    updateState(type, {
      execution: optimisticRunningExecution(type),
      requestError: null,
      errorResponse: null,
    })
    try {
      const execution = await startDataPipeline(type)
      if (mounted.current && isLatestGeneration(type, generation)) {
        applyExecution(type, execution)
      }
    } catch (error) {
      await recoverOrDisplayRequestFailure(type, error, generation)
    }
  }

  async function recoverOrDisplayRequestFailure(
    type: DataPipelineType,
    error: unknown,
    generation: number,
  ) {
    try {
      const execution = await getDataPipelineStatus(type)
      if (!mounted.current || !isLatestGeneration(type, generation)) {
        return
      }
      applyExecution(type, execution)
      if (execution.status !== 'RUNNING') {
        displayRequestFailure(type, error)
      }
      return
    } catch {
      if (mounted.current && isLatestGeneration(type, generation)) {
        displayRequestFailure(type, error)
        schedulePoll(type)
      }
    }
  }

  async function refresh(type: DataPipelineType, retryOnFailure = false) {
    const generation = nextGeneration(type)
    try {
      const execution = await getDataPipelineStatus(type)
      if (mounted.current && isLatestGeneration(type, generation)) {
        applyExecution(type, execution)
      }
    } catch (error) {
      if (mounted.current && isLatestGeneration(type, generation)) {
        displayRequestFailure(type, error)
        if (retryOnFailure) {
          schedulePoll(type)
        }
      }
    }
  }

  function applyExecution(type: DataPipelineType, execution: DataPipelineExecution) {
    updateState(type, {
      execution,
      requestError: null,
      errorResponse: null,
    })
    clearPoll(type)
    if (execution.status === 'RUNNING') {
      schedulePoll(type)
    }
  }

  function displayRequestFailure(type: DataPipelineType, error: unknown) {
    const message = error instanceof Error
      ? error.message
      : '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'
    const errorResponse = error instanceof DataPipelineApiError
      ? error.serverResponse
      : null
    updateState(type, (previous) => ({
      ...previous,
      requestError: message,
      errorResponse,
    }))
  }

  function schedulePoll(type: DataPipelineType) {
    clearPoll(type)
    pollTimers.current[type] = window.setTimeout(
      () => void refresh(type, true),
      pollIntervalMilliseconds,
    )
  }

  function clearPoll(type: DataPipelineType) {
    const timer = pollTimers.current[type]
    if (timer !== undefined) {
      window.clearTimeout(timer)
      delete pollTimers.current[type]
    }
  }

  function nextGeneration(type: DataPipelineType): number {
    stateGenerations.current[type] += 1
    return stateGenerations.current[type]
  }

  function isLatestGeneration(type: DataPipelineType, generation: number): boolean {
    return stateGenerations.current[type] === generation
  }

  function updateState(
    type: DataPipelineType,
    nextState: PipelineViewState | ((previous: PipelineViewState) => PipelineViewState),
  ) {
    setPipelineStates((previous) => ({
      ...previous,
      [type]: typeof nextState === 'function'
        ? nextState(previous[type])
        : nextState,
    }))
  }

  return (
    <section className="registration-card data-pipeline-card" aria-labelledby="data-pipeline-title">
      <div className="data-pipeline-heading">
        <div>
          <h2 id="data-pipeline-title">데이터 수집·정제</h2>
          <p>
            수집·정제를 선택하면 수집 후 정제까지 이어서 실행합니다.
            수집 실패·호출 제한이 있으면 자동 정제를 멈추고 결과를 남깁니다.
            수집 또는 정제만 따로 실행할 수도 있습니다.
          </p>
        </div>
      </div>
      <div className="data-pipeline-groups">
        {orderedGroups.map((group) => (
          <section
            aria-labelledby={group.id}
            className="data-pipeline-group"
            key={group.id}
          >
            <div className="data-pipeline-group-heading">
              <div>
                <h3 id={group.id}>{group.title}</h3>
                <p>{group.description}</p>
              </div>
              <div className="data-pipeline-actions">
                {group.types.map((type) => (
                  <button
                    disabled={isAnyPipelineRunning}
                    key={type}
                    onClick={() => handleRun(type)}
                    type="button"
                  >
                    {buttonLabel(type, pipelineStates[type].execution.status)}
                  </button>
                ))}
              </div>
            </div>
            {group.id === 'complex-pipelines' ? (
              <p className="ingest-meta">새 단지 주소의 좌표가 없으면 단지 수집 → <Link to="/admin/locations">위치정보 ZIP 업로드</Link> → 단지 정제를 실행해 주세요.</p>
            ) : null}
            <div className="data-pipeline-results">
              {group.types.map((type) => (
                <PipelineResult key={type} type={type} state={pipelineStates[type]}
                  collectionExecution={latestCollectionExecution(
                    pipelineStates[group.types[0]].execution, pipelineStates[group.types[1]].execution,
                  )}
                  stopping={stopping[type] ?? false} onStop={() => void stop(type)}
                  recoveryDisabled={isAnyPipelineRunning}
                  onRefine={() => handleRun(group.types[2])}
                  />
              ))}
            </div>
          </section>
        ))}
      </div>
      <LhAnnouncementQualityPanel collectionExecution={latestCollectionExecution(
        pipelineStates.ANNOUNCEMENT_SYNC.execution, pipelineStates.ANNOUNCEMENT_COLLECTION.execution,
      )} />
    </section>
  )
}

export function PipelineResult({ type, state, collectionExecution, stopping, onStop, recoveryDisabled, onRefine }: {
  type: DataPipelineType, state: PipelineViewState, collectionExecution?: DataPipelineExecution,
  stopping: boolean, onStop: () => void, recoveryDisabled?: boolean, onRefine?: () => void,
}) {
  const label = pipelineLabels[type]
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
                <details><summary>원본 응답</summary><pre aria-label={`${step.stepName} 건너뜀 응답`}>
                  {JSON.stringify(step.serverResponse, null, 2)}
                </pre></details>
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
                <details><summary>원본 보고서</summary><pre className="data-pipeline-warning-report" aria-label={`${step.stepName} 누락 보고서`}>
                  {JSON.stringify(step.report, null, 2)}
                </pre></details>
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
              <details><summary>서버 응답 상세</summary><pre aria-label="서버 응답">{JSON.stringify(serverResponse, null, 2)}</pre></details>
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
          <Link className="pipeline-inspect" to={`/admin/failures?category=collection&executionId=${execution.executionId ?? ''}`}>{label} 수집 실패 요청 보기</Link>{' · '}
          <Link className="pipeline-inspect" to={`/admin/failures?category=${type === 'COMPLEX_SYNC' ? 'complex' : 'announcement'}&executionId=${execution.executionId ?? ''}`}>{label} 정제 실패 행 보기</Link>{' · '}
          <Link className="pipeline-inspect" to={`/admin/failures?category=${type === 'COMPLEX_SYNC' ? 'household' : 'enrichment'}&executionId=${execution.executionId ?? ''}`}>{label} 보강 실패 행 보기</Link>
        </div>
      ) : (
        <Link className="pipeline-inspect" to={`/admin/failures?category=${type === 'COMPLEX_REFINEMENT' ? 'complex' : type === 'ANNOUNCEMENT_REFINEMENT' ? 'announcement' : 'collection'}&executionId=${execution.executionId ?? ''}`}>{label} 실패 행·요청 보기</Link>
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

function latestCollectionExecution(combined: DataPipelineExecution, collection: DataPipelineExecution): DataPipelineExecution {
  return (Date.parse(combined.startedAt ?? '') || 0) > (Date.parse(collection.startedAt ?? '') || 0)
    ? combined : collection
}

function initialPipelineStates(): Record<DataPipelineType, PipelineViewState> {
  return {
    COMPLEX_COLLECTION: viewState(idleExecution('COMPLEX_COLLECTION')),
    COMPLEX_REFINEMENT: viewState(idleExecution('COMPLEX_REFINEMENT')),
    ANNOUNCEMENT_COLLECTION: viewState(idleExecution('ANNOUNCEMENT_COLLECTION')),
    ANNOUNCEMENT_REFINEMENT: viewState(idleExecution('ANNOUNCEMENT_REFINEMENT')),
    COMPLEX_SYNC: viewState(idleExecution('COMPLEX_SYNC')),
    ANNOUNCEMENT_SYNC: viewState(idleExecution('ANNOUNCEMENT_SYNC')),
  }
}

function viewState(execution: DataPipelineExecution): PipelineViewState {
  return { execution, requestError: null, errorResponse: null }
}

function idleExecution(type: DataPipelineType): DataPipelineExecution {
  return {
    executionId: null,
    type,
    status: 'IDLE',
    currentStepName: null,
    currentStepIndex: 0,
    totalStepCount: pipelineStepCounts[type],
    completedSteps: [],
    skippedSteps: [],
    partiallyFailedSteps: [],
    failure: null,
  }
}

function optimisticRunningExecution(type: DataPipelineType): DataPipelineExecution {
  return {
    ...idleExecution(type),
    status: 'RUNNING',
  }
}

function runningMessage(execution: DataPipelineExecution): string {
  if (execution.currentStepName === null) {
    return '실행을 시작하고 있습니다.'
  }
  return `${execution.currentStepIndex}/${execution.totalStepCount} · ${execution.currentStepName} 실행 중`
}

function buttonLabel(type: DataPipelineType, status: DataPipelineExecution['status']): string {
  const label = pipelineLabels[type]
  if (status === 'RUNNING') {
    return `${label} 실행 중…`
  }
  return label
}
