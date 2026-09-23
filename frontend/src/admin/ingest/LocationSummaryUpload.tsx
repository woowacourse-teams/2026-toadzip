import { useState, type ChangeEvent, type FormEvent } from 'react'
import {
  uploadLocationSummary,
  type LocationSummaryImportReport,
} from './api'

export function LocationSummaryUpload() {
  const [file, setFile] = useState<File | null>(null)
  const [report, setReport] = useState<LocationSummaryImportReport | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [isUploading, setIsUploading] = useState(false)

  function handleFileChange(event: ChangeEvent<HTMLInputElement>) {
    setFile(event.target.files?.[0] ?? null)
    setReport(null)
    setError(null)
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (file === null || isUploading) {
      return
    }
    void submit(file)
  }

  async function submit(selectedFile: File) {
    setError(null)
    setReport(null)
    setIsUploading(true)
    try {
      setReport(await uploadLocationSummary(selectedFile))
    } catch (requestError) {
      setError(errorMessage(requestError))
    } finally {
      setIsUploading(false)
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
            disabled={isUploading}
            onChange={handleFileChange}
            type="file"
          />
        </label>
        <button
          className="registration-submit"
          disabled={file === null || isUploading}
          type="submit"
        >
          {isUploading ? '업로드 및 선별 적재 중…' : '좌표 데이터 업로드'}
        </button>
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

function errorMessage(error: unknown): string {
  if (error instanceof Error) {
    return error.message
  }
  return '위치정보요약DB 업로드를 처리하지 못했습니다.'
}
