import { pipelineLabels, pipelineStatusLabels } from './pipelineLabels'
import { Link } from 'react-router'
import { useEffect, useRef, useState } from 'react'
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
import { Button } from '../../design-system/components/Button'
import styles from './CollectionStartForm.module.css'
import workflow from './PipelineWorkflow.module.css'
import { ComplexIngestStages } from './ComplexIngestStages'

type DataPipelineType = SharedDataPipelineType

type CollectionType = 'COMPLEX_COLLECTION' | 'ANNOUNCEMENT_COLLECTION' | 'COMPLEX_SYNC' | 'ANNOUNCEMENT_SYNC'

const pollIntervalMilliseconds = 1_000
const pipelineTypes: readonly DataPipelineType[] = [
  'COMPLEX_COLLECTION',
  'COMPLEX_REFINEMENT',
  'ANNOUNCEMENT_COLLECTION',
  'ANNOUNCEMENT_REFINEMENT',
  'COMPLEX_SYNC',
  'ANNOUNCEMENT_SYNC',
  'SH_ANNOUNCEMENT_COLLECTION',
  'ANNOUNCEMENT_REGISTRATION',
]
const pipelineStepCounts: Record<DataPipelineType, number> = {
  COMPLEX_COLLECTION: 2,
  COMPLEX_REFINEMENT: 2,
  ANNOUNCEMENT_COLLECTION: 4,
  ANNOUNCEMENT_REFINEMENT: 2,
  COMPLEX_SYNC: 4,
  ANNOUNCEMENT_SYNC: 6,
  SH_ANNOUNCEMENT_COLLECTION: 1,
  ANNOUNCEMENT_REGISTRATION: 4,
}
const pipelineGroups = [
  {
    id: 'complex-pipelines',
    title: '단지 데이터',
    description: '처음 준비하거나 새 단지가 추가되었을 때 아래 순서로 진행해 주세요.',
    types: ['COMPLEX_SYNC', 'COMPLEX_COLLECTION', 'COMPLEX_REFINEMENT'],
  },
  {
    id: 'announcement-pipelines',
    title: '공고 데이터',
    description: '공고 갱신은 수집 후 정제까지 이어서 실행합니다. 단지가 준비되어 있다면 여기서 시작하세요.',
    types: ['ANNOUNCEMENT_SYNC', 'ANNOUNCEMENT_COLLECTION', 'ANNOUNCEMENT_REFINEMENT'],
  },
] as const satisfies readonly {
  id: string
  title: string
  description: string
  types: readonly DataPipelineType[]
}[]

export function DataPipelineControl({ domain, compact = false }: {
  domain?: 'complex' | 'announcement'; compact?: boolean
} = {}) {
  const [selectedType, setSelectedType] = useState<DataPipelineType | null>(null)
  const [pipelineStates, setPipelineStates] = useState(initialPipelineStates)
  const [stopping, setStopping] = useState<Partial<Record<DataPipelineType, boolean>>>({})
  const [pendingCollection, setPendingCollection] = useState<CollectionType | null>(null)
  const [startingType, setStartingType] = useState<DataPipelineType | null>(null)
  const starting = useRef<DataPipelineType | null>(null)
  const [checking, setChecking] = useState(true)
  const [qualityOpen, setQualityOpen] = useState(false)
  const [uploadBusy, setUploadBusy] = useState(false)
  const pollTimers = useRef<Partial<Record<DataPipelineType, number>>>({})
  const stateGenerations = useRef<Record<DataPipelineType, number>>({
    COMPLEX_COLLECTION: 0,
    COMPLEX_REFINEMENT: 0,
    ANNOUNCEMENT_COLLECTION: 0,
    ANNOUNCEMENT_REFINEMENT: 0,
    COMPLEX_SYNC: 0,
    ANNOUNCEMENT_SYNC: 0,
    SH_ANNOUNCEMENT_COLLECTION: 0,
    ANNOUNCEMENT_REGISTRATION: 0,
  })
  const mounted = useRef(true)
  const orderedGroups = pipelineGroups.filter(group => !domain || group.id === `${domain}-pipelines`).sort((left, right) =>
    Number(right.types.some((type) => pipelineStates[type].execution.status === 'RUNNING'))
      - Number(left.types.some((type) => pipelineStates[type].execution.status === 'RUNNING'))
      || Math.max(...right.types.map((type) => Date.parse(pipelineStates[type].execution.startedAt ?? '') || 0))
        - Math.max(...left.types.map((type) => Date.parse(pipelineStates[type].execution.startedAt ?? '') || 0))
      || Number(right.id === 'announcement-pipelines') - Number(left.id === 'announcement-pipelines'),
  )
  const internalRunning = Object.values(pipelineStates)
    .some((state) => state.execution.status === 'RUNNING')
  const isAnyPipelineRunning = internalRunning || startingType !== null || uploadBusy || (compact && checking)

  useEffect(() => { setPendingCollection(null) }, [domain])

  useEffect(() => {
    mounted.current = true
    void Promise.all(pipelineTypes.map(type => refresh(type, true, true))).finally(() => {
      if (mounted.current) setChecking(false)
    })
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
    if (starting.current || isAnyPipelineRunning) return
    starting.current = type
    setStartingType(type)
    setSelectedType(type)
    clearPoll(type)
    const generation = nextGeneration(type)
    updateState(type, {
      execution: idleExecution(type),
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

  async function refresh(type: DataPipelineType, retryOnFailure = false, revealRunning = false) {
    const generation = nextGeneration(type)
    try {
      const execution = await getDataPipelineStatus(type)
      if (mounted.current && isLatestGeneration(type, generation)) {
        applyExecution(type, execution)
        if (revealRunning && execution.status === 'RUNNING') setSelectedType(type)
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
    if (starting.current === type) {
      starting.current = null
      setStartingType(null)
    }
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

  const visibleTypes: readonly DataPipelineType[] = domain === 'complex' ? pipelineGroups[0].types
    : [...pipelineGroups[1].types, 'SH_ANNOUNCEMENT_COLLECTION'] as const
  const activeType = pendingCollection ?? (selectedType && visibleTypes.includes(selectedType) ? selectedType : null)
  const runningElsewhere = pipelineTypes.find(type => !visibleTypes.includes(type)
    && pipelineStates[type].execution.status === 'RUNNING')
  const lockNotice = runningElsewhere ? <p role="status" className={workflow.notice}>
    {pipelineLabels[runningElsewhere]} 실행 중입니다. 수집·정제는 한 번에 한 작업만 실행할 수 있습니다.{' '}
    <Link to={runningElsewhere === 'ANNOUNCEMENT_REGISTRATION' ? '/admin/announcements/new?mode=myhome'
      : `/admin/ingest/${domain === 'complex' ? 'announcements' : 'complexes'}`}>실행 중인 작업 보기</Link>
  </p> : null
  if (domain === 'complex') return <section aria-label="단지 수집 작업">
    {lockNotice}
    {checking && <p role="status">진행 중인 작업과 마지막 결과를 확인하고 있습니다…</p>}
    {pipelineTypes.filter(type => !visibleTypes.includes(type)).map(type => pipelineStates[type].requestError && <p role="alert" key={type}>
      {pipelineLabels[type]} 상태 확인 실패: {pipelineStates[type].requestError}
    </p>)}
    <ComplexIngestStages states={pipelineStates} disabled={isAnyPipelineRunning} startingType={startingType} stopping={stopping}
      onStart={(type, key) => void execute(type, key)} onStop={type => void stop(type)}
      onRefresh={type => void refresh(type, true)} onUploadBusy={setUploadBusy} />
  </section>
  function recent(types: readonly DataPipelineType[]) {
    const latest = [...types].map(type => pipelineStates[type].execution)
      .filter(run => run.status !== 'IDLE').sort((a, b) => (Date.parse(b.startedAt ?? '') || 0) - (Date.parse(a.startedAt ?? '') || 0))[0]
    return <div className={workflow.recent}>
      {latest ? <><span>{pipelineLabels[latest.type]}</span>
        <strong className={`pipeline-badge pipeline-${latest.status.toLowerCase()}`}>{pipelineStatusLabels[latest.status]}</strong>
        <span>{latest.startedAt ? new Date(latest.startedAt).toLocaleString('ko-KR') : '시각 기록 없음'}</span>
        <button type="button" onClick={() => { setPendingCollection(null); setSelectedType(latest.type) }}>실행 결과 보기</button></>
        : <span>{checking ? '최근 실행 확인 중…' : types.some(type => pipelineStates[type].requestError)
          ? '최근 실행을 확인하지 못했습니다.' : '아직 실행 이력이 없습니다.'}</span>}
    </div>
  }
  if (compact && domain) return <section className={`registration-card ${workflow.workspace}`} aria-label="데이터 실행">
    {lockNotice}
    {activeType ? <>
      <button type="button" className={workflow.back} onClick={() => { setPendingCollection(null); setSelectedType(null) }}>
        공고 수집 개요로 돌아가기
      </button>
      <PipelineExecutionSteps focused pending={startingType === activeType} key={activeType} execution={pendingCollection ? idleExecution(pendingCollection) : pipelineStates[activeType].execution}
        stopping={stopping[activeType]} onStop={() => void stop(activeType)} recoveryDisabled={isAnyPipelineRunning}
        onRefine={() => handleRun('ANNOUNCEMENT_REFINEMENT')}>
        {pendingCollection ? <CollectionStartForm key={pendingCollection} type={pendingCollection}
          onCancel={() => { setPendingCollection(null); setSelectedType(null) }}
          onStart={serviceKey => { const type = pendingCollection; setPendingCollection(null); void execute(type, serviceKey) }} />
          : startingType === activeType ? <div role="status"><h4>실행 접수를 확인하고 있습니다</h4>
            <p>서버에서 실행을 받아들였는지 확인 중입니다. 확인 전에는 다시 실행할 수 없습니다.</p></div> : null}
      </PipelineExecutionSteps>
      {pipelineStates[activeType].requestError && <div role="alert" className={workflow.notice}>
        <strong>{startingType === activeType ? '접수 여부를 확인하지 못했습니다.' : '상태 조회 또는 요청에 실패했습니다.'}</strong>
        <p>{pipelineStates[activeType].requestError} {(startingType === activeType || pipelineStates[activeType].execution.status === 'RUNNING') && '마지막으로 확인한 상태를 유지하며 자동으로 다시 확인합니다.'}</p>
        <button type="button" onClick={() => void refresh(activeType, true)}>상태 다시 확인</button>
      </div>}
      {pipelineStates[activeType].execution.status === 'RUNNING' && <p className="ingest-meta">다른 화면으로 이동해도 실행은 계속됩니다. 다시 들어오면 진행 상태를 확인할 수 있습니다.</p>}
    </> : <>
      <>
        <div className={workflow.sources}>
          <section aria-labelledby="myhome-source" className={workflow.source}>
            <header><h2 id="myhome-source">마이홈</h2><span>LH 정보 보강 포함</span></header>
            <p>마이홈 공고와 LH 목록·공급·상세를 수집한 뒤 저장된 원천을 정제합니다.</p>
            {recent(pipelineGroups[1].types)}
            <Button data-design-system disabled={isAnyPipelineRunning} onClick={() => handleRun('ANNOUNCEMENT_SYNC')}>마이홈 다건 갱신</Button>
            <details className={workflow.additional}><summary>수집·정제 따로 실행</summary>
              <p>정제는 이전 수집을 포함한 저장 원천 전체에 적용합니다.</p>
              <div className={workflow.actions}>{pipelineGroups[1].types.slice(1).map(type => <button key={type} type="button"
                disabled={isAnyPipelineRunning} onClick={() => handleRun(type)}>{pipelineLabels[type]}</button>)}</div>
            </details>
          </section>
          <section aria-labelledby="sh-source" className={workflow.source}>
            <header><h2 id="sh-source">SH</h2><span>API 키 불필요</span></header>
            <p>‘모집’ 검색 결과의 제목·본문·첨부를 원천으로 저장합니다. 서비스 공고 등록·정제는 포함하지 않습니다.</p>
            {recent(['SH_ANNOUNCEMENT_COLLECTION'])}
            <button type="button" disabled={isAnyPipelineRunning} onClick={() => handleRun('SH_ANNOUNCEMENT_COLLECTION')}>SH 공고 수집</button>
          </section>
        </div>
        <p className={workflow.registrationLink}>공고 한 건을 추가하려면 <Link to="/admin/announcements/new?mode=myhome">공고 관리에서 마이홈 공고 가져오기</Link></p>
        <details className={workflow.additional} onToggle={event => setQualityOpen(event.currentTarget.open)}>
          <summary>LH 데이터 품질·공급 감소 보류 확인</summary>
          <p className="ingest-meta">LH 공고만의 연결·금액·일정 상태입니다. 수집 실행의 성공 여부와 별도로 확인합니다.</p>
          {qualityOpen && <LhAnnouncementQualityPanel collectionExecution={latestCollectionExecution(
            pipelineStates.ANNOUNCEMENT_SYNC.execution, pipelineStates.ANNOUNCEMENT_COLLECTION.execution)} />}
        </details>
      </>
      {visibleTypes.map(type => pipelineStates[type].requestError && <p key={type} role="alert" className="form-error">
        {pipelineLabels[type]}: {pipelineStates[type].requestError}</p>)}
    </>}
  </section>

  return (
    <section className="registration-card data-pipeline-card" aria-label={compact ? '데이터 실행' : '데이터 수집·정제'}>
      {!compact && <div className="data-pipeline-heading">
        <div>
          <h2 id="data-pipeline-title">데이터 수집·정제</h2>
          <p>
            평소에는 공고를 갱신하고, 단지는 처음 준비하거나 누락된 단지를 보완할 때 처리합니다.
            수집 실패·호출 제한이 있으면 자동 정제를 멈춥니다. 결과를 확인한 뒤 다음 작업을 선택해 주세요.
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
              <div className={workflow.actions}>
                {group.id === 'announcement-pipelines' && <Button data-design-system disabled={isAnyPipelineRunning}
                  onClick={() => handleRun('ANNOUNCEMENT_SYNC')}>
                  {buttonLabel('ANNOUNCEMENT_SYNC', pipelineStates.ANNOUNCEMENT_SYNC.execution.status)}
                </Button>}
              </div>
            </div>
            {group.id === 'complex-pipelines' && <ol className={workflow.preparation} aria-label="단지 준비 순서">
              <li><strong>1. 단지 원천 수집</strong><p>마이홈 단지와 LH 임대 카탈로그를 가져옵니다.</p>
                <Button data-design-system disabled={isAnyPipelineRunning} onClick={() => handleRun('COMPLEX_COLLECTION')}>
                  {buttonLabel('COMPLEX_COLLECTION', pipelineStates.COMPLEX_COLLECTION.execution.status)}</Button></li>
              <li><strong>2. 주소에 맞는 좌표 적재</strong><p>새 단지 주소가 있다면 ZIP을 업로드합니다. 준비된 좌표는 그대로 사용합니다.</p>
                <Link to="/admin/locations">위치정보 ZIP 업로드</Link></li>
              <li><strong>3. 단지 정제·저장</strong><p>저장된 원천 전체를 정제하고 단지·주택형 결과를 확인합니다.</p>
                <button type="button" disabled={isAnyPipelineRunning} onClick={() => handleRun('COMPLEX_REFINEMENT')}>
                  {buttonLabel('COMPLEX_REFINEMENT', pipelineStates.COMPLEX_REFINEMENT.execution.status)}</button></li>
            </ol>}
            <details className={workflow.additional}>
              <summary>{group.id === 'complex-pipelines' ? '단지 추가 작업' : '공고 추가 작업'}</summary>
              <p className="ingest-meta">{group.id === 'complex-pipelines'
                ? '좌표가 준비된 단지는 수집과 정제를 이어서 실행할 수 있습니다.'
                : '수집만 다시 하거나, 외부 호출 없이 저장된 공고 원천 전체를 정제할 수 있습니다. 이전 수집의 원천도 포함됩니다.'}</p>
              <div className={workflow.actions}>
                {(group.id === 'complex-pipelines' ? [group.types[0]] : [group.types[1], group.types[2]]).map(type =>
                  <button type="button" key={type} disabled={isAnyPipelineRunning} onClick={() => handleRun(type)}>
                    {buttonLabel(type, pipelineStates[type].execution.status)}</button>)}
              </div>
            </details>
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
        {<section aria-labelledby="sh-pipelines" className="data-pipeline-group">
          <div className="data-pipeline-group-heading">
            <div>
              <h3 id="sh-pipelines">SH 공고 원천</h3>
              <p>SH의 ‘모집’ 검색 결과에서 제목·본문 텍스트·첨부 정보를 추출해 저장합니다. API 키 없이 실행합니다.</p>
            </div>
            <button type="button" disabled={isAnyPipelineRunning}
              onClick={() => handleRun('SH_ANNOUNCEMENT_COLLECTION')}>
              {buttonLabel('SH_ANNOUNCEMENT_COLLECTION', pipelineStates.SH_ANNOUNCEMENT_COLLECTION.execution.status)}
            </button>
          </div>
          <div className={compact ? undefined : 'data-pipeline-results'}>
            {compact ? <>
              {selectedType === 'SH_ANNOUNCEMENT_COLLECTION'
                && pipelineStates.SH_ANNOUNCEMENT_COLLECTION.execution.status !== 'IDLE'
                && <PipelineExecutionSteps execution={pipelineStates.SH_ANNOUNCEMENT_COLLECTION.execution}
                  stopping={stopping.SH_ANNOUNCEMENT_COLLECTION}
                  onStop={() => void stop('SH_ANNOUNCEMENT_COLLECTION')} />}
              {pipelineStates.SH_ANNOUNCEMENT_COLLECTION.requestError
                && <p role="alert" className="form-error">{pipelineStates.SH_ANNOUNCEMENT_COLLECTION.requestError}</p>}
            </> : <PipelineResult type="SH_ANNOUNCEMENT_COLLECTION" state={pipelineStates.SH_ANNOUNCEMENT_COLLECTION}
              stopping={stopping.SH_ANNOUNCEMENT_COLLECTION ?? false}
              onStop={() => void stop('SH_ANNOUNCEMENT_COLLECTION')} />}
          </div>
        </section>}
      </div>
      {!compact && <LhAnnouncementQualityPanel collectionExecution={latestCollectionExecution(
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
  const [showKey, setShowKey] = useState(false)
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
    <h4>API 키 입력</h4>
    {type === 'ANNOUNCEMENT_SYNC' && <p>마이홈과 LH 수집 후 자동 정제까지 진행합니다. 수집 실패·호출 제한이 있으면 자동 정제를 멈춥니다. 정제에는 이전에 저장한 원천도 포함됩니다.</p>}
    <p id={`${inputId}-help`}>공공데이터포털 일반 인증키를 입력해 주세요. Decoding·Encoding 키 모두 사용할 수 있으며 이번 실행에만 사용합니다.</p>
    <a href="https://www.data.go.kr/" target="_blank" rel="noreferrer">공공데이터포털에서 API 키 발급</a>
    <label htmlFor={inputId}>공공데이터포털 API 키</label>
    <div className={styles.keyInput}><input ref={input} id={inputId} type={showKey ? 'text' : 'password'} maxLength={4096} autoComplete="off" spellCheck={false}
      aria-describedby={`${inputId}-help${error ? ` ${inputId}-error` : ''}`} aria-invalid={error ? true : undefined}
      value={serviceKey} onChange={event => { setServiceKey(event.currentTarget.value); setError('') }} />
      <button type="button" aria-label={showKey ? 'API 키 숨기기' : 'API 키 보기'} onClick={() => setShowKey(value => !value)}>{showKey ? '숨기기' : '보기'}</button></div>
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
    SH_ANNOUNCEMENT_COLLECTION: viewState(idleExecution('SH_ANNOUNCEMENT_COLLECTION')),
    ANNOUNCEMENT_REGISTRATION: viewState(idleExecution('ANNOUNCEMENT_REGISTRATION')),
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

function buttonLabel(type: DataPipelineType, status: DataPipelineExecution['status']): string {
  const label = pipelineLabels[type]
  if (status === 'RUNNING') {
    return `${label} 실행 중…`
  }
  return label
}
