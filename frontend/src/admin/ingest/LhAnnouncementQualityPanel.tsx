import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import {
  applyVerifiedLhSupplyReplacement,
  getLhAnnouncementQuality,
  type DataPipelineExecution,
  type LhAnnouncementQuality,
} from './api'

export function LhAnnouncementQualityPanel({ collectionExecution }: {
  collectionExecution: DataPipelineExecution
}) {
  const [quality, setQuality] = useState<LhAnnouncementQuality | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [selectedRequest, setSelectedRequest] = useState<{
    requestDescription: string
    proposedFingerprint: string | null
  } | null>(null)
  const [pblancId, setPblancId] = useState('')
  const [evidenceUrl, setEvidenceUrl] = useState('')
  const [reason, setReason] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const [applying, setApplying] = useState(false)
  const [result, setResult] = useState<string | null>(null)
  const reloadVersion = useRef(0)

  useEffect(() => {
    void reload()
    return () => { reloadVersion.current += 1 }
  }, [collectionExecution.executionId, collectionExecution.status])

  async function reload() {
    const version = ++reloadVersion.current
    setLoading(true)
    try {
      const nextQuality = await getLhAnnouncementQuality()
      if (version !== reloadVersion.current) return
      setQuality(nextQuality)
      setError(null)
    } catch (cause) {
      if (version !== reloadVersion.current) return
      setError(cause instanceof Error ? cause.message : 'LH 데이터 품질을 조회하지 못했습니다.')
    } finally {
      if (version === reloadVersion.current) setLoading(false)
    }
  }

  const held = quality?.heldRequests.find((item) =>
    selectedRequest !== null && item.requestDescription === selectedRequest.requestDescription
      && item.proposedFingerprint === selectedRequest.proposedFingerprint,
  )

  useEffect(() => {
    if (selectedRequest !== null && !held) {
      setSelectedRequest(null)
      setPblancId('')
      setEvidenceUrl('')
      setReason('')
      setConfirmed(false)
      setResult('선택한 보류 요청이 변경되었습니다. 현재 목록에서 대상을 다시 확인해 주세요.')
    }
  }, [selectedRequest, held])

  async function apply(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!held?.proposedFingerprint || !confirmed || applying) return
    setApplying(true)
    setResult(null)
    try {
      await applyVerifiedLhSupplyReplacement(pblancId.trim(), {
        requestDescription: held.requestDescription,
        proposedFingerprint: held.proposedFingerprint,
        evidenceUrl: evidenceUrl.trim(),
        reason: reason.trim(),
      })
      setSelectedRequest(null)
      setPblancId('')
      setEvidenceUrl('')
      setReason('')
      setConfirmed(false)
      setResult('근거 승인 후 LH 공급을 다시 조회했습니다. 반영된 원천과 제품 정제 결과를 확인해 주세요.')
      await reload()
    } catch (cause) {
      setResult(cause instanceof Error ? cause.message : '공급 정정을 반영하지 못했습니다.')
    } finally {
      setApplying(false)
    }
  }

  return (
    <section className="data-pipeline-group" aria-labelledby="lh-quality-title">
      <div className="data-pipeline-group-heading">
        <div>
          <h3 id="lh-quality-title">LH 공고 데이터 정확성</h3>
          <p>수집 요청의 성공과 제품 정보의 충족 상태를 따로 확인합니다. 제품 지표는 현재 삭제되지 않은 LH 공고 기준입니다.</p>
        </div>
        <button type="button" onClick={() => void reload()} disabled={loading}>다시 조회</button>
      </div>
      {loading && quality === null ? <p>품질 지표를 불러오는 중입니다.</p> : null}
      {error ? <p role="alert" className="data-pipeline-error">{error}</p> : null}
      {quality ? (
        <div className="lh-quality-content">
          <section className="lh-quality-metric-group" aria-labelledby="lh-collection-metrics">
            <h4 id="lh-collection-metrics">수집 상태</h4>
            <div className="lh-quality-metrics">
              <div><h5>최근 LH 공급 수집</h5><p>{collectionRate(collectionExecution, 'COLLECT_LH_ANNOUNCEMENT_SUPPLIES')}</p>
                <small>원천 선택 충돌 {selectionFailures(collectionExecution, 'COLLECT_LH_ANNOUNCEMENT_SUPPLIES')}건</small></div>
              <div><h5>최근 LH 상세 수집</h5><p>{collectionRate(collectionExecution, 'COLLECT_LH_ANNOUNCEMENT_DETAILS')}</p>
                <small>원천 선택 충돌 {selectionFailures(collectionExecution, 'COLLECT_LH_ANNOUNCEMENT_DETAILS')}건</small></div>
              <div><h5>공급 원천 확보</h5><p>{coverage(quality.supplyCollection.collectedRequests, quality.supplyCollection.totalRequests)}</p>
                <small>가장 최근 실제 수집 {time(quality.supplyCollection.latestCollectedAt)}</small></div>
              <div><h5>상세 원천 확보</h5><p>{coverage(quality.detailCollection.collectedRequests, quality.detailCollection.totalRequests)}</p>
                <small>가장 최근 실제 수집 {time(quality.detailCollection.latestCollectedAt)}</small></div>
            </div>
          </section>
          <section className="lh-quality-metric-group" aria-labelledby="lh-product-metrics">
            <h4 id="lh-product-metrics">제품 정보</h4>
            <div className="lh-quality-metrics">
              <div><h5>단지 연결</h5><p>{coverage(quality.connection.complexLinked, quality.connection.total)}</p></div>
              <div><h5>주택형 연결</h5><p>{coverage(quality.connection.housingTypeLinked, quality.connection.total)}</p></div>
              <div><h5>숫자 금액 확보</h5><p>{coverage(quality.amounts.fulfilled, quality.amounts.total)}</p></div>
              <div><h5>일정 검토</h5><p>{coverage(quality.schedules.reviewed, quality.schedules.total)}</p>
                <small>확인한 접수 일정 {quality.schedules.withApplicationSchedule}건</small></div>
            </div>
          </section>
          <p className="ingest-meta">원천 확보는 현재 수집 대상의 조회 조건에 맞는 성공 원천 유무입니다. 과거 성공도 포함하며 이번 실행의 성공률이나 현재 값의 최신성을 뜻하지 않습니다. 가장 최근 실제 수집 시각은 대상 중 최댓값입니다. 금액 확보는 공급행에 보증금·월세 숫자 쌍이 하나 이상 있는 경우입니다.</p>
          <div className="lh-quality-review-grid">
            <section className="lh-quality-summary">
              <h4>연결되지 않은 공급행 사유</h4>
              {Object.keys(quality.connection.unlinkedReasons).length === 0 ? <p>현재 미연결 공급행이 없습니다.</p> : (
                <ul>{Object.entries(quality.connection.unlinkedReasons).map(([reason, count]) => (
                  <li key={reason}>{reason}: {count}행</li>
                ))}</ul>
              )}
            </section>
            <section className="lh-quality-summary">
              <h4>기존 공급 원천 유지 <strong>{quality.preservedSourceRequestCount}건</strong></h4>
              <p className="ingest-meta">빈 응답·공급행 감소로 교체하지 않은 현재 요청 수입니다. 금액을 보존한 공급행 수와는 다릅니다.</p>
              <ul>{Object.entries(quality.preservedReasons).map(([reason, count]) => (
                <li key={reason}>{preservationReason(reason)}: {count}건</li>
              ))}</ul>
            </section>
            <section className="lh-quality-summary">
              <h4>기존 숫자 금액 유지 <strong>{quality.preservedAmountTargetCount}건</strong></h4>
              <ul>{Object.entries(quality.preservedAmountReasons).map(([reason, count]) => (
                <li key={reason}>{amountPreservationReason(reason)}: {count}건</li>
              ))}</ul>
            </section>
          </div>
          <details className="lh-quality-candidates">
            <summary><span>미연결 LH 임대 공고 조사 후보 {quality.unlinkedLhLeaseCatalogCount}건</span><span>목록 보기</span></summary>
            <p className="ingest-meta">LH 단독 모집인지 기존 공고의 정정인지 확인할 대상입니다. 관계 확인 전에는 제품 공고 수로 보지 않습니다.</p>
            <div className="lh-quality-candidate-scroll" tabIndex={0} aria-label="미연결 LH 공고 후보 목록">
              <ul>{quality.unlinkedLhCandidates.map((candidate) => (
                <li key={candidate.sourceKey}>
                  <strong>{candidate.panId}</strong>
                  <span>{candidate.sourceKey}</span>
                  <time dateTime={candidate.changedAt}>변경 {time(candidate.changedAt)}</time>
                </li>
              ))}</ul>
            </div>
            {quality.unlinkedLhLeaseCatalogCount > quality.unlinkedLhCandidates.length ? (
              <p className="ingest-meta">최근 변경 50건만 표시합니다.</p>
            ) : null}
          </details>
          <section className="lh-quality-held">
            <h4>원천 감소·정정 보류 요청</h4>
            {quality.heldRequests.length === 0 ? <p>현재 보류된 공급 요청이 없습니다.</p> : (
              <ul>{quality.heldRequests.map((item) => (
                <li key={item.requestDescription}>
                  <strong>{item.requestDescription}</strong> · {time(item.lastOccurredAt)}
                  <p>{item.reason}</p>
                  {item.proposedFingerprint ? <button type="button" disabled={applying} onClick={() => {
                    setSelectedRequest({ requestDescription: item.requestDescription,
                      proposedFingerprint: item.proposedFingerprint })
                    setPblancId('')
                    setEvidenceUrl('')
                    setReason('')
                    setConfirmed(false)
                    setResult(null)
                  }}>확인한 정정 반영</button> : <p>새 수집에서 응답 지문을 확보한 뒤 승인할 수 있습니다.</p>}
                </li>
              ))}</ul>
            )}
            {quality.preservedSourceRequestCount > quality.heldRequests.length ? (
              <p className="ingest-meta">최근 50건만 표시합니다. 전체 내역은 실패 요청 화면에서 확인하세요.</p>
            ) : null}
            <Link to="/admin/failures?category=collection">수집 실패 요청 전체 보기</Link>
          </section>
          {held?.proposedFingerprint ? (
            <form className="lh-quality-review" onSubmit={(event) => void apply(event)}>
              <h4>공식 근거 확인 후 공급 재조회</h4>
              <p>선택한 요청: {held.requestDescription}</p>
              <p className="ingest-meta">승인할 응답 지문: {held.proposedFingerprint}</p>
              <label>마이홈 공고 ID<input required value={pblancId} onChange={(event) => setPblancId(event.target.value)} /></label>
              <label>LH 공식 공고문 URL<input required type="url" value={evidenceUrl}
                onChange={(event) => setEvidenceUrl(event.target.value)} /></label>
              <label>철회·정정 확인 사유<textarea required value={reason}
                onChange={(event) => setReason(event.target.value)} /></label>
              <label><input type="checkbox" checked={confirmed} onChange={(event) => setConfirmed(event.target.checked)} />
                공고문에서 실제 철회·정정을 확인했습니다.</label>
              <button type="submit" disabled={!confirmed || applying}>{applying ? '다시 조회 중…' : '승인하고 공급 다시 조회'}</button>
            </form>
          ) : null}
          {result ? <p role="status">{result}</p> : null}
        </div>
      ) : null}
    </section>
  )
}

function coverage(fulfilled: number, total: number): string {
  if (total === 0) return '대상 없음'
  return `${fulfilled}/${total} (${Math.round(fulfilled / total * 100)}%)`
}

function collectionRate(execution: DataPipelineExecution, step: string): string {
  const reports = [...(execution.completedStepResults ?? []), ...execution.partiallyFailedSteps]
  const matched = reports.find((item) => item.step === step)?.report
  if (typeof matched !== 'object' || matched === null || !('successfulRequestCount' in matched)
    || !('failedRequestCount' in matched)) return '이번 실행의 요청별 결과 없음'
  const successful = matched.successfulRequestCount
  const failed = matched.failedRequestCount
  if (typeof successful !== 'number' || typeof failed !== 'number') return '이번 실행의 요청별 결과 없음'
  const attemptedFailures = failed - selectionFailures(execution, step)
  if (attemptedFailures < 0) return '이번 실행의 요청별 결과 없음'
  if (successful + attemptedFailures === 0) return '실제 요청 없음'
  return coverage(successful, successful + attemptedFailures)
}

function selectionFailures(execution: DataPipelineExecution, step: string): number {
  const reports = [...(execution.completedStepResults ?? []), ...execution.partiallyFailedSteps]
  const report = reports.find((item) => item.step === step)?.report
  if (typeof report !== 'object' || report === null || !('selectionFailedRequestCount' in report)) return 0
  const count = report.selectionFailedRequestCount
  return typeof count === 'number' && Number.isSafeInteger(count) && count >= 0 ? count : 0
}

function amountPreservationReason(reason: string): string {
  const labels: Record<string, string> = {
    LH_AMOUNT_NOT_PROVIDED: 'LH 신규 금액 미제공',
    LH_SUPPLY_NOT_PROVIDED: 'LH 공급 정보 미제공',
    LH_SUPPLY_MATCHING_FAILED: 'LH 공급행 연결 실패',
    MYHOME_MAPPING_REJECTED: '주택형·공고 매핑 보류',
    LH_ENRICHMENT_REJECTED: 'LH 보강 검증·연결 보류',
  }
  return labels[reason] ?? reason
}

function preservationReason(reason: string): string {
  if (reason === 'EmptyLhSupplyReplacementException') return '빈 공급 응답'
  if (reason === 'IncompleteLhSupplyReplacementException') return '기존 공급행 감소'
  return reason
}

function time(value: string | null): string {
  return value ? new Date(value).toLocaleString('ko-KR') : '기록 없음'
}
