import { useCallback, useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { ListPagination } from '../management/ListPagination'
import { isTarget, loadNotificationTargets, loadNotificationTrend, targetLabels } from './api'
import styles from './Notifications.module.css'

const timestamp = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', dateStyle: 'short', timeStyle: 'short' })
function useInsight<T>(key: string, loader: (signal: AbortSignal) => Promise<T>, valid: boolean) {
  const [attempt, setAttempt] = useState(0)
  const requestKey = `${key}:${attempt}`
  const [result, setResult] = useState<{ key: string; data: T } | null>(null)
  const [failure, setFailure] = useState<{ key: string; message: string } | null>(null)
  useEffect(() => {
    if (!valid) return
    setFailure(null)
    const controller = new AbortController()
    void loader(controller.signal).then(data => {
      if (!controller.signal.aborted) { setFailure(null); setResult({ key: requestKey, data }) }
    }).catch(cause => {
      if (!controller.signal.aborted) setFailure({ key: requestKey,
        message: cause instanceof Error ? cause.message : '현황을 불러오지 못했습니다.' })
    })
    return () => controller.abort()
  }, [requestKey, loader, valid])
  const data = valid && result?.key === requestKey ? result.data : null
  const error = !valid ? '검색 조건을 확인해 주세요.' : failure?.key === requestKey ? failure.message : ''
  return { data, error, retry: () => setAttempt(value => value + 1), pending: valid && !data && !error }
}
function InsightError({ error, retry }: { error: string; retry: () => void }) {
  return <div className={styles.error} role="alert"><p>{error}</p><button type="button" onClick={retry}>다시 불러오기</button></div>
}
export function NotificationTargetOverview() {
  const [params, setParams] = useSearchParams()
  const type = params.get('targetType') ?? '', pageValue = params.get('targetPage') ?? '0'
  const page = Number(pageValue)
  const query = new URLSearchParams({ page: String(page), size: '20' })
  if (type) query.set('targetType', type)
  const key = query.toString()
  const loader = useCallback((signal: AbortSignal) => loadNotificationTargets(key, signal), [key])
  const valid = (!type || isTarget(type)) && /^\d+$/.test(pageValue) && Number.isSafeInteger(page) && page <= 2147483647
  const { data, error, retry, pending } = useInsight(key, loader, valid)
  useEffect(() => {
    if (data && data.totalPages > 0 && page >= data.totalPages) {
      setParams(current => { current.set('targetPage', String(data.totalPages - 1)); return current }, { replace: true })
    }
  }, [data, page, setParams])
  return <section className={styles.panel} aria-label="대상별 신청 현황"><div className={styles.workspace}>
    <div className={styles.insightHeading}><div><h2>신청자가 많은 대상</h2><p className={styles.meta}>현재 활성 신청 기준</p></div>
      <label>현황 대상<select aria-label="현황 대상" value={type} onChange={event => {
        const next = new URLSearchParams({ view: 'targets' }); if (event.target.value) next.set('targetType', event.target.value); setParams(next)
      }}><option value="">전체 대상</option>{Object.entries(targetLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
      <button type="button" onClick={retry} disabled={pending}>새로고침</button></div>
    {pending ? <p className={styles.insightMessage} role="status">대상별 현황을 불러오는 중…</p> : null}
    {error ? <InsightError error={error} retry={retry} /> : null}
    {data ? <div className={styles.results}><div className={styles.resultHeading}><strong>대상 {data.totalElements.toLocaleString('ko-KR')}개</strong>
      <span className={styles.meta}>조회 {timestamp.format(new Date(data.asOf))}</span></div>
      {data.items.length ? <div className={styles.scroll}><table className={`${styles.table} ${styles.targetTable}`} aria-label="대상별 활성 신청 목록">
        <thead><tr><th scope="col">순위</th><th scope="col">대상</th><th scope="col">활성 신청 회원</th></tr></thead>
        <tbody>{data.items.map((target, index) => {
          const name = target.targetName ?? '대상 확인 불가'
          const search = new URLSearchParams({ targetType: target.targetType, targetId: target.targetId, groupTarget: 'true' })
          return <tr key={`${target.targetType}:${target.targetId}`}><td data-label="순위"><span className={styles.rank}>{data.page * 20 + index + 1}</span></td><td data-label="대상"><Link
            aria-label={`${name} 신청자 보기`} to={`/admin/notification-subscriptions?${search}`}>{name}</Link>
            <span className={styles.kind}>{targetLabels[target.targetType]} · {target.targetId}</span></td>
            <td data-label="회원"><strong>{target.activeMembers.toLocaleString('ko-KR')}명</strong></td></tr>
        })}</tbody></table></div> : <p className={styles.empty}>활성 신청이 있는 대상이 없습니다.</p>}
      {data.totalPages > 1 ? <ListPagination page={data.page} totalPages={data.totalPages} label="대상별 현황 페이지"
        onMove={next => setParams(current => { current.set('targetPage', String(next)); return current })} /> : null}
    </div> : null}</div></section>
}

export function NotificationTrendOverview() {
  const [params, setParams] = useSearchParams()
  const days = params.get('days') ?? '30'
  const [expandedPeriod, setExpandedPeriod] = useState<string | null>(null)
  const showUncollected = expandedPeriod === days
  const key = `days=${days}`
  const loader = useCallback((signal: AbortSignal) => loadNotificationTrend(key, signal), [key])
  const { data, error, retry, pending } = useInsight(key, loader, ['7', '30', '90'].includes(days))
  const activations = data?.days.reduce((sum, day) => sum + (day.activations ?? 0), 0) ?? 0
  const cancellations = data?.days.reduce((sum, day) => sum + (day.cancellations ?? 0), 0) ?? 0
  const collected = data?.days.some(day => day.activations !== null) ?? false
  const uncollected = data?.days.filter(day => day.activations === null).length ?? 0
  const visibleDays = data?.days.filter(day => showUncollected || day.activations !== null) ?? []
  const max = Math.max(1, ...(data?.days.flatMap(day => [day.activations ?? 0, day.cancellations ?? 0]) ?? []))
  return <section className={styles.panel} aria-label="신청·취소 추이"><div className={styles.workspace}>
    <div className={styles.insightHeading}><div><h2>신청·취소 추이</h2><p className={styles.meta}>실제 상태가 바뀐 건수 · 한국 시간 기준</p></div>
      <label>조회 기간<select value={days} onChange={event => setParams({ view: 'trend', days: event.target.value })}>
        {[7, 30, 90].map(value => <option key={value} value={value}>최근 {value}일</option>)}</select></label>
      <button type="button" onClick={retry} disabled={pending}>새로고침</button></div>
    {pending ? <p className={styles.insightMessage} role="status">신청·취소 추이를 불러오는 중…</p> : null}
    {error ? <InsightError error={error} retry={retry} /> : null}
    {data ? <div className={styles.results}><dl className={styles.summary}>
      <div><dt>집계 기간 신청</dt><dd>{collected ? `${activations.toLocaleString('ko-KR')}건` : '미집계'}</dd></div>
      <div><dt>집계 기간 취소</dt><dd>{collected ? `${cancellations.toLocaleString('ko-KR')}건` : '미집계'}</dd></div></dl>
      <div className={styles.trendToolbar}><span className={styles.meta}>{data.collectionStartedAt
        ? `집계 시작 ${timestamp.format(new Date(data.collectionStartedAt))}` : '집계 기록이 아직 없습니다.'}</span>
        {uncollected > 0 ? <button type="button" aria-expanded={showUncollected} onClick={() => setExpandedPeriod(showUncollected ? null : days)}>
          {showUncollected ? '미집계 날짜 접기' : `미집계 ${uncollected}일 보기`}</button> : null}</div>
      {visibleDays.length ? <div className={`${styles.scroll} ${styles.trendScroll}`}><table className={`${styles.table} ${styles.trendTable}`} aria-label="일별 신청·취소">
        <thead><tr><th scope="col">날짜</th><th scope="col">신청</th><th scope="col">취소</th></tr></thead>
        <tbody>{[...visibleDays].reverse().map(day => <tr key={day.day}><td data-label="날짜"><time dateTime={day.day}>{day.day}</time></td>
          <td data-label="신청">{day.activations === null ? '미집계' : <div className={styles.trendCell}>
            <span className={styles.trendBar} aria-hidden="true" style={{ width: `${day.activations / max * 100}%` }} />
            <span>{day.activations.toLocaleString('ko-KR')}건</span></div>}</td>
          <td data-label="취소">{day.cancellations === null ? '미집계' : <div className={styles.trendCell}>
            <span className={`${styles.trendBar} ${styles.cancelBar}`} aria-hidden="true" style={{ width: `${day.cancellations / max * 100}%` }} />
            <span>{day.cancellations.toLocaleString('ko-KR')}건</span></div>}</td></tr>)}</tbody></table></div>
        : <p className={styles.empty}>선택한 기간에 집계된 날짜가 없습니다.</p>}
      <details className={styles.explanation}><summary>집계 기준</summary><p>
        한국 시간 기준 실제 상태 변화만 집계합니다. 재신청은 신청 1건이며, 중복 요청·재전송과 자동 만료는 제외합니다.
        집계 시작 이전은 미집계이고, 시작일과 오늘은 일부 시간만 포함됩니다.
      </p></details>
    </div> : null}</div></section>
}
