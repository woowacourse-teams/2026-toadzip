import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Button } from '../../design-system/components/Button'
import { uploadLocationSummary, type LocationSummaryImportReport } from './api'
import styles from './ComplexIngest.module.css'
import { ComplexStatusBadge } from './ComplexStatusBadge'
import { LocationPreparation } from './LocationPreparation'

export function LocationSummaryUpload({ embedded = false, disabled = false, onBusyChange, revision = '', onChanged }: {
  embedded?: boolean; disabled?: boolean; onBusyChange?: (busy: boolean) => void; revision?: string; onChanged?: () => void
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

  return <div className={styles.uploadWorkspace}>
    {!embedded && <h2>주소·좌표 준비</h2>}
    <section aria-label="좌표 ZIP 업로드" className={styles.uploadCard}>
    <header className={styles.uploadHeading}><h3>좌표 자료 업로드</h3>
      <p>월 전체분 ZIP을 올리면 원천 주소와 일치하는 좌표를 준비합니다.</p></header>
    <div className={styles.uploadInput}><form className={styles.uploadForm} onSubmit={event => void submit(event)}>
      <label className="location-summary-file">위치정보요약DB 월 전체분 ZIP
        <input accept=".zip,application/zip" type="file" disabled={disabled || uploading}
          onChange={event => { setFile(event.target.files?.[0] ?? null); setReport(null); setError(null) }} />
      </label>
      <Button data-design-system type="submit" disabled={!file || disabled || uploading}>
        {uploading ? '좌표 파일 처리 중…' : '좌표 데이터 업로드'}
      </Button>
    </form></div>
    {uploading && <div className={styles.runNotice} role="status"><ComplexStatusBadge status="running" /><p>좌표 파일을 처리하고 있습니다. 완료될 때까지 이 페이지를 닫지 마세요.</p></div>}
    {error && <div className={styles.uploadFailure} role="alert"><ComplexStatusBadge status="failed" /><p>{error}</p></div>}
    {report && <section className={styles.uploadResult} aria-label="좌표 적재 결과"><div role="status">
      <header><h4>좌표 적재 결과</h4><ComplexStatusBadge status="completed" /></header>
      <dl className={styles.metrics}>
        <ResultItem label="원천의 고유 주소" value={report.targetRoadAddressCount} />
        <ResultItem label="ZIP 주소 일치" value={report.matchedRoadAddressCount} />
        <ResultItem label="ZIP 좌표 준비 완료" value={report.preparedRoadAddressCount} />
        <ResultItem label="ZIP 주소 불일치" value={report.unmatchedRoadAddressCount} />
      </dl>
    </div></section>}
    {report && report.unmatchedRoadAddressCount > 0 && <aside className={styles.nextAction} aria-label="다음 작업 안내"><h4>다음 작업</h4><p>좌표를 찾지 못한 주소가 {report.unmatchedRoadAddressCount.toLocaleString('ko-KR')}개 있습니다.{' '}
        아래 보완 목록에서 주소 검색 또는 직접 입력으로 주소·좌표를 준비하세요.</p></aside>}
    </section>
    <LocationPreparation disabled={disabled || uploading} revision={`${revision}-${uploading}`}
      onBusyChange={onBusyChange} onChanged={onChanged} />
  </div>
}

function ResultItem({ label, value }: { label: string; value: number }) {
  return <div><dt>{label}</dt><dd>{value.toLocaleString('ko-KR')}개</dd></div>
}
