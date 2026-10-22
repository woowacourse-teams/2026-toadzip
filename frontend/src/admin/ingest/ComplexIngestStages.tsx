import { useId, useRef, useState, type KeyboardEvent } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { Button } from '../../design-system/components/Button'
import type { DataPipelineExecution, DataPipelineType } from './api'
import type { PipelineViewState } from './PipelineResult'
import { ComplexExecutionResult, ComplexExecutionSummary } from './ComplexExecutionResult'
import { LocationSummaryUpload } from './LocationSummaryUpload'
import { pipelineStepDefinitions, pipelineStepSequences } from './pipelineSteps'
import styles from './ComplexIngest.module.css'
import { ComplexStageHelp } from './ComplexStageHelp'

export function ComplexIngestStages({ states, disabled, startingType, stopping, onStart, onStop, onRefresh, onUploadBusy }: {
  states: Record<DataPipelineType, PipelineViewState>; disabled: boolean; startingType: DataPipelineType | null
  stopping: Partial<Record<DataPipelineType, boolean>>; onStart: (type: DataPipelineType, key?: string) => void
  onStop: (type: DataPipelineType) => void; onRefresh: (type: DataPipelineType) => void; onUploadBusy: (busy: boolean) => void
}) {
  const location = useLocation()
  const navigate = useNavigate()
  const tabId = useId()
  const tabs = useRef<(HTMLButtonElement | null)[]>([])
  const [uploading, setUploading] = useState(false)
  const steps = [
    { id: 'complex-collection', label: '1 원천 수집' },
    { id: 'complex-location', label: '2 좌표 적재' },
    { id: 'complex-refinement', label: '3 정제·저장' },
  ]
  const selected = Math.max(0, steps.findIndex(step => location.hash === `#${step.id}`))
  function select(index: number) {
    void navigate({ search: location.search, hash: `#${steps[index].id}` }, { replace: true, preventScrollReset: true })
  }
  function move(event: KeyboardEvent<HTMLButtonElement>, index: number) {
    const next = event.key === 'ArrowRight' ? (index + 1) % steps.length
      : event.key === 'ArrowLeft' ? (index + steps.length - 1) % steps.length
      : event.key === 'Home' ? 0 : event.key === 'End' ? steps.length - 1 : null
    if (next === null) return
    event.preventDefault()
    select(next)
    tabs.current[next]?.focus()
  }
  const combined = states.COMPLEX_SYNC.execution
  function latest(type: 'COMPLEX_COLLECTION' | 'COMPLEX_REFINEMENT'): DataPipelineExecution {
    const own = states[type].execution
    if (startingType === type) return own
    return combined.status === 'RUNNING' || (Date.parse(combined.startedAt ?? '') || 0) > (Date.parse(own.startedAt ?? '') || 0)
      ? combined : own
  }
  function isRunning(index: number) {
    if (index === 1) return uploading
    const type = index === 0 ? 'COMPLEX_COLLECTION' : 'COMPLEX_REFINEMENT'
    const execution = latest(type)
    return execution.status === 'RUNNING' && (execution.type !== 'COMPLEX_SYNC'
      || pipelineStepSequences[type].some(step => pipelineStepDefinitions[step] === execution.currentStepName)
      || (!execution.currentStepName && index === 0))
  }
  function result(type: 'COMPLEX_COLLECTION' | 'COMPLEX_REFINEMENT') {
    const execution = latest(type)
    return <>
      <ComplexExecutionResult key={`${execution.executionId}-${type}`} execution={execution} showSummary={false}
        phase={type === 'COMPLEX_COLLECTION' ? 'collection' : 'refinement'} pending={startingType === type}
        stopping={stopping[execution.type]} onStop={() => onStop(execution.type)} />
      {[type, 'COMPLEX_SYNC' as const].map(value => states[value].requestError && <div role="alert" className={styles.notice} key={value}>
        <p>{states[value].requestError}</p><button type="button" onClick={() => onRefresh(value)}>상태 다시 확인</button>
      </div>)}
    </>
  }
  return <div className={styles.stages}>
    <div className={styles.tabs} role="tablist" aria-label="단지 수집 단계">
      {steps.map((step, index) => <button data-design-system key={step.id} type="button" role="tab" id={`${tabId}-${index}`}
        ref={element => { tabs.current[index] = element }} aria-selected={selected === index}
        aria-controls={step.id} aria-label={step.label} tabIndex={selected === index ? 0 : -1}
        aria-describedby={isRunning(index) ? `${tabId}-${index}-status` : undefined}
        onClick={() => select(index)} onKeyDown={event => move(event, index)}>
        {step.label}
        {isRunning(index) && <span id={`${tabId}-${index}-status`} className={styles.running}>진행 중</span>}
      </button>)}
    </div>
    <section className={styles.stage} id="complex-collection" role="tabpanel" hidden={selected !== 0} aria-labelledby={`${tabId}-0`}>
      <header><div className={styles.stageTitle}><span className={styles.number}>1</span><h2 id="complex-collection-title">단지 원천 수집</h2>
        <ComplexStageHelp stage="단지 원천 수집">마이홈 단지와 LH 임대 카탈로그를 수집해 원본을 저장합니다.</ComplexStageHelp></div>
        <ComplexExecutionSummary execution={latest('COMPLEX_COLLECTION')} pending={startingType === 'COMPLEX_COLLECTION'} /></header>
      <Button data-design-system className={styles.collectionButton} disabled={disabled} onClick={() => onStart('COMPLEX_COLLECTION')}>단지 원천 수집 실행</Button>
      {result('COMPLEX_COLLECTION')}
    </section>
    <section className={styles.stage} id="complex-location" role="tabpanel" hidden={selected !== 1} aria-labelledby={`${tabId}-1`}>
      <header><div className={styles.stageTitle}><span className={styles.number}>2</span><h2 id="complex-location-title">주소에 맞는 좌표 적재</h2>
        <ComplexStageHelp stage="주소에 맞는 좌표 적재">위치정보요약DB 월 전체분 ZIP을 업로드해 저장된 단지 원본의 주소에 맞는 좌표를 준비합니다.</ComplexStageHelp></div></header>
      <LocationSummaryUpload embedded disabled={disabled} onBusyChange={busy => { setUploading(busy); onUploadBusy(busy) }} />
    </section>
    <section className={styles.stage} id="complex-refinement" role="tabpanel" hidden={selected !== 2} aria-labelledby={`${tabId}-2`}>
      <header><div className={styles.stageTitle}><span className={styles.number}>3</span><h2 id="complex-refinement-title">단지 정제·저장</h2>
        <ComplexStageHelp stage="단지 정제·저장">현재 저장된 단지 원본 전체를 단지·주택형으로 정제해 저장합니다.</ComplexStageHelp></div>
        <ComplexExecutionSummary execution={latest('COMPLEX_REFINEMENT')} pending={startingType === 'COMPLEX_REFINEMENT'} /></header>
      <Button data-design-system disabled={disabled} onClick={() => onStart('COMPLEX_REFINEMENT')}>단지 정제·저장 실행</Button>
      {result('COMPLEX_REFINEMENT')}
    </section>
  </div>
}
