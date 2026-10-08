import { useEffect, useRef, useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router'
import { DataPipelineControl } from './DataPipelineControl'
import { PipelineHistory } from './PipelineHistory'
import workspaceStyles from './IngestWorkspace.module.css'
import { getDataPipelineExecution, getDataPipelineStatus, startAnnouncementRegistrationUrl,
  stopDataPipeline, type DataPipelineExecution } from './api'
import styles from './CollectionStartForm.module.css'

export function AnnouncementRegistrationV2Page() {
  const [params, setParams] = useSearchParams()
  const domain = params.get('domain') === 'complex' ? 'complex' : 'announcement'
  const [pipelineBusy, setPipelineBusy] = useState(false)
  const [formOpen, setFormOpen] = useState(false)
  const [url, setUrl] = useState('')
  const [execution, setExecution] = useState<DataPipelineExecution | null>(null)
  const [initializing, setInitializing] = useState(true)
  const [submitting, setSubmitting] = useState(false)
  const [stopping, setStopping] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const alive = useRef(false)
  const submittingRef = useRef(false)
  const processing = submitting || execution?.status === 'RUNNING'

  useEffect(() => {
    alive.current = true
    let active = true
    getDataPipelineStatus('ANNOUNCEMENT_REGISTRATION').then(latest => {
      if (active && latest.status === 'RUNNING') setExecution(latest)
    }).catch((cause: unknown) => {
      if (active) setError(errorMessage(cause))
    }).finally(() => {
      if (active) setInitializing(false)
    })
    return () => { active = false; alive.current = false }
  }, [])

  useEffect(() => {
    if (execution?.status !== 'RUNNING' || !execution.executionId) return
    const executionId = execution.executionId
    let active = true
    let timer: ReturnType<typeof setTimeout>
    async function poll() {
      try {
        const updated = await getDataPipelineExecution(executionId)
        if (!active) return
        setExecution(previous => ({
          ...updated, stopRequested: updated.status === 'RUNNING'
            && (updated.stopRequested || previous?.stopRequested),
        }))
        setError(null)
        if (updated.status !== 'RUNNING') return
      } catch (cause) {
        if (!active) return
        setError(`상태 조회 실패: ${errorMessage(cause)} 자동으로 다시 확인합니다.`)
      }
      if (active) timer = setTimeout(() => { void poll() }, 1500)
    }
    timer = setTimeout(() => { void poll() }, 1500)
    return () => { active = false; clearTimeout(timer) }
  }, [execution?.executionId, execution?.status])

  async function register(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (submittingRef.current || processing || initializing || pipelineBusy) return
    if (!url.trim()) { setError('마이홈 공고 URL을 입력해 주세요.'); return }
    submittingRef.current = true
    setSubmitting(true)
    setExecution(null)
    setError(null)
    try {
      const accepted = await startAnnouncementRegistrationUrl(url.trim())
      if (alive.current) setExecution(accepted)
    } catch (cause) {
      if (alive.current) setError(errorMessage(cause))
    } finally {
      submittingRef.current = false
      if (alive.current) setSubmitting(false)
    }
  }

  async function stop() {
    if (!execution?.executionId || stopping) return
    setStopping(true)
    try {
      const updated = await stopDataPipeline(execution.executionId)
      if (alive.current) setExecution(updated)
    } catch (cause) {
      if (alive.current) setError(errorMessage(cause))
    } finally {
      if (alive.current) setStopping(false)
    }
  }

  function select(next: 'complex' | 'announcement') {
    setParams(previous => {
      const updated = new URLSearchParams(previous)
      updated.set('domain', next)
      updated.delete('workspacePage')
      return updated
    })
  }
  const disabled = initializing || processing || pipelineBusy
  return <section className="admin-registration-page">
    <header className="admin-registration-heading"><h1>수집·정제 v2</h1></header>
    <div role="tablist" aria-label="수집 대상" className={workspaceStyles.tabs}>
      {(['announcement', 'complex'] as const).map(value => <button key={value} type="button" role="tab"
        id={`ingest-tab-${value}`} aria-selected={domain === value} aria-controls="ingest-domain-panel"
        tabIndex={domain === value ? 0 : -1} onClick={() => select(value)} onKeyDown={event => {
          if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
          event.preventDefault()
          const next = event.key === 'Home' ? 'announcement' : event.key === 'End' ? 'complex'
            : value === 'complex' ? 'announcement' : 'complex'
          select(next)
          document.getElementById(`ingest-tab-${next}`)?.focus()
        }}>{value === 'announcement' ? '공고' : '단지'}</button>)}
    </div>
    <section id="ingest-domain-panel" role="tabpanel" aria-labelledby={`ingest-tab-${domain}`}>
      <DataPipelineControl domain={domain} compact externalBusy={processing || initializing}
        onBusyChange={setPipelineBusy} onExecutionStart={() => {
          setExecution(null); setError(null); setFormOpen(false)
        }} registration={{
          action: <button type="button" disabled={disabled} aria-expanded={formOpen}
            aria-controls="registration-form" onClick={() => setFormOpen(value => !value)}>공고 등록하기</button>,
          form: <>
            {formOpen && <form id="registration-form" onSubmit={event => { void register(event) }} className={styles.form} aria-label="공고 단건 등록">
              <label htmlFor="registration-url">마이홈 공고 URL</label>
              <input id="registration-url" value={url} disabled={disabled} maxLength={2048}
                autoComplete="off" inputMode="url" onChange={event => setUrl(event.target.value)}
                placeholder="https://www.myhome.go.kr/hws/portal/sch/selectRsdtRcritNtcDetailView.do?pblancId=…" />
              <button type="submit" className="admin-primary" disabled={disabled}>
                {processing ? '등록 처리 중…' : '등록 실행'}
              </button>
            </form>}
            {error && <p role="alert" className="form-error">{error}</p>}
            {submitting && <p role="status">공고 등록 요청 중…</p>}
          </>,
          execution, stopping, onStop: () => { void stop() },
        }} />
      <PipelineHistory key={domain} domain={domain} compact />
    </section>
  </section>
}

function errorMessage(cause: unknown): string {
  return cause instanceof Error ? cause.message : '요청을 처리하지 못했습니다.'
}
