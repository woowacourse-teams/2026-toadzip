import { collectionCause, failureTarget } from './failurePresentation'
import { Link } from 'react-router'
import { useEffect, useRef, useState } from 'react'
import { failureCategories, getIngestFailures, type FailureCategory, type IngestFailure } from './api'

export function IngestFailurePanel({
  initialCategory, executionId,
}: { initialCategory: FailureCategory, executionId: string | null }) {
  const heading = useRef<HTMLHeadingElement>(null)
  useEffect(() => { heading.current?.focus() }, [])
  const [category, setCategory] = useState(initialCategory)
  const [history, setHistory] = useState(false)
  const [page, setPage] = useState(0)
  const [revision, setRevision] = useState(0)
  const [rows, setRows] = useState<readonly IngestFailure[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let active = true
    setLoading(true)
    setError(null)
    void getIngestFailures(category, history, page).then((result) => {
      if (active) setRows(result)
    }).catch((cause: unknown) => {
      if (active) setError(cause instanceof Error ? cause.message : '실패 목록을 불러오지 못했습니다.')
    }).finally(() => {
      if (active) setLoading(false)
    })
    return () => { active = false }
  }, [category, history, page, revision])

  return (
    <section className="ingest-failures" aria-labelledby="ingest-failures-title">
      <div className="ingest-failures-heading">
        <div>
          <h3 ref={heading} tabIndex={-1} id="ingest-failures-title">수집·처리 문제 확인</h3>
          <p>선택한 분류의 {history ? '전체 실패 이력' : '전체 미해결 항목'}입니다. 이전 실행에서 발생한 실패도 포함합니다.</p>
          <p>미해결은 마지막 재확인까지 해결되지 않은 기록입니다. 현재 외부 서버의 상태를 실시간으로 나타내지는 않습니다.</p>
        </div>
        <button type="button" onClick={() => setRevision((value) => value + 1)} disabled={loading}>
          목록 새로고침
        </button>
      </div>
      <div className="ingest-failure-filters">
        <label>실패 분류
          <select value={category} onChange={(event) => {
            const selected = event.target.value
            if (Object.hasOwn(failureCategories, selected)) {
              setCategory(selected as FailureCategory)
              setPage(0)
            }
          }}>
            {Object.entries(failureCategories).map(([value, item]) => (
              <option key={value} value={value}>{item.label}</option>
            ))}
          </select>
        </label>
        <label>조회 범위
          <select value={history ? 'history' : 'pending'} onChange={(event) => {
            setHistory(event.target.value === 'history')
            setPage(0)
          }}>
            <option value="pending">현재 미해결</option>
            <option value="history">해결된 항목 포함</option>
          </select>
        </label>
      </div>
      {loading ? <p role="status">실패 목록을 불러오는 중입니다.</p> : null}
      {!loading && error ? <p role="alert">{error} 목록 새로고침으로 다시 시도해 주세요.</p> : null}
      {!loading && !error && rows.length === 0 ? <p>이 페이지에 표시할 실패가 없습니다.</p> : null}
      {!loading && !error && rows.length > 0 ? <p className="ingest-page-summary">이 페이지 {rows.length}건{executionId ? ` · 선택한 실행에서 발생 ${rows.filter(row => row.executionId === executionId).length}건` : ''}</p> : null}
      {!loading && !error && rows.length > 0 ? (
        <div className="ingest-failure-table-scroll" tabIndex={0} role="region" aria-label="실패 목록 가로 스크롤">
          <table className="ingest-failure-table">
            <caption>{failureCategories[category].label} · {history ? '실패 이력' : '미해결 실패'} · {page + 1}페이지</caption>
            <thead><tr><th scope="col">대상·작업</th><th scope="col">원인·다음 조치</th><th scope="col">최근 발생 / 상태</th></tr></thead>
            <tbody>{rows.map((row, index) => {
              const target = failureTarget(row)
              const cause = category === 'collection' ? collectionCause(row) : null
              return (
              <tr key={`${row.sourceKey}-${row.occurredAt}-${index}`}>
                <td>
                  <strong>{target.title}</strong>
                  {target.source ? <span className="ingest-meta">{target.source}</span> : null}
                  {target.context ? <span className="ingest-meta">{target.context}</span> : null}
                  {typeof row.raw.sourceAnnouncementIdentifier === 'string' ? <Link to={`/admin/announcements?keyword=${encodeURIComponent(row.raw.sourceAnnouncementIdentifier)}`}>관련 공고 찾기</Link>
                    : typeof row.raw.sourceComplexIdentifier === 'string' ? <Link to={`/admin/complexes?keyword=${encodeURIComponent(row.raw.sourceComplexIdentifier)}`}>관련 단지 찾기</Link> : null}
                  <details><summary>요청·오류 원문 보기</summary>
                    <p>원문 오류: {row.detail}</p>
                    <p>원천 키: {row.sourceKey}</p>
                    <p>최근 실행 ID: {row.executionId ?? '기록 없음'}</p>
                    <pre>{JSON.stringify(row.raw, null, 2)}</pre>
                  </details>
                </td>
                <td><strong>{cause?.title ?? reasonLabel(row.reason)}</strong><p>{cause ? row.status === 'RESOLVED' ? '해결된 기록입니다.' : row.status === 'SKIPPED' ? '건너뛴 기록입니다. 사유는 상세 기록에서 확인하세요.' : cause.action : row.detail}</p></td>
                <td>
                  <time dateTime={row.occurredAt}>{formatTime(row.occurredAt)}</time>
                  <span className="ingest-meta">{row.status === 'RESOLVED' ? '해결됨' : row.status === 'SKIPPED' ? '건너뜀' : '미해결'} · 발생 {row.occurrenceCount}회</span>
                  {executionId && row.executionId === executionId ? <span className="ingest-current">선택한 실행에서 발생</span> : executionId ? <span className="ingest-meta">{row.executionId ? '다른 실행 기록' : '실행 연결 정보 없음'}</span> : null}
                </td>
              </tr>
            )})}</tbody>
          </table>
        </div>
      ) : null}
      <nav className="ingest-pagination" aria-label="실패 목록 페이지">
        <button type="button" disabled={loading || page === 0} onClick={() => setPage((value) => value - 1)}>이전 페이지</button>
        <span>{page + 1}페이지</span>
        <button type="button" disabled={loading || error !== null || rows.length < 20} onClick={() => setPage((value) => value + 1)}>다음 페이지</button>
      </nav>
    </section>
  )
}

function formatTime(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('ko-KR', { hour12: false })
}

function reasonLabel(reason: string): string {
  const labels: Record<string, string> = {
    COMPLEX_NOT_FOUND: '연결할 단지를 찾지 못함',
    AMBIGUOUS_COMPLEX: '일치하는 단지가 여러 개',
    MISSING_REQUIRED_VALUE: '필수 원천값 누락',
    INVALID_SOURCE: '원천 데이터 형식 오류',
    DUPLICATE_TARGET_COMPLEX: '같은 단지에 여러 원천이 연결됨',
    PREVIOUS_ANNOUNCEMENT_NOT_FOUND: '이전 공고를 찾지 못함',
    CYCLIC_ANNOUNCEMENT_REVISION: '이전 공고 참조가 순환함',
    ExternalDataRequestException: '외부 API 응답 오류',
    ExternalDataCallFailureException: '외부 API 호출 실패',
  }
  return labels[reason] ?? reason
}
