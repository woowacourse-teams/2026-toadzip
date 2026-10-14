import { useEffect, useId, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { Button } from '../../design-system/components/Button'
import { ListPagination } from '../management/ListPagination'
import { providerLabels } from '../users/presentation'
import { isIdentifier, isTargetIdentifier, isStatus, isTarget, isReview, reviewLabels, loadNotificationSubscriptions, statusLabels, targetLabels,
  type NotificationItem, type NotificationPage } from './api'
import styles from './Notifications.module.css'
import { NotificationTargetOverview, NotificationTrendOverview } from './NotificationInsights'

const timestamp = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
})
function date(value: string | null) { return value ? timestamp.format(new Date(value)) : '미확인' }
function targetLink(item: NotificationItem) {
  if (!item.targetName) return null
  const canonicalId = BigInt(item.targetId).toString()
  if (!isIdentifier(canonicalId)) return null
  if (item.targetType === 'COMPLEX') return `/admin/complexes/${canonicalId}`
  if (item.targetType === 'ANNOUNCEMENT') return `/admin/announcements/${canonicalId}`
  return null
}
export function NotificationManagementPage() {
  const [params] = useSearchParams()
  const view = params.get('view') ?? 'list'
  const views = [ ['list', '신청 목록', ''], ['targets', '대상별 현황', '?view=targets'],
    ['review', '확인 필요', '?view=review&status=ALL&review=ANY'], ['trend', '신청·취소 추이', '?view=trend'] ] as const
  return <section className={styles.page}><header className={styles.heading}><h1>알림 신청 관리</h1></header>
    <nav className={styles.tabs} aria-label="알림 관리 보기">{views.map(([key, label, query]) =>
      <Link key={key} aria-current={view === key ? 'page' : undefined} to={`/admin/notification-subscriptions${query}`}>{label}</Link>)}</nav>
    {view === 'targets' ? <NotificationTargetOverview /> : view === 'trend' ? <NotificationTrendOverview />
      : view === 'list' || view === 'review' ? <NotificationSubscriptions key={view} />
        : <p role="alert">화면 조건을 확인해 주세요. 신청 목록에서 다시 시작할 수 있습니다.</p>}</section>
}
export function NotificationSubscriptions({ userId }: { userId?: string }) {
  const SectionHeading = userId ? 'h3' : 'h2'
  const [params, setParams] = useSearchParams()
  const helpId = useId()
  const form = useRef<HTMLFormElement>(null)
  const pageKey = userId ? 'notificationPage' : 'page', statusKey = userId ? 'notificationStatus' : 'status'
  const status = params.get(statusKey) ?? (userId ? 'ALL' : 'ACTIVE')
  const review = userId ? 'ALL' : params.get('review') ?? (params.get('view') === 'review' ? 'ANY' : 'ALL')
  const groupTarget = !userId && params.get('groupTarget') === 'true'
  const reviewView = !userId && params.get('view') === 'review'
  const pageValue = params.get(pageKey) ?? '0', page = Number(pageValue)
  const targetType = userId ? '' : params.get('targetType') ?? '', targetId = userId ? '' : params.get('targetId') ?? ''
  const [keyword, setKeyword] = useState('')
  const [attempt, setAttempt] = useState(0)
  const [loaded, setLoaded] = useState<{ key: string; filters: string; value: NotificationPage } | null>(null)
  const [failure, setFailure] = useState<{ key: string; message: string } | null>(null)
  const valid = isStatus(status) && isReview(review) && (!targetType || isTarget(targetType)) && (!targetId || isTargetIdentifier(targetId))
    && (!userId || isIdentifier(userId)) && /^\d+$/.test(pageValue) && Number.isSafeInteger(page)
    && page <= 2147483647 && keyword.length <= 200 && (!params.has('groupTarget') || ['true', 'false'].includes(params.get('groupTarget') ?? ''))
  const filters = JSON.stringify([userId, status, targetType, targetId, keyword, review, groupTarget])
  const key = JSON.stringify([filters, pageValue, attempt])
  const data = valid && loaded?.filters === filters ? loaded.value : null
  const pending = valid && loaded?.key !== key && failure?.key !== key
  const error = valid ? failure?.key === key ? failure.message : '' : '검색 조건을 확인해 주세요.'
  const routeParams = new URLSearchParams()
  for (const name of ['view', 'status', 'targetType', 'targetId', 'page', 'review', 'groupTarget']) {
    const value = params.get(name)
    if (value) routeParams.set(name, value)
  }
  const returnTo = `/admin/notification-subscriptions${routeParams.size ? `?${routeParams}` : ''}`
  const hasFilters = Boolean(keyword || targetType || targetId || review !== 'ALL' || status !== (userId ? 'ALL' : 'ACTIVE'))

  function resetFilters() {
    form.current?.reset()
    setKeyword('')
    setParams(current => {
      const next = userId ? new URLSearchParams(current) : new URLSearchParams()
      next.delete(pageKey)
      next.delete(statusKey)
      if (reviewView) { next.set('view', 'review'); next.set('status', 'ALL'); next.set('review', 'ANY') }
      return next
    })
  }

  useEffect(() => {
    if (!valid) return
    setFailure(null)
    const controller = new AbortController()
    const query = new URLSearchParams({ page: String(page), size: '20', status })
    if (keyword) query.set('keyword', keyword)
    if (targetType) query.set('targetType', targetType)
    if (targetId) query.set('targetId', targetId)
    if (review !== 'ALL') query.set('review', review)
    if (groupTarget) query.set('groupTarget', 'true')
    void loadNotificationSubscriptions(query, controller.signal, userId).then(value => {
      if (controller.signal.aborted) return
      setFailure(null)
      if (value.totalPages > 0 && page >= value.totalPages) {
        setParams(current => { current.set(pageKey, String(value.totalPages - 1)); return current }, { replace: true })
        return
      }
      setLoaded({ key, filters, value })
    }).catch(cause => {
      if (!controller.signal.aborted) setFailure({ key,
        message: cause instanceof Error ? cause.message : '알림 신청 목록을 불러오지 못했습니다.' })
    })
    return () => controller.abort()
  }, [valid, key, filters, userId, page, pageKey, status, keyword, targetType, targetId, review, groupTarget, setParams])

  return <section className={styles.panel} aria-label={userId ? '회원 알림 신청' : '알림 신청 조회'}>
    {userId ? <h2>알림 신청</h2> : null}
    {data ? <div className={styles.overview}><dl className={styles.summary} aria-label={userId ? '회원 활성 신청 현황' : '전체 활성 신청 현황'}>
      <div><dt title={userId ? '이 회원의 현재 유효한 신청' : '유효한 신청이 있는 회원 수 · 중복 제외'}>{userId ? '회원 활성 신청 건수' : '전체 활성 신청 회원'}</dt>
        <dd>{(userId ? data.summary.activeSubscriptions : data.summary.activeMembers).toLocaleString('ko-KR')}{userId ? '건' : '명'}</dd>
      </div>
      {!userId ? <div><dt title="한 회원이 여러 대상을 신청하면 각각 1건">전체 활성 신청 건수</dt>
        <dd>{data.summary.activeSubscriptions.toLocaleString('ko-KR')}건</dd></div> : null}
    </dl><p className={styles.meta}>조회 {date(data.summary.asOf)} · 한국 시간</p></div> : null}
    <div className={styles.workspace}>
    <div className={styles.filterPanel}>
    <form ref={form} key={`${filters}#${pageValue}`} className={styles.filters} aria-label="알림 신청 검색" onSubmit={event => {
      event.preventDefault()
      const values = new FormData(event.currentTarget)
      setKeyword(String(values.get('keyword') ?? '').trim())
      setParams(current => {
        const next = userId ? new URLSearchParams(current) : new URLSearchParams()
        if (!userId && current.get('view') === 'review') next.set('view', 'review')
        if (groupTarget) next.set('groupTarget', 'true')
        next.delete(pageKey)
        next.set(statusKey, String(values.get('status') ?? 'ACTIVE'))
        if (!userId) next.set('review', String(values.get('review') ?? 'ALL'))
        for (const name of ['targetType', 'targetId']) {
          const value = String(values.get(name) ?? '').trim()
          if (!userId && value) next.set(name, value)
        }
        return next
      })
    }}>
      {!userId ? <><label className={styles.search}>로그인 이메일·회원 ID<input name="keyword" maxLength={200}
        defaultValue={keyword} placeholder="이메일 또는 회원 ID 검색" /></label>
      <label>대상 종류<select name="targetType" defaultValue={targetType}><option value="">전체 대상</option>
        {Object.entries(targetLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
      </> : null}
      <label>신청 상태<select name="status" defaultValue={status}>{Object.entries(statusLabels).map(([value, label]) =>
        <option key={value} value={value}>{label}</option>)}</select></label>
      {reviewView ? <label>확인 항목<select name="review" defaultValue={review}>{Object.entries(reviewLabels).map(([value, label]) =>
        <option key={value} value={value}>{label}</option>)}</select></label> : null}
      <div className={styles.actions}><Button data-design-system type="submit">검색</Button><button type="button" onClick={resetFilters}>초기화</button></div>
      {!userId ? <details className={styles.advanced} open={Boolean(targetId || !reviewView && review !== 'ALL') || undefined}>
        <summary>상세 검색</summary>
        <div>{!reviewView ? <label>확인 항목<select name="review" defaultValue={review}>{Object.entries(reviewLabels).map(([value, label]) =>
          <option key={value} value={value}>{label}</option>)}</select></label> : null}
          <label>대상 ID<input name="targetId" defaultValue={targetId} inputMode="numeric" maxLength={19}
          pattern="[0-9]{1,19}" aria-describedby={`${helpId}-target`} placeholder="예: 11" /></label>
          <p id={`${helpId}-target`}>지역 코드 또는 단지·공고 ID{groupTarget ? ' · 같은 대상의 이전 코드·선행 0 신청도 포함' : ''}</p></div>
      </details> : null}
    </form>
    {reviewView ? <details className={styles.explanation}><summary>확인 항목 기준</summary>
      <p>대상 확인 불가는 현재 대상 정보를 찾을 수 없는 신청입니다. 신청일 미확인은 신청 시각 기록이 없는 신청입니다. 이메일 미제공만으로는 확인 필요 항목에 포함하지 않습니다.</p>
    </details> : null}
    </div>
    {error ? <div className={styles.error} role="alert"><p>{error}</p>{valid ? <button type="button"
      onClick={() => setAttempt(value => value + 1)}>다시 불러오기</button> : null}</div> : null}
    <div className={styles.results} aria-busy={pending}><div className={styles.resultHeading}>
      <div><SectionHeading aria-live="polite">{data ? `검색 결과 ${data.totalElements.toLocaleString('ko-KR')}건` : '신청 목록'}</SectionHeading>
        <span className={styles.meta}>신청일 최신순</span></div>
      <button type="button" disabled={pending || !valid} onClick={() => setAttempt(value => value + 1)}>새로고침</button></div>
      {valid && hasFilters ? <ul className={styles.applied} aria-label="적용된 검색 조건">
        {status !== (userId ? 'ALL' : 'ACTIVE') ? <li>상태: {statusLabels[status]}</li> : null}
        {!userId && isTarget(targetType) ? <li>대상: {targetLabels[targetType]}</li> : null}
        {keyword ? <li>신청자: {keyword}</li> : null}
        {targetId ? <li>대상 ID: {targetId}</li> : null}
        {review !== 'ALL' && isReview(review) ? <li>확인: {reviewLabels[review]}</li> : null}
      </ul> : null}
      {pending ? <p role="status">알림 신청 목록을 불러오는 중…</p> : null}
      {data?.items.length ? <div className={styles.scroll} role="region" tabIndex={0} aria-label="알림 신청 목록 스크롤">
        <table className={styles.table} aria-label={userId ? '회원 알림 신청 목록' : '알림 신청 목록'}>
          <thead><tr>{!userId ? <th scope="col">신청자</th> : null}
            <th scope="col">대상</th><th scope="col">상태</th><th scope="col">신청일</th><th scope="col">만료일</th></tr></thead>
          <tbody>{data.items.map(item => <tr key={item.id}>
            {!userId ? <td data-label="신청자"><Link className={styles.primary}
              aria-label={`회원 ${item.userId} 상세 보기${item.email ? ` · ${item.email}` : ''}`}
              to={`/admin/users/${item.userId}?returnTo=${encodeURIComponent(returnTo)}`}>{item.email || `회원 ${item.userId}`}</Link>
              <span className={styles.kind}>{item.email ? `회원 ID ${item.userId} · ` : ''}{providerLabels[item.provider]}
                {!item.email ? ' · 이메일 미제공' : ''}</span></td> : null}
            <td data-label="대상">
              {targetLink(item) ? <Link className={styles.primary} to={targetLink(item) ?? ''}>{item.targetName}</Link>
                : <span className={styles.primary}>{item.targetName ?? '대상 확인 불가'}</span>}
              <span className={styles.kind}>{targetLabels[item.targetType]} · {item.targetId}</span></td>
            <td data-label="상태"><span className={styles.badge} data-status={item.status}
              title={item.status === 'ACTIVE' ? '현재 유효한 신청' : item.status === 'CANCELLED' ? '회원이 해제한 신청' : '유효기간이 지난 신청'}>{statusLabels[item.status]}</span></td>
            <td data-label="신청일">{item.requestedAt ? <time dateTime={item.requestedAt}>{date(item.requestedAt)}</time>
              : <span title="신청 시각 기록 없음">미확인</span>}</td>
            <td data-label="만료일"><time dateTime={item.expiresAt}>{date(item.expiresAt)}</time></td>
          </tr>)}</tbody>
        </table></div> : data ? <div className={styles.empty}><h3>표시할 알림 신청이 없습니다.</h3>
          <p>{hasFilters ? '검색어 또는 대상·상태 조건을 바꾸어 다시 찾아보세요.'
            : userId ? '이 회원이 신청한 알림이 없습니다.' : '현재 활성 신청이 없습니다. 전체 상태를 선택하면 취소·만료된 신청도 확인할 수 있습니다.'}</p>
          {hasFilters ? <button type="button" onClick={resetFilters}>검색 조건 초기화</button> : null}</div> : null}
      {data && data.totalPages > 1 ? <ListPagination page={data.page} totalPages={data.totalPages} disabled={pending} label="알림 신청 목록 페이지"
        onMove={next => setParams(current => { current.set(pageKey, String(next)); return current })} /> : null}
    </div>
    </div>
  </section>
}
