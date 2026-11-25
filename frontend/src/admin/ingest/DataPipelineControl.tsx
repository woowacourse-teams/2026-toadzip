import { pipelineLabels, pipelineStatusLabels } from './pipelineLabels'
import { Link } from 'react-router'
import { useEffect, useRef, useState, type ReactNode } from 'react'
import {
  getDataPipelineReadiness,
  getDataPipelineStatus,
  startDataPipeline,
  retryInitialRegistration,
  stopDataPipeline,
  type DataPipelineExecution,
  type DataPipelineType as SharedDataPipelineType,
} from './api'

import { LhAnnouncementQualityPanel } from './LhAnnouncementQualityPanel'
import { PipelineExecutionSteps } from './PipelineExecutionSteps'
import { Button } from '../../design-system/components/Button'
import workflow from './PipelineWorkflow.module.css'
import { ComplexIngestStages } from './ComplexIngestStages'
import { pipelineStepSequences, type PipelineViewState } from './pipelineSteps'
import { useConfirm } from '../shared/ConfirmDialog'
import { AdminIcon } from '../shared/AdminIcon'

type DataPipelineType = SharedDataPipelineType

// 공공데이터포털을 호출해 서버의 수집 키가 있어야 시작할 수 있는 실행이다.
const keyRequiredTypes: readonly DataPipelineType[] = ['COMPLEX_COLLECTION', 'ANNOUNCEMENT_COLLECTION', 'ANNOUNCEMENT_SYNC']

const pollIntervalMilliseconds = 1_000
const pipelineTypes: readonly DataPipelineType[] = [
  'COMPLEX_REGISTRATION',
  'COMPLEX_COLLECTION',
  'COMPLEX_REFINEMENT',
  'ANNOUNCEMENT_COLLECTION',
  'ANNOUNCEMENT_REFINEMENT',
  'COMPLEX_SYNC',
  'ANNOUNCEMENT_SYNC',
  'SH_ANNOUNCEMENT_COLLECTION',
  'GH_ANNOUNCEMENT_COLLECTION',
  'ANNOUNCEMENT_REGISTRATION',
]
const complexTypes: readonly DataPipelineType[] = ['COMPLEX_SYNC', 'COMPLEX_COLLECTION', 'COMPLEX_REFINEMENT', 'COMPLEX_REGISTRATION']
const lhAnnouncementTypes: readonly DataPipelineType[] = ['ANNOUNCEMENT_SYNC', 'ANNOUNCEMENT_COLLECTION', 'ANNOUNCEMENT_REFINEMENT']

// 공고 수집 첫 화면은 기관마다 실행 버튼 하나와 마지막 결과만 보인다. LH 마지막 결과는 따로 실행했던 수집·정제 이력도 포함한다.
const agencySources: readonly { id: string; title: string; runType: DataPipelineType; resultTypes: readonly DataPipelineType[] }[] = [
  { id: 'lh', title: 'LH', runType: 'ANNOUNCEMENT_SYNC', resultTypes: lhAnnouncementTypes },
  // SH·GH는 공식 게시판을 API 키 없이 읽어 원천 저장 뒤 미확정 공고로 정제한다.
  { id: 'sh', title: 'SH', runType: 'SH_ANNOUNCEMENT_COLLECTION', resultTypes: ['SH_ANNOUNCEMENT_COLLECTION'] },
  { id: 'gh', title: 'GH', runType: 'GH_ANNOUNCEMENT_COLLECTION', resultTypes: ['GH_ANNOUNCEMENT_COLLECTION'] },
]

export function DataPipelineControl({ domain, history, onShowHistory }: {
  // 공고 수집 화면에서 실행 이력을 실행 카드와 접어 둔 LH 품질 확인 사이에 둔다.
  domain: 'complex' | 'announcement'; history?: ReactNode; onShowHistory?: () => void
}) {
  const [selectedType, setSelectedType] = useState<DataPipelineType | null>(null)
  const [pipelineStates, setPipelineStates] = useState(initialPipelineStates)
  const [stopping, setStopping] = useState<Partial<Record<DataPipelineType, boolean>>>({})
  const [serviceKeyConfigured, setServiceKeyConfigured] = useState<boolean | null>(null)
  const [startingType, setStartingType] = useState<DataPipelineType | null>(null)
  const starting = useRef<DataPipelineType | null>(null)
  const [checking, setChecking] = useState(true)
  const [qualityOpen, setQualityOpen] = useState(false)
  const [uploadBusy, setUploadBusy] = useState(false)
  const [confirm, confirmDialog] = useConfirm()
  const pollTimers = useRef<Partial<Record<DataPipelineType, number>>>({})
  const stateGenerations = useRef<Record<DataPipelineType, number>>({
    COMPLEX_REGISTRATION: 0,
    COMPLEX_COLLECTION: 0,
    COMPLEX_REFINEMENT: 0,
    ANNOUNCEMENT_COLLECTION: 0,
    ANNOUNCEMENT_REFINEMENT: 0,
    COMPLEX_SYNC: 0,
    ANNOUNCEMENT_SYNC: 0,
    SH_ANNOUNCEMENT_COLLECTION: 0,
    GH_ANNOUNCEMENT_COLLECTION: 0,
    ANNOUNCEMENT_REGISTRATION: 0,
  })
  const mounted = useRef(true)
  const internalRunning = Object.values(pipelineStates)
    .some((state) => state.execution.status === 'RUNNING')
  const isAnyPipelineRunning = internalRunning || startingType !== null || uploadBusy || checking

  useEffect(() => {
    mounted.current = true
    // 확인하지 못하면 막지 않는다. 키가 없으면 서버가 시작을 거절하고 그 안내를 보여 준다.
    void getDataPipelineReadiness().then(
      readiness => { if (mounted.current) setServiceKeyConfigured(readiness.serviceKeyConfigured) },
      () => undefined,
    )
    void Promise.all(pipelineTypes.map(type => refresh(type, true, true))).finally(() => {
      if (mounted.current) setChecking(false)
    })
    return () => {
      mounted.current = false
      pipelineTypes.forEach(clearPoll)
    }
  }, [])

  const serviceKeyMissing = serviceKeyConfigured === false
  const blockedByServiceKey = (type: DataPipelineType) => serviceKeyMissing && keyRequiredTypes.includes(type)

  function handleRun(type: DataPipelineType) {
    if (isAnyPipelineRunning || blockedByServiceKey(type)) {
      return
    }
    void execute(type)
  }

  // 저장된 공고 원천 전체를 다시 정제하므로 범위를 알리고 묻는다.
  async function refineAll() {
    if (isAnyPipelineRunning) return
    if (!(await confirm({
      title: '저장된 원천 전체로 공고 정제를 실행할까요?',
      body: <>
        <p>이번에 수집한 분량만이 아니라 저장된 공고 원천 전체를 다시 정제합니다. 이전 수집의 원천도 포함됩니다.</p>
        <p>실행 중에는 다른 수집·정제를 시작할 수 없습니다. 도중에 중지할 수 있지만 이미 정제한 내용은 되돌아가지 않습니다.</p>
      </>,
      confirmLabel: '공고 정제 실행',
    }))) return
    handleRun('ANNOUNCEMENT_REFINEMENT')
  }

  async function stop(type: DataPipelineType) {
    const executionId = pipelineStates[type].execution.executionId
    if (!executionId || stopping[type]) return
    if (!(await confirm({
      title: `${pipelineLabels[type]} 실행을 중지할까요?`,
      body: '현재 처리 중인 항목이 끝나면 멈춥니다. 이미 저장한 데이터는 유지되고, 남은 대상은 다시 실행해야 처리됩니다.',
      confirmLabel: '실행 중지',
    }))) return
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

  async function execute(type: DataPipelineType, identifiers?: string[]) {
    if (starting.current || isAnyPipelineRunning) return
    starting.current = type
    setStartingType(type)
    setSelectedType(type)
    clearPoll(type)
    const generation = nextGeneration(type)
    updateState(type, {
      execution: idleExecution(type),
      requestError: null,
    })
    try {
      const execution = identifiers ? await retryInitialRegistration(identifiers) : await startDataPipeline(type)
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
    updateState(type, (previous) => ({
      ...previous,
      requestError: message,
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

  const visibleTypes: readonly DataPipelineType[] = domain === 'complex' ? complexTypes
    : agencySources.flatMap(source => source.resultTypes)
  const activeType = selectedType && visibleTypes.includes(selectedType) ? selectedType : null
  const runningElsewhere = pipelineTypes.find(type => !visibleTypes.includes(type)
    && pipelineStates[type].execution.status === 'RUNNING')
  const lockNotice = runningElsewhere ? <p role="status" className={workflow.notice}>
    {pipelineLabels[runningElsewhere]} 실행 중입니다. 수집·정제는 한 번에 한 작업만 실행할 수 있습니다.
    {/* 지원 종료한 단건 등록은 진행을 볼 화면이 없으므로 이동 링크를 두지 않는다. */}
    {runningElsewhere !== 'ANNOUNCEMENT_REGISTRATION' && <>{' '}
      <Link to={`/admin/ingest/${domain === 'complex' ? 'announcements' : 'complexes'}`}>실행 중인 작업 보기</Link></>}
  </p> : null
  const serviceKeyNotice = serviceKeyMissing ? <div role="alert" className={workflow.notice}>
    <strong>서버 수집 키 미설정</strong>
    <p>서버 환경변수 DATA_GO_KR_SERVICE_KEY가 없어 공공데이터포털 수집을 실행할 수 없습니다.</p>
  </div> : null
  if (domain === 'complex') return <section aria-label="단지 수집 작업">
    {lockNotice}
    {serviceKeyNotice}
    {checking && <p role="status">진행 중인 작업과 마지막 결과를 확인하고 있습니다…</p>}
    {pipelineTypes.filter(type => !visibleTypes.includes(type)).map(type => pipelineStates[type].requestError && <p role="alert" key={type}>
      {pipelineLabels[type]} 상태 확인 실패: {pipelineStates[type].requestError}
    </p>)}
    <ComplexIngestStages states={pipelineStates} disabled={isAnyPipelineRunning} collectionBlocked={serviceKeyMissing}
      startingType={startingType} stopping={stopping}
      onStart={type => handleRun(type)} onRetry={identifiers => void execute('COMPLEX_REGISTRATION', identifiers)}
      onStop={type => void stop(type)}
      onRefresh={type => void refresh(type, true)} onUploadBusy={setUploadBusy} onShowHistory={onShowHistory} />
    {confirmDialog}
  </section>
  function recent(source: typeof agencySources[number]) {
    const latest = source.resultTypes.map(type => pipelineStates[type].execution)
      .filter(run => run.status !== 'IDLE').sort((a, b) => (Date.parse(b.startedAt ?? '') || 0) - (Date.parse(a.startedAt ?? '') || 0))[0]
    return <div className={workflow.recent}>
      <div className={workflow.recentHeading}>
        <span>마지막 결과</span>
        {latest && <button type="button" className="admin-text-button" onClick={() => setSelectedType(latest.type)}>실행 결과 보기</button>}
      </div>
      {latest ? <div className={workflow.recentValue}>
        <strong className={`pipeline-badge pipeline-${latest.status.toLowerCase()}`}>{pipelineStatusLabels[latest.status]}</strong>
        {/* 따로 실행했던 수집·정제가 마지막이면 어떤 실행인지 함께 적는다. */}
        {latest.type !== source.runType && <span>{pipelineLabels[latest.type]}</span>}
        <time dateTime={latest.startedAt ?? undefined}>{latest.startedAt ? new Date(latest.startedAt).toLocaleString('ko-KR') : '시각 기록 없음'}</time>
      </div> : <span className={workflow.recentEmpty}>{checking ? '확인 중…' : source.resultTypes.some(type => pipelineStates[type].requestError)
        ? '최근 실행을 확인하지 못했습니다.' : '아직 실행 이력이 없습니다.'}</span>}
    </div>
  }
  return <>
  <section className={`registration-card ${workflow.workspace}`} aria-label="데이터 실행">
    {lockNotice}
    {serviceKeyNotice}
    {activeType ? <>
      <button type="button" className={workflow.back} onClick={() => setSelectedType(null)}>
        공고 수집 개요로 돌아가기
      </button>
      <PipelineExecutionSteps focused pending={startingType === activeType} key={activeType} execution={pipelineStates[activeType].execution}
        stopping={stopping[activeType]} onStop={() => void stop(activeType)} recoveryDisabled={isAnyPipelineRunning}
        onRefine={() => void refineAll()}>
        {startingType === activeType ? <div role="status"><h4>실행 접수를 확인하고 있습니다</h4>
            <p>서버에서 실행을 받아들였는지 확인 중입니다. 확인 전에는 다시 실행할 수 없습니다.</p></div> : null}
      </PipelineExecutionSteps>
      {pipelineStates[activeType].requestError && <div role="alert" className={workflow.notice}>
        <strong>{startingType === activeType ? '접수 여부를 확인하지 못했습니다.' : '상태 조회 또는 요청에 실패했습니다.'}</strong>
        <p>{pipelineStates[activeType].requestError} {(startingType === activeType || pipelineStates[activeType].execution.status === 'RUNNING') && '마지막으로 확인한 상태를 유지하며 자동으로 다시 확인합니다.'}</p>
        <button type="button" onClick={() => void refresh(activeType, true)}>상태 다시 확인</button>
      </div>}
      {pipelineStates[activeType].execution.status === 'RUNNING' && <p className="ingest-meta">다른 화면으로 이동해도 실행은 계속됩니다. 다시 들어오면 진행 상태를 확인할 수 있습니다.</p>}
    </> : <>
      <div className={workflow.sources}>
        {agencySources.map(source => <section key={source.id} aria-labelledby={`${source.id}-source`} className={workflow.source}>
          <header>
            <h2 id={`${source.id}-source`}>{source.title}</h2>
            <Button data-design-system variant="secondary" disabled={isAnyPipelineRunning || blockedByServiceKey(source.runType)}
              onClick={() => handleRun(source.runType)}>{pipelineLabels[source.runType]}</Button>
          </header>
          {recent(source)}
        </section>)}
      </div>
      {visibleTypes.map(type => pipelineStates[type].requestError && <p key={type} role="alert" className="form-error">
        {pipelineLabels[type]}: {pipelineStates[type].requestError}</p>)}
    </>}
  </section>
  {history}
  {/* LH 공급 감소 보류 승인은 이 메뉴에서만 할 수 있어 지우지 않고 맨 아래에 접어 둔다. */}
  {!activeType && <details className={workflow.quality} open={qualityOpen} onToggle={event => setQualityOpen(event.currentTarget.open)}>
    <summary>LH 데이터 품질·공급 감소 보류 확인<AdminIcon name="chevronDown" size={16} className={workflow.qualityChevron} /></summary>
    {qualityOpen && <LhAnnouncementQualityPanel collectionExecution={latestCollectionExecution(
      pipelineStates.ANNOUNCEMENT_SYNC.execution, pipelineStates.ANNOUNCEMENT_COLLECTION.execution)} />}
  </details>}
  {confirmDialog}
  </>
}

function latestCollectionExecution(combined: DataPipelineExecution, collection: DataPipelineExecution): DataPipelineExecution {
  return (Date.parse(combined.startedAt ?? '') || 0) > (Date.parse(collection.startedAt ?? '') || 0)
    ? combined : collection
}

function initialPipelineStates(): Record<DataPipelineType, PipelineViewState> {
  return {
    COMPLEX_REGISTRATION: viewState(idleExecution('COMPLEX_REGISTRATION')),
    COMPLEX_COLLECTION: viewState(idleExecution('COMPLEX_COLLECTION')),
    COMPLEX_REFINEMENT: viewState(idleExecution('COMPLEX_REFINEMENT')),
    ANNOUNCEMENT_COLLECTION: viewState(idleExecution('ANNOUNCEMENT_COLLECTION')),
    ANNOUNCEMENT_REFINEMENT: viewState(idleExecution('ANNOUNCEMENT_REFINEMENT')),
    COMPLEX_SYNC: viewState(idleExecution('COMPLEX_SYNC')),
    ANNOUNCEMENT_SYNC: viewState(idleExecution('ANNOUNCEMENT_SYNC')),
    SH_ANNOUNCEMENT_COLLECTION: viewState(idleExecution('SH_ANNOUNCEMENT_COLLECTION')),
    GH_ANNOUNCEMENT_COLLECTION: viewState(idleExecution('GH_ANNOUNCEMENT_COLLECTION')),
    ANNOUNCEMENT_REGISTRATION: viewState(idleExecution('ANNOUNCEMENT_REGISTRATION')),
  }
}

function viewState(execution: DataPipelineExecution): PipelineViewState {
  return { execution, requestError: null }
}

function idleExecution(type: DataPipelineType): DataPipelineExecution {
  return {
    executionId: null,
    type,
    status: 'IDLE',
    currentStepName: null,
    currentStepIndex: 0,
    totalStepCount: pipelineStepSequences[type].length,
    completedSteps: [],
    skippedSteps: [],
    partiallyFailedSteps: [],
    failure: null,
  }
}
