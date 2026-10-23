import { useEffect, useRef, useState } from 'react'
import { DataPipelineApiError, isExternalDataCollectionReport, refreshLhAnnouncement, type LhRefreshSource } from './api'
import type { FailureReview } from './failureReviewApi'
import styles from './IngestFailurePanel.module.css'

export function FailureSourceRecovery({row, disabled, onProcessed, onBusy}: {
  row: FailureReview; disabled: boolean; onProcessed: () => void; onBusy: (busy: boolean) => void
}) {
  const identifier = typeof row.raw.sourceAnnouncementIdentifier === 'string' ? row.raw.sourceAnnouncementIdentifier.trim() : ''
  const [notice, setNotice] = useState('')
  const [error, setError] = useState('')
  const [running, setRunning] = useState<LhRefreshSource | null>(null)
  const pending = useRef(false)
  const alive = useRef(false)
  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [])
  if (row.category !== 'enrichment' || !identifier || row.status !== 'PENDING') return null

  async function refresh(source: LhRefreshSource) {
    if (pending.current || disabled) return
    pending.current = true; setRunning(source); onBusy(true); setNotice(''); setError('')
    try {
      const report = await refreshLhAnnouncement(source, identifier)
      if (!alive.current) return
      setNotice(report.externalApiCallCount === 0 && report.failedRequestCount === 0
        ? '재조회할 LH 요청이 없습니다. 마이홈 공고와 LH 원천 연결을 확인해 주세요.'
        : `외부 호출 ${report.externalApiCallCount}회 · 성공 ${report.successfulRequestCount}건 · 저장·갱신 ${report.storedRowCount}행. 원천 반영 후 정제가 필요합니다.`)
      onProcessed()
    } catch (cause) {
      if (!alive.current) return
      setError(cause instanceof DataPipelineApiError && isExternalDataCollectionReport(cause.serverResponse)
        ? `실패 요청 ${cause.serverResponse.failedRequestCount}건 · 저장·갱신 ${cause.serverResponse.storedRowCount}행. ${cause.message}`
        : cause instanceof Error ? cause.message : 'LH 원천을 다시 조회하지 못했습니다.')
      onProcessed()
    } finally {
      pending.current = false
      if (alive.current) { setRunning(null); onBusy(false) }
    }
  }

  return <div className={styles.recovery}>
    <p>마이홈 공고 <strong>{identifier}</strong>에 연결된 LH 요청만 재수집합니다. 기존 원천 보존·공급 감소 검증을 적용합니다.</p>
    <div className={styles.workflow}>
      {(['details', 'supplies'] as const).map(source => <button key={source} type="button" disabled={disabled || running !== null}
        onClick={() => void refresh(source)}>{running === source ? '재수집 중…' : source === 'details' ? '이 공고 LH 상세 재수집' : '이 공고 LH 공급 재수집'}</button>)}
    </div>
    {notice && <p role="status">{notice}</p>}
    {error && <p role="alert">{error}</p>}
  </div>
}
