import { useEffect, useRef, useState, type ChangeEvent, type FormEvent } from 'react'
import { Link } from 'react-router'
import { Button } from '../../design-system/components/Button'
import {
  getDataPipelineExecution,
  getDataPipelineStatus,
  startDataPipeline,
  stopDataPipeline,
  uploadLocationSummary,
  type DataPipelineExecution,
  type LocationSummaryImportReport,
} from './api'
import { PipelineExecutionSteps } from './PipelineExecutionSteps'

export function LocationSummaryUpload() {
  const [file, setFile] = useState<File | null>(null)
  const [report, setReport] = useState<LocationSummaryImportReport | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [isUploading, setIsUploading] = useState(false)
  const [execution, setExecution] = useState<DataPipelineExecution | null>(null)
  const [refinementError, setRefinementError] = useState<string | null>(null)
  const [checkingStatus, setCheckingStatus] = useState(true)
  const [statusUnknown, setStatusUnknown] = useState(false)
  const [starting, setStarting] = useState(false)
  const [stopping, setStopping] = useState(false)
  const mounted = useRef(false)
  const requestPending = useRef(false)
  const generation = useRef(0)
  const startRecovery = useRef<{ previousExecutionId: string | null; message: string } | null>(null)
  const busy = isUploading || starting || checkingStatus || statusUnknown || execution?.status === 'RUNNING'

  useEffect(() => {
    mounted.current = true
    void checkStatus()
    return () => { mounted.current = false; generation.current += 1 }
  }, [])

  useEffect(() => {
    if (execution?.status !== 'RUNNING' || !execution.executionId) return
    const executionId = execution.executionId
    let active = true
    let timer: ReturnType<typeof setTimeout>
    async function poll() {
      if (requestPending.current) {
        timer = setTimeout(() => void poll(), 1000)
        return
      }
      const currentGeneration = generation.current
      try {
        const data = await getDataPipelineExecution(executionId)
        if (!active) return
        if (currentGeneration === generation.current) {
          setExecution(data)
          setRefinementError(null)
        }
        if (currentGeneration === generation.current && data.status !== 'RUNNING') return
      } catch (cause) {
        if (!active) return
        if (currentGeneration === generation.current) {
          setRefinementError(`${errorMessage(cause, '단지 정제 상태를 확인하지 못했습니다.')} 상태를 다시 확인하고 있습니다.`)
        }
      }
      if (active) timer = setTimeout(() => void poll(), 1000)
    }
    timer = setTimeout(() => void poll(), 1000)
    return () => { active = false; clearTimeout(timer) }
  }, [execution?.executionId, execution?.status])

  async function checkStatus() {
    const currentGeneration = ++generation.current
    setCheckingStatus(true)
    try {
      const data = await getDataPipelineStatus('COMPLEX_REFINEMENT')
      if (!mounted.current || currentGeneration !== generation.current) return
      const recovery = startRecovery.current
      const recovered = recovery !== null && data.executionId !== null
        && data.executionId !== recovery.previousExecutionId
      setExecution(data.status === 'RUNNING' || recovered ? data : null)
      setStatusUnknown(false)
      setRefinementError(recovery && !recovered && data.status !== 'RUNNING' ? recovery.message : null)
      startRecovery.current = null
      return data
    } catch (cause) {
      if (!mounted.current || currentGeneration !== generation.current) return
      setStatusUnknown(true)
      setRefinementError(`${errorMessage(cause, '단지 정제 상태를 확인하지 못했습니다.')} 실행 여부를 확인한 뒤 다시 진행해 주세요.`)
    } finally {
      if (mounted.current && currentGeneration === generation.current) setCheckingStatus(false)
    }
  }

  async function refine() {
    if (!report || busy || requestPending.current) return
    requestPending.current = true
    generation.current += 1
    setStarting(true)
    setRefinementError(null)
    setExecution(null)
    startRecovery.current = null
    try {
      const previous = await checkStatus()
      if (!previous || !mounted.current || previous.status === 'RUNNING') return
      try {
        const data = await startDataPipeline('COMPLEX_REFINEMENT')
        if (mounted.current) setExecution(data)
      } catch (cause) {
        if (!mounted.current) return
        const message = errorMessage(cause, '단지 정제를 시작하지 못했습니다.')
        startRecovery.current = { previousExecutionId: previous.executionId, message }
        setRefinementError(message)
        await checkStatus()
      }
    } finally {
      requestPending.current = false
      if (mounted.current) setStarting(false)
    }
  }

  async function stop() {
    if (execution?.status !== 'RUNNING' || !execution.executionId || stopping || requestPending.current) return
    requestPending.current = true
    generation.current += 1
    setStopping(true)
    setRefinementError(null)
    try {
      const data = await stopDataPipeline(execution.executionId)
      if (mounted.current) setExecution(data)
    } catch (cause) {
      if (mounted.current) setRefinementError(errorMessage(cause, '단지 정제 중지를 요청하지 못했습니다.'))
    } finally {
      requestPending.current = false
      if (mounted.current) setStopping(false)
    }
  }

  function handleFileChange(event: ChangeEvent<HTMLInputElement>) {
    setFile(event.target.files?.[0] ?? null)
    setReport(null)
    setError(null)
    setExecution(null)
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (file === null || busy || requestPending.current) {
      return
    }
    void submit(file)
  }

  async function submit(selectedFile: File) {
    setError(null)
    setReport(null)
    setExecution(null)
    setIsUploading(true)
    requestPending.current = true
    try {
      const result = await uploadLocationSummary(selectedFile)
      if (mounted.current) setReport(result)
    } catch (requestError) {
      if (mounted.current) setError(errorMessage(requestError, '위치정보요약DB 업로드를 처리하지 못했습니다.'))
    } finally {
      requestPending.current = false
      if (mounted.current) setIsUploading(false)
    }
  }

  return (
    <section
      aria-labelledby="location-summary-upload-title"
      className="registration-card location-summary-card"
    >
      <div>
        <h2 id="location-summary-upload-title">위치정보요약DB 업로드</h2>
        <p>
          단지 수집을 완료한 뒤 월 전체분 ZIP을 올려 주세요.
          단지 주소와 일치하는 좌표만 DB에 저장됩니다.
        </p>
      </div>
      <form className="location-summary-form" onSubmit={handleSubmit}>
        <label className="location-summary-file">
          월 전체분 ZIP
          <input
            accept=".zip,application/zip"
            disabled={busy}
            onChange={handleFileChange}
            type="file"
          />
        </label>
        <Button
          data-design-system
          disabled={file === null || busy}
          type="submit"
        >
          {isUploading ? '업로드 및 선별 적재 중…' : '좌표 데이터 업로드'}
        </Button>
      </form>
      {isUploading ? (
        <p className="location-summary-progress" role="status">
          전국 데이터를 확인하고 있습니다. 완료될 때까지 이 페이지를 닫지 마세요.
        </p>
      ) : null}
      {error ? (
        <p className="registration-message registration-error" role="alert">{error}</p>
      ) : null}
      {report ? <ImportResult report={report} /> : null}
      {report ? <div>
        <h3>다음 작업: 단지 정제</h3>
        <p>현재 저장된 전체 단지 원천을 정제해 서비스에 반영합니다. 이번 ZIP에서 좌표를 찾은 단지만 처리하는 작업은 아닙니다.</p>
        <Button data-design-system disabled={busy} onClick={() => void refine()}>
          {starting ? '단지 정제 시작 중…' : '저장된 원천으로 단지 정제 실행'}
        </Button>
      </div> : null}
      {checkingStatus && <p role="status">단지 정제 실행 상태를 확인하고 있습니다.</p>}
      {refinementError && <p className="registration-message registration-error" role="alert">{refinementError}</p>}
      {statusUnknown && <button type="button" disabled={checkingStatus} onClick={() => void checkStatus()}>단지 정제 상태 다시 확인</button>}
      {execution && execution.status !== 'IDLE'
        ? <PipelineExecutionSteps execution={execution} onStop={() => void stop()} stopping={stopping} /> : null}
      <p><Link to="/admin/ingest-v2?domain=complex">단지 준비 화면으로 돌아가기</Link></p>
    </section>
  )
}

function ImportResult({ report }: { report: LocationSummaryImportReport }) {
  return (
    <div className="location-summary-result" role="status">
      <strong>{report.sourceFileName} 적재를 완료했습니다.</strong>
      <dl>
        <ResultItem label="확인한 원천 행" value={report.scannedRowCount} />
        <ResultItem label="대상 단지 주소" value={report.targetRoadAddressCount} />
        <ResultItem label="일치한 주소" value={report.matchedRoadAddressCount} />
        <ResultItem label="일치하지 않은 주소" value={report.unmatchedRoadAddressCount} />
        <ResultItem label="저장한 좌표 행" value={report.storedLocationCount} />
      </dl>
      {report.unmatchedRoadAddressCount > 0 ? (
        <p>일치하지 않은 주소는 단지 정제 결과에서 실패 사유를 확인해 주세요.</p>
      ) : null}
    </div>
  )
}

function ResultItem({ label, value }: { label: string, value: number }) {
  return (
    <div>
      <dt>{label}</dt>
      <dd>{value.toLocaleString('ko-KR')}건</dd>
    </div>
  )
}

function errorMessage(error: unknown, fallback: string): string {
  if (error instanceof Error) {
    return error.message
  }
  return fallback
}
