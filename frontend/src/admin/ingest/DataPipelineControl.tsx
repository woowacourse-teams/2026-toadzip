import { pipelineLabels } from './pipelineLabels'
import { Link } from 'react-router'
import { useEffect, useRef, useState, type ReactNode } from 'react'
import {
  DataPipelineApiError,
  getDataPipelineStatus,
  startDataPipeline,
  stopDataPipeline,
  type DataPipelineExecution,
  type DataPipelineType as SharedDataPipelineType,
} from './api'

import { PipelineResult, type PipelineViewState } from './PipelineResult'
import { LhAnnouncementQualityPanel } from './LhAnnouncementQualityPanel'
import { PipelineExecutionSteps } from './PipelineExecutionSteps'
import styles from './CollectionStartForm.module.css'

type DataPipelineType = Exclude<SharedDataPipelineType, 'ANNOUNCEMENT_REGISTRATION'>

type CollectionType = 'COMPLEX_COLLECTION' | 'ANNOUNCEMENT_COLLECTION' | 'COMPLEX_SYNC' | 'ANNOUNCEMENT_SYNC'

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

export function DataPipelineControl({ domain, externalBusy = false, onBusyChange, compact = false, registration, onExecutionStart }: {
  domain?: 'complex' | 'announcement'; externalBusy?: boolean; onBusyChange?: (busy: boolean) => void
  compact?: boolean; onExecutionStart?: () => void
  registration?: { action: ReactNode; form: ReactNode; execution: DataPipelineExecution | null;
    stopping: boolean; onStop: () => void }
} = {}) {
  const [selectedType, setSelectedType] = useState<DataPipelineType | null>(null)
  const [pipelineStates, setPipelineStates] = useState(initialPipelineStates)
  const [stopping, setStopping] = useState<Partial<Record<DataPipelineType, boolean>>>({})
  const [pendingCollection, setPendingCollection] = useState<CollectionType | null>(null)
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
  const orderedGroups = pipelineGroups.filter(group => !domain || group.id === `${domain}-pipelines`).sort((left, right) =>
    Number(right.types.some((type) => pipelineStates[type].execution.status === 'RUNNING'))
      - Number(left.types.some((type) => pipelineStates[type].execution.status === 'RUNNING'))
      || Math.max(...right.types.map((type) => Date.parse(pipelineStates[type].execution.startedAt ?? '') || 0))
        - Math.max(...left.types.map((type) => Date.parse(pipelineStates[type].execution.startedAt ?? '') || 0)),
  )
  const internalRunning = Object.values(pipelineStates)
    .some((state) => state.execution.status === 'RUNNING')
  const isAnyPipelineRunning = internalRunning || externalBusy

  useEffect(() => { onBusyChange?.(internalRunning) }, [internalRunning, onBusyChange])
  useEffect(() => { setPendingCollection(null) }, [domain])
  useEffect(() => { if (externalBusy) setSelectedType(null) }, [externalBusy])

  useEffect(() => {
    mounted.current = true
    pipelineTypes.forEach((type) => void refresh(type, true))
    return () => {
      mounted.current = false
      pipelineTypes.forEach(clearPoll)
    }
  }, [])

  useEffect(() => {
    if (isAnyPipelineRunning) setPendingCollection(null)
  }, [isAnyPipelineRunning])

  function handleRun(type: DataPipelineType) {
    if (isAnyPipelineRunning) {
      return
    }
    if (type === 'COMPLEX_COLLECTION' || type === 'ANNOUNCEMENT_COLLECTION' || type === 'COMPLEX_SYNC' || type === 'ANNOUNCEMENT_SYNC') {
      setPendingCollection(type)
      return
    }
    setPendingCollection(null)
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

  async function execute(type: DataPipelineType, serviceKey?: string) {
    onExecutionStart?.()
    setSelectedType(type)
    clearPoll(type)
    const generation = nextGeneration(type)
    updateState(type, {
      execution: optimisticRunningExecution(type),
      requestError: null,
      errorResponse: null,
    })
    try {
      const execution = await (serviceKey === undefined ? startDataPipeline(type) : startDataPipeline(type, serviceKey))
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
      setSelectedType(type)
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
    <section className="registration-card data-pipeline-card" aria-label={compact ? '데이터 실행' : '데이터 수집·정제'}>
      {!compact && <div className="data-pipeline-heading">
        <div>
          <h2 id="data-pipeline-title">데이터 수집·정제</h2>
          <p>
            수집·정제를 선택하면 수집 후 정제까지 이어서 실행합니다.
            수집 실패·호출 제한이 있으면 자동 정제를 멈추고 결과를 남깁니다.
            수집 또는 정제만 따로 실행할 수도 있습니다.
          </p>
        </div>
      </div>}
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
                {group.id === 'announcement-pipelines' && registration?.action}
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
            {group.id === 'announcement-pipelines' && registration?.form}
            {!compact && group.id === 'complex-pipelines' ? (
              <p className="ingest-meta">새 단지 주소의 좌표가 없으면 단지 수집 → <Link to="/admin/locations">위치정보 ZIP 업로드</Link> → 단지 정제를 실행해 주세요.</p>
            ) : null}
            {pendingCollection !== null && group.types.some(type => type === pendingCollection) && !isAnyPipelineRunning ? (
              <CollectionStartForm key={pendingCollection} type={pendingCollection}
                onCancel={() => setPendingCollection(null)}
                onStart={serviceKey => {
                  if (isAnyPipelineRunning) return
                  setPendingCollection(null)
                  void execute(pendingCollection, serviceKey)
                }} />
            ) : null}
            <div className={compact ? undefined : 'data-pipeline-results'}>
              {compact ? <>
                {group.id === 'announcement-pipelines' && registration?.execution
                  ? <PipelineExecutionSteps key={registration.execution.executionId} execution={registration.execution}
                    stopping={registration.stopping} onStop={registration.onStop} />
                  : selectedType && group.types.some(type => type === selectedType)
                    && pipelineStates[selectedType].execution.status !== 'IDLE'
                    ? <PipelineExecutionSteps key={pipelineStates[selectedType].execution.executionId}
                      execution={pipelineStates[selectedType].execution} stopping={stopping[selectedType]}
                      onStop={() => void stop(selectedType)} /> : null}
                {group.types.map(type => pipelineStates[type].requestError
                  ? <p key={type} role="alert" className="form-error">{pipelineStates[type].requestError}</p> : null)}
              </> : group.types.map((type) => (
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
      {domain && ((!compact && externalBusy) || (domain === 'complex' && externalBusy) || pipelineGroups.filter(group => group.id !== `${domain}-pipelines`)
        .some(group => group.types.some(type => pipelineStates[type].execution.status === 'RUNNING'))) ?
        <p role="status">다른 탭에서 수집·정제 작업이 실행 중입니다. 완료 후 실행할 수 있습니다.</p> : null}
      {!compact && domain !== 'complex' && <LhAnnouncementQualityPanel collectionExecution={latestCollectionExecution(
        pipelineStates.ANNOUNCEMENT_SYNC.execution, pipelineStates.ANNOUNCEMENT_COLLECTION.execution,
      )} />}
    </section>
  )
}

function latestCollectionExecution(combined: DataPipelineExecution, collection: DataPipelineExecution): DataPipelineExecution {
  return (Date.parse(combined.startedAt ?? '') || 0) > (Date.parse(collection.startedAt ?? '') || 0)
    ? combined : collection
}

function CollectionStartForm({ type, onStart, onCancel }: {
  type: CollectionType; onStart: (serviceKey: string) => void; onCancel: () => void
}) {
  const [serviceKey, setServiceKey] = useState('')
  const [error, setError] = useState('')
  const input = useRef<HTMLInputElement>(null)
  const label = pipelineLabels[type]
  const inputId = `${type}-service-key`
  useEffect(() => { input.current?.focus() }, [])
  return <form className={styles.form} aria-label={`${label} API 키 입력`} onSubmit={event => {
    event.preventDefault()
    const executionKey = serviceKey.trim()
    if (!executionKey) {
      setError('공공데이터포털 API 키를 입력해 주세요.')
      input.current?.focus()
      return
    }
    setServiceKey('')
    onStart(executionKey)
  }}>
    <h4>{label}에 사용할 API 키</h4>
    <p id={`${inputId}-help`}>공공데이터포털 일반 인증키를 입력해 주세요. Decoding·Encoding 키 모두 사용할 수 있으며 이번 실행에만 사용합니다.</p>
    <a href="https://www.data.go.kr/" target="_blank" rel="noreferrer">공공데이터포털에서 API 키 발급</a>
    <label htmlFor={inputId}>공공데이터포털 API 키</label>
    <input ref={input} id={inputId} type="password" autoComplete="off" spellCheck={false}
      aria-describedby={`${inputId}-help${error ? ` ${inputId}-error` : ''}`} aria-invalid={error ? true : undefined}
      value={serviceKey} onChange={event => { setServiceKey(event.currentTarget.value); setError('') }} />
    {error ? <p id={`${inputId}-error`} className={styles.error} role="alert">{error}</p> : null}
    <div className={styles.actions}>
      <button className="admin-primary" type="submit">{label} 시작</button>
      <button type="button" onClick={onCancel}>취소</button>
    </div>
  </form>
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

function buttonLabel(type: DataPipelineType, status: DataPipelineExecution['status']): string {
  const label = pipelineLabels[type]
  if (status === 'RUNNING') {
    return `${label} 실행 중…`
  }
  return label
}
