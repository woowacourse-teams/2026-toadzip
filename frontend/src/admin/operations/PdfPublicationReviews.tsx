import { useCallback, useState } from 'react'
import { Link } from 'react-router'
import { AdminIcon } from '../shared/AdminIcon'
import { loadPdfReviews } from './api'
import { useOperationsQuery } from './useOperationsQuery'
import { OperationsStatus } from './OperationsStatus'
import styles from './OperationsHome.module.css'

/** 서버는 여러 사유를 줄바꿈으로 이어 보낸다. 홈에는 건수만 보이고 사유 내용은 PDF 보정 화면에서 본다. */
const reasonCount = (reason: string) => reason.split('\n').filter(line => line.trim()).length

export function PdfPublicationReviews() {
  const [history, setHistory] = useState<(number | null)[]>([null])
  const cursor = history.at(-1) ?? null
  const load = useCallback((signal: AbortSignal) => loadPdfReviews(cursor, signal), [cursor])
  const query = useOperationsQuery(load, 0)
  const next = query.data?.nextBefore
  return <section className={styles.pdfReviews} aria-label="자동 보정 검수함">
    <h3>자동 보정 검수함</h3>
    <OperationsStatus query={query} />
    {query.data?.items.length === 0 && <p className={styles.message}>검토할 자동 보정 결과가 없습니다.</p>}
    {Boolean(query.data?.items.length) && <ul className={styles.pdfReviewList}>{query.data?.items.map(item => <li key={item.jobId}>
      <Link to={`/admin/announcements/${item.announcementId}/pdf-correction`}>
        <span>{item.name}</span>{item.eligibilityOnly && <em>신청자격</em>}
        <small>보류 사유 {reasonCount(item.reason)}건</small><AdminIcon name="chevronRight" /></Link>
    </li>)}</ul>}
    {history.length > 1 && <button type="button" onClick={() => setHistory(history.slice(0, -1))}>이전 결과</button>}
    {next != null && <button type="button" onClick={() => setHistory([...history, next])}>다음 결과</button>}
  </section>
}
