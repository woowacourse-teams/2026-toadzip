import { useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { IngestFailurePanel } from './IngestFailurePanel'
import { getDataPipelineStatus, startDataPipeline, type DataPipelineExecution, type DataPipelineType } from './api'
import { pipelineStatusLabels } from './pipelineLabels'
import type { FailureDomain } from './failureReviewApi'
import styles from './IngestFailurePanel.module.css'
import { FailureRetryButton } from './FailureRetryButton'
import { AdminIcon } from '../shared/AdminIcon'

export function FailureReviewPage() {
  const [params, setParams] = useSearchParams()
  const legacyCategory = params.get('category')
  // 주소에 대상이 없으면 공고 문제를 연다. 운영 홈의 '미해결' 숫자가 공고 문제이고, 단지는 주소로 지정할 때만 연다.
  const domain: FailureDomain = params.get('domain') === 'complex'
    || (!params.has('domain') && legacyCategory === 'complex') ? 'complex' : 'announcement'
  const type: DataPipelineType = domain === 'complex' ? 'COMPLEX_REFINEMENT' : 'ANNOUNCEMENT_REFINEMENT'
  const label = domain === 'complex' ? '단지' : '공고'
  const retired = domain === 'complex'
  const [execution, setExecution] = useState<{ type: DataPipelineType; data: DataPipelineExecution } | null>(null)
  const current = execution?.type === type ? execution.data : null
  const [starting, setStarting] = useState(false)
  const [error, setError] = useState('')
  const [runNotice, setRunNotice] = useState('')
  // 이 화면에서 직접 시작한 정제인지. 지난 실행 결과는 알리지 않고, 방금 누른 실행이 실패로 끝났을 때만 알린다.
  const [startedHere, setStartedHere] = useState(false)
  const [refresh, setRefresh] = useState(0)
  // 조건을 걸지 않은 미해결 건수. 목록 응답에서 받으므로 지금 연 탭에만 붙는다.
  const [pendingCount, setPendingCount] = useState<number | null>(null)
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
    if (busy || retired) return
    statusGeneration.current += 1
    setStarting(true); setError(''); setRunNotice(''); setStartedHere(true)
    try {
      const data = await startDataPipeline(type)
      setExecution({type, data})
      if (data.status !== 'RUNNING') setRefresh(value => value + 1)
    } catch (cause) { setError(cause instanceof Error ? cause.message : '재처리를 시작하지 못했습니다.') }
    finally { setStarting(false) }
  }
  function selectDomain(next: FailureDomain) {
    setStartedHere(false)
    setParams(currentParams => {
      currentParams.set('domain', next)
      currentParams.delete('category'); currentParams.delete('page'); currentParams.delete('executionId')
      currentParams.delete('failure'); currentParams.delete('result')
      return currentParams
    })
  }
  // 정제가 진행 중일 때만 알린다. 끝난 실행의 결과는 목록이 바로 보여 주므로 문구로 다시 말하지 않는다.
  const runMessage = starting ? '재처리를 시작하는 중입니다.'
    : current?.status === 'RUNNING' ? `${label} 정제 ${pipelineStatusLabels[current.status]} · 끝나면 목록을 갱신합니다.` : ''
  const runFailure = startedHere && current && (current.status === 'FAILED' || current.status === 'COMPLETED_WARNINGS')
    ? `${label} 정제가 '${pipelineStatusLabels[current.status]}'로 끝났습니다. 아래 목록에서 실패 항목을 확인해 주세요.` : ''

  return <section className={`management-page ${styles.page}`}>
    <header className={styles.heading}><h1>실패·검토 항목</h1><Link to={`/admin/ingest/${domain === 'complex' ? 'complexes' : 'announcements'}`}>{label} 수집·정제 진행 상황<AdminIcon name="chevronRight" /></Link></header>
    <div className={styles.tabBar}><div className={styles.tabs} role="tablist" aria-label="문제 대상">
      {(['complex','announcement'] as const).map((value,index,values) => <button key={value} data-design-system="list-tab" type="button" role="tab"
        id={`failure-tab-${value}`} aria-selected={domain === value} aria-controls="failure-review-panel" tabIndex={domain === value ? 0 : -1}
        onClick={() => selectDomain(value)} onKeyDown={event => {
          let next: number
          if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') next = (index + 1) % values.length
          else if (event.key === 'Home') next = 0
          else if (event.key === 'End') next = 1
          else return
          event.preventDefault(); selectDomain(values[next])
          event.currentTarget.parentElement?.querySelectorAll<HTMLButtonElement>('[role="tab"]')[next]?.focus()
        }}>{value === 'complex' ? '단지 문제' : '공고 문제'}{domain === value && pendingCount !== null
          ? <> <span className={styles.tabCount}>{pendingCount.toLocaleString()}건</span></> : null}</button>)}
    </div>
      {/* 다시 실행은 고른 탭(공고 문제)에만 걸리는 동작이라 탭 줄 오른쪽에 둔다. */}
      {!retired && <FailureRetryButton key={domain} label={label} disabled={busy} onRetry={() => void retry()}/>}
    </div>
    <div id="failure-review-panel" role="tabpanel" aria-labelledby={`failure-tab-${domain}`}>
      {runMessage ? <p role="status" className={styles.runMessage}>{runMessage}</p> : null}
      {runNotice ? <p role="status" className={styles.runMessage}>{runNotice}</p> : null}
      {error || runFailure ? <div role="alert" className="admin-alert"><p>{error || runFailure}</p></div> : null}
      <div className="admin-list-card">
        <IngestFailurePanel domain={domain} executionId={params.get('executionId')} refreshToken={refresh} retryDisabled={busy} runError={error} onPendingCount={setPendingCount} />
      </div>
    </div>
  </section>
}
