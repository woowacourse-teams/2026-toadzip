import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { PipelineExecutionSteps } from './PipelineExecutionSteps'
import type { DataPipelineType } from './api'
import { getDataPipelineExecution, getDataPipelineStatus, startAnnouncementRegistration, startAnnouncementRegistrationUrl,
  stopDataPipeline, type DataPipelineExecution } from './api'
import styles from './CollectionStartForm.module.css'

export function MyhomeAnnouncementImport({ onSubmittingChange }: { onSubmittingChange?: (value: boolean) => void }) {
  const [pipelineBusy, setPipelineBusy] = useState(true)
  const [checkingOthers, setCheckingOthers] = useState(true)
  const [busyError, setBusyError] = useState<string | null>(null)
  const [inputType, setInputType] = useState<'url' | 'id'>('url')
  const [target, setTarget] = useState('')
  const [execution, setExecution] = useState<DataPipelineExecution | null>(null)
  const [initializing, setInitializing] = useState(true)
  const [submitting, setSubmitting] = useState(false)
  const [stopping, setStopping] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const alive = useRef(false)
  const submittingRef = useRef(false)
  const processing = submitting || execution?.status === 'RUNNING'

  useEffect(() => { onSubmittingChange?.(processing) }, [processing, onSubmittingChange])
  useEffect(() => () => { onSubmittingChange?.(false) }, [onSubmittingChange])

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
    let active = true
    let timer: ReturnType<typeof setTimeout>
    let types: DataPipelineType[] = ['COMPLEX_COLLECTION', 'COMPLEX_REFINEMENT', 'COMPLEX_SYNC',
      'ANNOUNCEMENT_COLLECTION', 'ANNOUNCEMENT_REFINEMENT', 'ANNOUNCEMENT_SYNC', 'SH_ANNOUNCEMENT_COLLECTION']
    async function checkBusy() {
      try {
        const statuses = await Promise.all(types.map(type => getDataPipelineStatus(type)))
        if (!active) return
        types = statuses.filter(status => status.status === 'RUNNING').map(status => status.type)
        setCheckingOthers(false)
        setPipelineBusy(types.length > 0)
        setBusyError(null)
      } catch {
        if (active) setBusyError('다른 수집 작업의 상태를 확인하지 못했습니다. 자동으로 다시 확인합니다.')
      }
      if (active && types.length > 0) timer = setTimeout(() => { void checkBusy() }, 1500)
    }
    void checkBusy()
    return () => { active = false; clearTimeout(timer) }
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
    if (!target.trim()) {
      setError(inputType === 'url' ? '마이홈 공고 URL을 입력해 주세요.' : '마이홈 공고 ID를 입력해 주세요.')
      return
    }
    await runRegistration(() => inputType === 'url'
      ? startAnnouncementRegistrationUrl(target.trim())
      : startAnnouncementRegistration(target.trim()))
  }

  async function runRegistration(start: () => Promise<DataPipelineExecution>) {
    if (submittingRef.current || processing || initializing || pipelineBusy) return
    submittingRef.current = true
    setSubmitting(true)
    setExecution(null)
    setError(null)
    try {
      const accepted = await start()
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

  const disabled = initializing || processing || pipelineBusy
  return <section className="registration-card">
    <h2>마이홈에서 공고 가져오기</h2>
    <p>마이홈 공고 한 건의 원천을 가져와 정제·등록합니다. 공급기관이 LH인 공고는 LH 정보도 보강합니다.</p>
    <p><Link to="/admin/ingest/announcements">여러 공고를 갱신하려면 공고 수집으로 이동</Link></p>
    {busyError && <p role="alert" className="form-error">{busyError}</p>}
    {pipelineBusy && !checkingOthers && !busyError && <p role="status">다른 수집·정제 작업이 실행 중입니다. 완료 후 등록할 수 있습니다.</p>}
    <form onSubmit={event => { void register(event) }} className={styles.form} aria-label="공고 단건 등록">
      <label htmlFor="registration-input-type">입력 방식</label>
      <select id="registration-input-type" value={inputType} disabled={disabled} onChange={event => {
        setInputType(event.target.value === 'id' ? 'id' : 'url')
        setTarget('')
        setError(null)
      }}>
        <option value="url">마이홈 URL</option>
        <option value="id">공고 ID (pblancId)</option>
      </select>
      <label htmlFor="registration-target">
        {inputType === 'url' ? '마이홈 공고 URL' : '마이홈 공고 ID (pblancId)'}
      </label>
      <input id="registration-target" value={target} disabled={disabled}
        maxLength={inputType === 'url' ? 2048 : 100} autoComplete="off"
        inputMode={inputType === 'url' ? 'url' : 'numeric'} onChange={event => setTarget(event.target.value)}
        placeholder={inputType === 'url'
          ? 'https://www.myhome.go.kr/hws/portal/sch/selectRsdtRcritNtcDetailView.do?pblancId=…'
          : '예: 21026'} />
      <button type="submit" className="admin-primary" disabled={disabled}>
        {processing ? '등록 처리 중…' : '등록 실행'}
      </button>
    </form>
    {error && <p role="alert" className="form-error">{error}</p>}
    {submitting && <p role="status">공고 등록 요청 중…</p>}
    {execution && <PipelineExecutionSteps execution={execution} stopping={stopping} onStop={() => { void stop() }} />}
  </section>
}

function errorMessage(cause: unknown): string {
  return cause instanceof Error ? cause.message : '요청을 처리하지 못했습니다.'
}
