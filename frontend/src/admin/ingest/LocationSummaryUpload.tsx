import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { Button } from '../../design-system/components/Button'
import { uploadLocationSummary, type LocationSummaryImportReport } from './api'
import styles from './ComplexIngest.module.css'

export function LocationSummaryUpload({ embedded = false, disabled = false, onBusyChange }: {
  embedded?: boolean; disabled?: boolean; onBusyChange?: (busy: boolean) => void
}) {
  const [file, setFile] = useState<File | null>(null)
  const [report, setReport] = useState<LocationSummaryImportReport | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [uploading, setUploading] = useState(false)
  const pending = useRef(false)
  const mounted = useRef(false)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  useEffect(() => {
    if (!uploading) return
    const preventClose = (event: BeforeUnloadEvent) => { event.preventDefault() }
    window.addEventListener('beforeunload', preventClose)
    return () => window.removeEventListener('beforeunload', preventClose)
  }, [uploading])

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!file || disabled || pending.current) return
    pending.current = true
    setUploading(true)
    onBusyChange?.(true)
    setError(null)
    setReport(null)
    try {
      const result = await uploadLocationSummary(file)
      if (mounted.current) setReport(result)
    } catch (cause) {
      if (mounted.current) setError(cause instanceof Error ? cause.message : '좌표 파일을 처리하지 못했습니다.')
    } finally {
      pending.current = false
      if (mounted.current) { setUploading(false); onBusyChange?.(false) }
    }
  }

  return <section aria-label="좌표 ZIP 업로드" className={embedded ? undefined : 'registration-card location-summary-card'}>
    {!embedded && <h2>주소에 맞는 좌표 적재</h2>}
    <form className={embedded ? styles.uploadForm : 'location-summary-form'} onSubmit={event => void submit(event)}>
      <label className="location-summary-file">위치정보요약DB 월 전체분 ZIP
        <input accept=".zip,application/zip" type="file" disabled={disabled || uploading}
          onChange={event => { setFile(event.target.files?.[0] ?? null); setReport(null); setError(null) }} />
      </label>
      <Button data-design-system type="submit" disabled={!file || disabled || uploading}>
        {uploading ? '좌표 파일 처리 중…' : '좌표 데이터 업로드'}
      </Button>
    </form>
    {uploading && <p className={styles.notice} role="status">좌표 파일을 처리하고 있습니다. 완료될 때까지 이 페이지를 닫지 마세요.</p>}
    {error && <p className="form-error" role="alert">{error}</p>}
    {report && <div className={styles.uploadResult} role="status">
      <strong>좌표 적재 완료</strong>
      <dl className={styles.metrics}>
        <ResultItem label="좌표를 찾을 주소" value={report.targetRoadAddressCount} />
        <ResultItem label="좌표를 찾은 주소" value={report.matchedRoadAddressCount} />
        <ResultItem label="좌표를 찾지 못한 주소" value={report.unmatchedRoadAddressCount} />
      </dl>
      {report.unmatchedRoadAddressCount > 0 && <p>좌표를 찾지 못한 주소가 {report.unmatchedRoadAddressCount.toLocaleString('ko-KR')}개 있습니다.{' '}
        {embedded ? <Link to="#complex-refinement">3단계에서 단지 정제·저장</Link> : <Link to="/admin/ingest/complexes#complex-refinement">단지 수집 화면의 3단계에서 단지 정제·저장</Link>}
        을 실행한 뒤, 결과의 ‘좌표 문제’에서 대상과 사유를 확인하세요.</p>}
    </div>}
    {!embedded && <p><Link to="/admin/ingest/complexes#complex-refinement">단지 수집 3단계에서 단지 정제·저장</Link></p>}
  </section>
}

function ResultItem({ label, value }: { label: string; value: number }) {
  return <div><dt>{label}</dt><dd>{value.toLocaleString('ko-KR')}개</dd></div>
}
