import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { getFailureReviews } from '../ingest/failureReviewApi'
import { listFeedback } from '../feedback/api'
import { listDeliveries } from '../notifications/deliveryApi'
import styles from './OperationsHome.module.css'

const work = [
  { title: '단지 수집·정제 실패', href: '/admin/failures?domain=complex&status=PENDING', load: (signal: AbortSignal) => getFailureReviews('complex', 'all', 'PENDING', 0, signal) },
  { title: '공고 수집·정제 실패', href: '/admin/failures?domain=announcement&status=PENDING', load: (signal: AbortSignal) => getFailureReviews('announcement', 'all', 'PENDING', 0, signal) },
  { title: '새 사용자 의견', href: '/admin/feedback?status=OPEN', load: (signal: AbortSignal) => listFeedback(new URLSearchParams({ status: 'OPEN', size: '1' }), signal) },
  { title: '알림 발송 실패', href: '/admin/notification-deliveries?status=FAILED', load: (signal: AbortSignal) => listDeliveries(new URLSearchParams({ status: 'FAILED', size: '1' }), signal) },
]
type Count = { value: number; error?: never } | { error: string; value?: never }
export function OperationsHome() {
  const [counts, setCounts] = useState<Count[] | null>(null)
  const [attempt, setAttempt] = useState(0)
  const [checkedAt, setCheckedAt] = useState<string | null>(null)
  useEffect(() => {
    const controller = new AbortController(); setCounts(null)
    void Promise.allSettled(work.map(item => item.load(controller.signal))).then(results => {
      if (controller.signal.aborted) return
      setCounts(results.map(result => result.status === 'fulfilled' ? { value: result.value.totalElements }
        : { error: result.reason instanceof Error ? result.reason.message : '건수를 확인하지 못했습니다.' }))
      setCheckedAt(new Date().toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' }))
    })
    return () => controller.abort()
  }, [attempt])
  return <section className={styles.page}>
    <header><div><h1>운영 홈</h1></div>
      {/* 확인 시각은 새로고침 버튼과 같은 일이라 버튼 바로 옆에 둔다. */}
      <div className={styles.refresh}><p className={styles.checked} role="status">{counts ? `${checkedAt} 확인` : '운영 현황을 확인하는 중…'}</p>
        <button type="button" disabled={!counts} onClick={() => setAttempt(value => value + 1)}>현황 새로고침</button></div></header>
    <ul className={styles.work}>{work.map((item, index) => <li key={item.href} className={counts?.[index].value ? styles.attention : undefined}>
      <div className={styles.cardHead}><h2>{item.title}</h2>
        <Link className={styles.go} to={item.href} aria-label={`${item.title} 목록으로 이동`}><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M7 17 17 7M9 7h8v8" /></svg></Link></div>
      <span className={styles.count} aria-label={`${item.title} 건수`}>{counts?.[index].value?.toLocaleString('ko-KR') ?? '—'}<small>건</small></span>
      {counts?.[index].error && <p role="alert">조회 실패: {counts[index].error} 목록에서 다시 확인해 주세요.</p>}
    </li>)}</ul>
    <section className={styles.tools}><h2>작업 도구</h2><nav className={styles.links} aria-label="운영 도구">
      <Link to="/admin/ingest">수집 실행·단계별 이력</Link>
      <Link to="/admin/complexes">단지 관리</Link><Link to="/admin/announcements">공고 관리</Link>
      <Link to="/admin/users">회원 조사</Link><Link to="/admin/notification-subscriptions">알림 신청·분석</Link>
      <Link to="/admin/feedback?status=IN_PROGRESS">처리 중 의견</Link><Link to="/admin/feedback?status=ON_HOLD">보류한 의견</Link>
    </nav></section>
  </section>
}
