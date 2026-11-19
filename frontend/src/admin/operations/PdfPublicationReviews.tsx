import { useCallback, useState } from 'react'
import { Link } from 'react-router'
import { loadPdfReviews } from './api'
import { useOperationsQuery } from './useOperationsQuery'
import { OperationsStatus } from './OperationsStatus'

export function PdfPublicationReviews() {
  const [history, setHistory] = useState<(number | null)[]>([null])
  const cursor = history.at(-1) ?? null
  const load = useCallback((signal: AbortSignal) => loadPdfReviews(cursor, signal), [cursor])
  const query = useOperationsQuery(load, 0)
  const next = query.data?.nextBefore
  return <section aria-label="자동 보정 검수함">
    <h3>자동 보정 검수함</h3>
    <OperationsStatus query={query} />
    {query.data?.items.length === 0 && <p>검토할 자동 보정 결과가 없습니다.</p>}
    {Boolean(query.data?.items.length) && <ul>{query.data?.items.map(item => <li key={item.jobId}>
      <Link to={`/admin/announcements/${item.announcementId}/pdf-correction`}>{item.name}</Link>
      <p>{item.eligibilityOnly && <strong>신청자격 · </strong>}{item.reason}</p>
    </li>)}</ul>}
    {history.length > 1 && <button type="button" onClick={() => setHistory(history.slice(0, -1))}>이전 결과</button>}
    {next != null && <button type="button" onClick={() => setHistory([...history, next])}>다음 결과</button>}
  </section>
}
