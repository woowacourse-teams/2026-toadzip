import { useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { IngestFailurePanel } from './IngestFailurePanel'
import { failureCategories, startDataPipeline, type DataPipelineType, type FailureCategory } from './api'
export function FailureReviewPage() {
  const [params] = useSearchParams()
  const raw = params.get('category') ?? 'collection'
  const category: FailureCategory = Object.hasOwn(failureCategories,raw) ? raw as FailureCategory : 'collection'
  const [busy,setBusy] = useState(false)
  const [message,setMessage] = useState('')
  const [error,setError] = useState('')
  async function retry(type:DataPipelineType) {
    setBusy(true);setError('');setMessage('')
    try {await startDataPipeline(type);setMessage('재실행을 시작했습니다. 수집·정제 화면에서 진행 상황을 확인하세요.')}
    catch(cause) {setError(cause instanceof Error ? cause.message : '재실행하지 못했습니다.')}
    finally {setBusy(false)}
  }
  return <section className="management-page"><header className="management-heading"><div><h1>실패·검토 항목</h1><p>실패 원인을 확인하고 데이터를 수정한 뒤 관련 작업을 재실행합니다.</p></div><Link to="/admin/ingest">수집·정제 상태 보기</Link></header>
    <section className="admin-detail-section"><h2>검토할 데이터</h2><div className="admin-inline"><Link to="/admin/complexes?review=true">원천이 변경된 단지</Link><Link to="/admin/announcements?review=true">원천이 변경된 공고</Link></div></section>
    <IngestFailurePanel key={`${category}-${params.get('executionId')}`} initialCategory={category} executionId={params.get('executionId')} />
    <section className="admin-detail-section"><h2>수정 후 재처리</h2><p>개별 행이 아니라 선택한 작업 전체를 다시 실행합니다. 기존 처리 결과와 중복 방지 규칙을 유지합니다. API 호출 제한은 해제된 뒤 재시도해 주세요.</p>
      <div className="admin-inline">{([['COMPLEX_COLLECTION','단지 수집'],['COMPLEX_REFINEMENT','단지 정제'],['ANNOUNCEMENT_COLLECTION','공고 수집'],['ANNOUNCEMENT_REFINEMENT','공고 정제']] as const).map(([type,label]) => <button key={type} disabled={busy} onClick={() => void retry(type)}>{label} 재실행</button>)}</div>
      {message ? <p role="status">{message}</p> : null}{error ? <p role="alert">{error}</p> : null}</section>
  </section>
}
