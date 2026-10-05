import { useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { IngestFailurePanel } from './IngestFailurePanel'
import { getDataPipelineStatus, startDataPipeline, type DataPipelineExecution, type DataPipelineType } from './api'
import { pipelineStatusLabels } from './pipelineLabels'
import type { FailureDomain } from './failureReviewApi'
import styles from './IngestFailurePanel.module.css'

export function FailureReviewPage() {
  const [params, setParams] = useSearchParams()
  const legacyCategory = params.get('category')
  const domain: FailureDomain = params.get('domain') === 'announcement'
    || (!params.has('domain') && (legacyCategory === 'announcement' || legacyCategory === 'enrichment')) ? 'announcement' : 'complex'
  const type: DataPipelineType = domain === 'complex' ? 'COMPLEX_REFINEMENT' : 'ANNOUNCEMENT_REFINEMENT'
  const label = domain === 'complex' ? '단지' : '공고'
  const [execution, setExecution] = useState<{ type: DataPipelineType; data: DataPipelineExecution } | null>(null)
  const current = execution?.type === type ? execution.data : null
  const [starting, setStarting] = useState(false)
  const [error, setError] = useState('')
  const [runNotice, setRunNotice] = useState('')
  const [refresh, setRefresh] = useState(0)
  const statusGeneration = useRef(0)
  const busy = starting || execution?.data.status === 'RUNNING'

  useEffect(() => {
    let active = true
    const generation = ++statusGeneration.current
    void getDataPipelineStatus(type).then(data => {
      if (active && generation === statusGeneration.current) setExecution(previous => previous?.type === type && previous.data.status === 'RUNNING'
        ? previous : {type, data})
    })
      .catch(cause => { if (active && generation === statusGeneration.current) setError(cause instanceof Error ? cause.message : '재처리 상태를 확인하지 못했습니다.') })
    return () => { active = false }
  }, [type])

  useEffect(() => {
    if (execution?.data.status !== 'RUNNING' || !execution.data.executionId) return
    let active = true
    let timer: ReturnType<typeof setTimeout>
    const id = execution.data.executionId
    const pollingType = execution.type
    async function poll() {
      try {
        const data = await getDataPipelineStatus(pollingType)
        if (!active) return
        if (data.executionId !== id) {
          setRunNotice('실행 기록이 바뀌어 최신 정제 상태를 표시합니다.')
          setExecution(previous => previous?.data.executionId === id ? {type:previous.type, data} : previous)
          setError('')
          setRefresh(value => value + 1)
          return
        }
        setExecution(previous => previous?.data.executionId === id ? {type:previous.type, data} : previous)
        setError('')
        if (data.status !== 'RUNNING') { setRefresh(value => value + 1); return }
      } catch (cause) {
        if (!active) return
        setError(cause instanceof Error ? cause.message : '재처리 상태를 확인하지 못했습니다.')
      }
      if (active) timer = setTimeout(() => void poll(), 1000)
    }
    timer = setTimeout(() => void poll(), 1000)
    return () => { active = false; clearTimeout(timer) }
  }, [execution?.data.executionId, execution?.data.status, execution?.type])

  async function retry() {
    if (busy) return
    statusGeneration.current += 1
    setStarting(true); setError(''); setRunNotice('')
    try {
      const data = await startDataPipeline(type)
      setExecution({type, data})
      if (data.status !== 'RUNNING') setRefresh(value => value + 1)
    } catch (cause) { setError(cause instanceof Error ? cause.message : '재처리를 시작하지 못했습니다.') }
    finally { setStarting(false) }
  }
  function selectDomain(next: FailureDomain) {
    setParams(currentParams => {
      currentParams.set('domain', next)
      currentParams.delete('category'); currentParams.delete('page'); currentParams.delete('executionId')
      return currentParams
    })
  }
  const runMessage = starting ? '재처리를 시작하는 중입니다.'
    : current && current.status !== 'IDLE' ? `${label} 정제: ${pipelineStatusLabels[current.status]} · 처리 결과에 따라 오류 목록을 갱신합니다.` : ''

  return <section className={`management-page ${styles.page}`}>
    <header className="management-heading"><div><h1>오류 확인·처리</h1><p>문제 원인과 현재 서비스 데이터를 함께 확인합니다.</p></div><Link to="/admin/ingest">수집·정제 진행 상황</Link></header>
    <div className={styles.tabs} role="tablist" aria-label="문제 대상">
      {(['complex','announcement'] as const).map((value,index,values) => <button key={value} type="button" role="tab"
        id={`failure-tab-${value}`} aria-selected={domain === value} aria-controls="failure-review-panel" tabIndex={domain === value ? 0 : -1}
        onClick={() => selectDomain(value)} onKeyDown={event => {
          let next: number
          if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') next = (index + 1) % values.length
          else if (event.key === 'Home') next = 0
          else if (event.key === 'End') next = 1
          else return
          event.preventDefault(); selectDomain(values[next])
          event.currentTarget.parentElement?.querySelectorAll<HTMLButtonElement>('[role="tab"]')[next]?.focus()
        }}>{value === 'complex' ? '단지 문제' : '공고 문제'}</button>)}
    </div>
    <div id="failure-review-panel" role="tabpanel" aria-labelledby={`failure-tab-${domain}`}>
      <div className={styles.workflow}>
        <Link to={`/admin/${domain === 'complex' ? 'complexes' : 'announcements'}?review=true`}>원천 변경 검토</Link>
        <Link to={`/admin/ingest#${domain === 'complex' ? 'complex' : 'announcement'}-pipelines`}>API 키 입력·원천 수집</Link>
        <button type="button" disabled={busy} onClick={() => void retry()}>{label} 정제 다시 실행</button>
      </div>
      <p className={styles.hint}>{label} 전체를 다시 정제합니다. 문제가 사라지면 해결됨으로, 재발하면 미해결로 표시됩니다.</p>
      {runMessage ? <p role="status" className={styles.runMessage}>{runMessage}</p> : null}
      {runNotice ? <p role="status">{runNotice}</p> : null}
      {error ? <p role="alert">{error}</p> : null}
      <IngestFailurePanel domain={domain} executionId={params.get('executionId')} refreshToken={refresh}
        onRetry={() => void retry()} retryDisabled={busy} runMessage={runMessage} runError={error} />
    </div>
  </section>
}
