import { useId, useState } from 'react'
import { Button } from '../../design-system/components/Button'
import { IconButton } from '../../design-system/components/IconButton'
import type { DataPipelineExecution, DataPipelineType } from './api'
import type { PipelineViewState } from './PipelineResult'
import { ComplexExecutionResult } from './ComplexExecutionResult'
import { LocationSummaryUpload } from './LocationSummaryUpload'
import styles from './ComplexIngest.module.css'

export function ComplexIngestStages({ states, disabled, startingType, stopping, onStart, onStop, onRefresh, onUploadBusy }: {
  states: Record<DataPipelineType, PipelineViewState>; disabled: boolean; startingType: DataPipelineType | null
  stopping: Partial<Record<DataPipelineType, boolean>>; onStart: (type: DataPipelineType, key?: string) => void
  onStop: (type: DataPipelineType) => void; onRefresh: (type: DataPipelineType) => void; onUploadBusy: (busy: boolean) => void
}) {
  const combined = states.COMPLEX_SYNC.execution
  function latest(type: 'COMPLEX_COLLECTION' | 'COMPLEX_REFINEMENT'): DataPipelineExecution {
    const own = states[type].execution
    return combined.status === 'RUNNING' || (Date.parse(combined.startedAt ?? '') || 0) > (Date.parse(own.startedAt ?? '') || 0)
      ? combined : own
  }
  function result(type: 'COMPLEX_COLLECTION' | 'COMPLEX_REFINEMENT') {
    const execution = latest(type)
    return <>
      <ComplexExecutionResult key={`${execution.executionId}-${type}`} execution={execution}
        phase={type === 'COMPLEX_COLLECTION' ? 'collection' : 'refinement'} pending={startingType === type}
        stopping={stopping[execution.type]} onStop={() => onStop(execution.type)} />
      {[type, 'COMPLEX_SYNC' as const].map(value => states[value].requestError && <div role="alert" className={styles.notice} key={value}>
        <p>{states[value].requestError}</p><button type="button" onClick={() => onRefresh(value)}>상태 다시 확인</button>
      </div>)}
    </>
  }
  return <div className={styles.stages}>
    <p className={styles.sequence}>1 원천 수집 <span aria-hidden="true">→</span> 2 좌표 적재 <span aria-hidden="true">→</span> 3 정제·저장
      <small>저장된 원본·좌표가 있다면 필요한 단계만 다시 실행할 수 있습니다.</small></p>
    <section className={styles.stage} aria-labelledby="complex-collection-title">
      <header><span className={styles.number}>1</span><h2 id="complex-collection-title">단지 원천 수집</h2></header>
      <ComplexCollectionForm disabled={disabled} onStart={key => onStart('COMPLEX_COLLECTION', key)} />
      {result('COMPLEX_COLLECTION')}
    </section>
    <section className={styles.stage} aria-labelledby="complex-location-title">
      <header><span className={styles.number}>2</span><h2 id="complex-location-title">주소에 맞는 좌표 적재</h2></header>
      <LocationSummaryUpload embedded disabled={disabled} onBusyChange={onUploadBusy} />
    </section>
    <section className={styles.stage} id="complex-refinement" aria-labelledby="complex-refinement-title">
      <header><span className={styles.number}>3</span><h2 id="complex-refinement-title">단지 정제·저장</h2></header>
      <p className={styles.muted}>현재 저장된 단지 원본 전체를 단지·주택형으로 정제해 저장합니다.</p>
      <Button data-design-system disabled={disabled} onClick={() => onStart('COMPLEX_REFINEMENT')}>단지 정제·저장 실행</Button>
      {result('COMPLEX_REFINEMENT')}
    </section>
  </div>
}

function ComplexCollectionForm({ disabled, onStart }: { disabled: boolean; onStart: (key: string) => void }) {
  const [key, setKey] = useState('')
  const [show, setShow] = useState(false)
  const [helpOpen, setHelpOpen] = useState(false)
  const [helpHovered, setHelpHovered] = useState(false)
  const helpId = useId()
  const inputId = useId()
  return <form className={styles.collectionForm} onSubmit={event => {
    event.preventDefault()
    if (disabled || !key.trim()) return
    const executionKey = key.trim()
    setKey('')
    setShow(false)
    onStart(executionKey)
  }}>
    <div className={styles.keyLabel}>
      <label htmlFor={inputId}>공공데이터포털 API 키</label>
      <div className={styles.help} onMouseEnter={() => setHelpHovered(true)} onMouseLeave={() => setHelpHovered(false)}
        onKeyDown={event => { if (event.key === 'Escape') { setHelpOpen(false); setHelpHovered(false) } }}
        onBlur={event => { if (!event.currentTarget.contains(event.relatedTarget)) setHelpOpen(false) }}>
        <IconButton data-design-system label="공공데이터포털 API 키 안내" aria-expanded={helpOpen || helpHovered} aria-controls={helpId}
          onClick={() => { setHelpHovered(false); setHelpOpen(value => !value) }}>ⓘ</IconButton>
        <div id={helpId} hidden={!helpOpen && !helpHovered} className={styles.helpContent}>
          <p>일반 인증키(Decoding·Encoding)를 사용할 수 있습니다. 입력한 키는 이번 실행에만 사용합니다.</p>
          <a href="https://www.data.go.kr/" target="_blank" rel="noreferrer">공공데이터포털에서 API 키 발급</a>
        </div>
      </div>
    </div>
    <div className={styles.keyControls}>
      <div><input id={inputId} type={show ? 'text' : 'password'} value={key} required maxLength={4096} disabled={disabled}
        autoComplete="off" spellCheck={false} onChange={event => setKey(event.target.value)} />
        <button type="button" aria-label={show ? 'API 키 숨기기' : 'API 키 보기'} onClick={() => setShow(value => !value)}>{show ? '숨기기' : '보기'}</button></div>
      <Button data-design-system type="submit" disabled={disabled || !key.trim()}>단지 원천 수집 실행</Button>
    </div>
  </form>
}
