import { useEffect, useRef, useState } from 'react'
import {
  DataPipelineApiError,
  getDataPipelineStatus,
  startDataPipeline,
  type DataPipelineExecution,
  type DataPipelineType,
} from './api'

type PipelineViewState = {
  execution: DataPipelineExecution
  requestError: string | null
  errorResponse: unknown
}

const pollIntervalMilliseconds = 1_000
const pipelineTypes: readonly DataPipelineType[] = ['COLLECTION', 'REFINEMENT']
const pipelineLabels: Record<DataPipelineType, string> = {
  COLLECTION: '수집',
  REFINEMENT: '정제',
}

export function DataPipelineControl() {
  const [pipelineStates, setPipelineStates] = useState(initialPipelineStates)
  const pollTimers = useRef<Partial<Record<DataPipelineType, number>>>({})
  const stateGenerations = useRef<Record<DataPipelineType, number>>({
    COLLECTION: 0,
    REFINEMENT: 0,
  })
  const mounted = useRef(true)
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
          <p>수집을 완료한 뒤 정제를 실행해 주세요. 실패하면 이후 단계는 실행되지 않습니다.</p>
        </div>
        <div className="data-pipeline-actions">
          {pipelineTypes.map((type) => (
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
      <div className="data-pipeline-results">
        {pipelineTypes.map((type) => (
          <PipelineResult key={type} type={type} state={pipelineStates[type]} />
        ))}
      </div>
    </section>
  )
}

function PipelineResult({ type, state }: { type: DataPipelineType, state: PipelineViewState }) {
  const label = pipelineLabels[type]
  const { execution } = state
  const failureMessage = state.requestError ?? execution.failure?.message
  const serverResponse = state.requestError === null
    ? execution.failure?.serverResponse
    : state.errorResponse

  return (
    <article className="data-pipeline-result">
      <h3>{label} 상태</h3>
      {execution.status === 'IDLE' ? <p>아직 실행하지 않았습니다.</p> : null}
      {execution.status === 'RUNNING' ? (
        <p role="status">{runningMessage(execution)}</p>
      ) : null}
      {execution.status === 'COMPLETED' ? (
        <p className="data-pipeline-success" role="status">{label} 작업을 완료했습니다.</p>
      ) : null}
      {execution.completedSteps.length > 0 ? (
        <ol className="data-pipeline-steps">
          {execution.completedSteps.map((step) => <li key={step}>{step} 완료</li>)}
        </ol>
      ) : null}
      {(execution.status === 'FAILED' || state.requestError !== null) && failureMessage ? (
        <div className="data-pipeline-error" role="alert">
          <strong>{failureMessage}</strong>
          {serverResponse !== null && serverResponse !== undefined ? (
            <pre aria-label="서버 응답">{JSON.stringify(serverResponse, null, 2)}</pre>
          ) : null}
        </div>
      ) : null}
    </article>
  )
}

function initialPipelineStates(): Record<DataPipelineType, PipelineViewState> {
  return {
    COLLECTION: viewState(idleExecution('COLLECTION', 5)),
    REFINEMENT: viewState(idleExecution('REFINEMENT', 4)),
  }
}

function viewState(execution: DataPipelineExecution): PipelineViewState {
  return { execution, requestError: null, errorResponse: null }
}

function idleExecution(type: DataPipelineType, totalStepCount: number): DataPipelineExecution {
  return {
    executionId: null,
    type,
    status: 'IDLE',
    currentStepName: null,
    currentStepIndex: 0,
    totalStepCount,
    completedSteps: [],
    failure: null,
  }
}

function optimisticRunningExecution(type: DataPipelineType): DataPipelineExecution {
  const totalStepCount = type === 'COLLECTION' ? 5 : 4
  return {
    ...idleExecution(type, totalStepCount),
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
