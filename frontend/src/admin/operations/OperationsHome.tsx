import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { getFailureReviews } from '../ingest/failureReviewApi'
import { getQualityQueue } from '../quality/qualityQueueApi'
import { listFeedback } from '../feedback/api'
import { listDeliveries } from '../notifications/deliveryApi'
import styles from './OperationsHome.module.css'

const work = [
  { title: '단지 수집·정제 실패', href: '/admin/failures?domain=complex&status=PENDING', description: '원천 수집, 주소·좌표와 주택형 정제 실패를 복구합니다.', load: (signal: AbortSignal) => getFailureReviews('complex', 'all', 'PENDING', 0, signal) },
  { title: '공고 수집·정제 실패', href: '/admin/failures?domain=announcement&status=PENDING', description: '공고 수집, 단지 연결과 공급정보 보강 실패를 복구합니다.', load: (signal: AbortSignal) => getFailureReviews('announcement', 'all', 'PENDING', 0, signal) },
  { title: '검토할 데이터', href: '/admin/quality', description: '누락·원천 변경·불일치를 공식 근거와 비교합니다.', load: (signal: AbortSignal) => getQualityQueue(new URLSearchParams({ size: '1' }), signal) },
  { title: '새 사용자 의견', href: '/admin/feedback?status=OPEN', description: '의견을 조사하고 처리 상태·결과·처리자를 확인합니다.', load: (signal: AbortSignal) => listFeedback(new URLSearchParams({ status: 'OPEN', size: '1' }), signal) },
  { title: '알림 발송 실패', href: '/admin/notification-deliveries?status=FAILED', description: '발송 실패 원인을 회원의 알림 신청과 함께 확인합니다.', load: (signal: AbortSignal) => listDeliveries(new URLSearchParams({ status: 'FAILED', size: '1' }), signal) },
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
    <header><div><h1>운영 홈</h1><p>처리할 업무를 고르고, 보정 후 실제 반영 결과까지 확인하세요.</p></div>
      <button type="button" disabled={!counts} onClick={() => setAttempt(value => value + 1)}>현황 새로고침</button></header>
    <p role="status">{counts ? `${checkedAt} 확인 · 한국 시각` : '운영 현황을 확인하는 중…'}</p>
    <ul className={styles.work}>{work.map((item, index) => <li key={item.href}>
      <div><h2><Link to={item.href}>{item.title} →</Link></h2><p>{item.description}</p>
        {counts?.[index].error && <p role="alert">조회 실패: {counts[index].error} 목록에서 다시 확인해 주세요.</p>}</div>
      <span className={styles.count} aria-label={`${item.title} 건수`}>{counts?.[index].value?.toLocaleString('ko-KR') ?? '—'}<small>건</small></span>
    </li>)}</ul>
    <section><h2>작업 도구</h2><nav className={styles.links} aria-label="운영 도구">
      <Link to="/admin/ingest">수집 실행·단계별 이력</Link>
      <Link to="/admin/complexes">단지 관리</Link><Link to="/admin/announcements">공고 관리</Link>
      <Link to="/admin/users">회원 조사</Link><Link to="/admin/notification-subscriptions">알림 신청·분석</Link>
      <Link to="/admin/feedback?status=IN_PROGRESS">처리 중 의견</Link><Link to="/admin/feedback?status=ON_HOLD">보류한 의견</Link>
    </nav></section>
  </section>
}
