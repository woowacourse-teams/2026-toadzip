import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router'
import { IngestFailurePanel } from './IngestFailurePanel'
import { DataPipelineApiError, isExternalDataCollectionReport, refreshLhAnnouncement, type LhRefreshSource, getDataPipelineStatus, startDataPipeline, type DataPipelineExecution, type DataPipelineType } from './api'
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
  const [pblancId, setPblancId] = useState('')
  const [source, setSource] = useState<LhRefreshSource>('supplies')
  const [refreshing, setRefreshing] = useState(false)
  const statusGeneration = useRef(0)
  const busy = refreshing || starting || execution?.data.status === 'RUNNING'

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
  async function refreshAnnouncement(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (busy || !pblancId.trim()) return
    setRefreshing(true); setError(''); setRunNotice('')
    try {
      const report = await refreshLhAnnouncement(source, pblancId.trim())
      setRunNotice(report.externalApiCallCount === 0 && report.failedRequestCount === 0
        ? '재조회할 LH 요청이 없습니다. 마이홈 공고 ID와 LH 원천 연결을 확인해 주세요.'
        : `외부 호출 ${report.externalApiCallCount}회 · 성공 요청 ${report.successfulRequestCount}건 · 저장·갱신 ${report.storedRowCount}행. 원천 반영 후 공고 정제를 실행해 주세요.`)
    } catch (cause) {
      if (cause instanceof DataPipelineApiError && isExternalDataCollectionReport(cause.serverResponse)) {
        const report = cause.serverResponse
        setError(`외부 호출 ${report.externalApiCallCount}회 · 성공 요청 ${report.successfulRequestCount}건 · 실패 요청 ${report.failedRequestCount}건 · 저장·갱신 ${report.storedRowCount}행. 실패 목록에서 원인과 기존 원천 보존 여부를 확인해 주세요.`)
      } else {
        setError(cause instanceof Error ? cause.message : 'LH 원천을 재조회하지 못했습니다.')
      }
    } finally {
      setRefreshing(false)
      setRefresh(value => value + 1)
    }
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
      {domain === 'announcement' ? (
        <section className="admin-detail-section">
          <h2>공고별 LH 원천 강제 재조회</h2>
          <p>마이홈 공고 ID에 연결된 LH 요청을 조회합니다. 오래된 종료 공고 제외를 우회하며, 빈 응답 보호와 공급행 감소 승인 규칙은 유지합니다.</p>
          <form onSubmit={(event) => void refreshAnnouncement(event)}>
            <label>마이홈 공고 ID<input required value={pblancId} disabled={busy} onChange={(event) => setPblancId(event.target.value)} /></label>
            <label>재조회 원천<select value={source} disabled={busy} onChange={(event) => setSource(event.target.value as LhRefreshSource)}>
              <option value="supplies">LH 공급</option><option value="details">LH 상세</option>
            </select></label>
            <button disabled={busy || !pblancId.trim()} type="submit">{refreshing ? '처리 중…' : '공고 원천 강제 재조회'}</button>
          </form>
          <Link to="/admin/ingest">공급 감소 승인·수집 결과 확인</Link>
        </section>
      ) : null}
      <IngestFailurePanel domain={domain} executionId={params.get('executionId')} refreshToken={refresh}
        onRetry={() => void retry()} retryDisabled={busy} runMessage={runMessage} runError={error} />
    </div>
  </section>
}
