import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { ListPagination } from '../management/ListPagination'
import { workDetailUrl } from '../shared/workNavigation'
import { deliveryStates, listDeliveries, type DeliveryPage } from './deliveryApi'
import styles from './DeliveryPage.module.css'

const at = (value: string | null) => value ? new Date(value).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' }) : '—'
export function NotificationDeliveriesPage() {
  const [params, setParams] = useSearchParams()
  const [loaded, setLoaded] = useState<{ query: string; data: DeliveryPage } | null>(null)
  const [failure, setFailure] = useState<{ query: string; message: string } | null>(null)
  const [attempt, setAttempt] = useState(0)
  const page = Number(params.get('page') ?? '0')
  const status = params.get('status') ?? ''
  const keyword = params.get('keyword') ?? ''
  const userId = params.get('userId'), announcementId = params.get('announcementId')
  const query = `${params}#${attempt}`
  const validId = (id: string | null) => id === null || /^\d+$/.test(id) && Number.isSafeInteger(Number(id)) && Number(id) > 0
  const valid = Number.isInteger(page) && page >= 0 && page <= 2147483647 && keyword.length <= 200
    && (!status || Object.hasOwn(deliveryStates, status)) && validId(userId) && validId(announcementId)
  const data = loaded?.query === query ? loaded.data : null
  const error = !valid ? '검색 조건을 확인해 주세요.' : failure?.query === query ? failure.message : ''
  useEffect(() => {
    if (!valid) return
    const controller = new AbortController()
    const request = new URLSearchParams({ page: String(page), size: '20', status, keyword })
    if (userId) request.set('userId', userId)
    if (announcementId) request.set('announcementId', announcementId)
    void listDeliveries(request, controller.signal).then(data => {
      if (controller.signal.aborted) return
      if (page >= data.totalPages && page > 0) {
        setParams(current => { current.set('page', String(Math.max(0, data.totalPages - 1))); return current }, { replace: true })
      } else setLoaded({ query, data })
    }).catch(cause => { if (!controller.signal.aborted) setFailure({ query, message: cause instanceof Error ? cause.message : '발송 기록을 불러오지 못했습니다.' }) })
    return () => controller.abort()
  }, [query, valid, page, status, keyword, userId, announcementId, setParams])
  return <section className={styles.page}>
    <header><h1>알림 발송 기록</h1><p>회원별 알림 생성, 기기별 발송 시도, 열람 여부를 확인합니다.</p></header>
    {userId && <p><Link to={`/admin/users/${userId}`}>회원 #{userId} 정보·알림 신청</Link>의 발송 기록</p>}
    {announcementId && <p><Link to={workDetailUrl('ANNOUNCEMENT', Number(announcementId), `/admin/notification-deliveries?${params}`, 'info')}>공고 #{announcementId}</Link>의 발송 기록</p>}
    <form key={params.toString()} className={styles.filters} onSubmit={event => {
      event.preventDefault(); const form = new FormData(event.currentTarget)
      const next = new URLSearchParams(params); next.delete('page')
      for (const key of ['keyword', 'status']) { const value = String(form.get(key) ?? '').trim(); if (value) next.set(key, value); else next.delete(key) }
      if (next.toString() === params.toString()) setAttempt(value => value + 1); else setParams(next)
    }}>
      <label>공고 제목<input name="keyword" maxLength={200} defaultValue={keyword} /></label>
      <label>발송 상태<select name="status" defaultValue={status}><option value="">전체</option>{Object.entries(deliveryStates).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select></label>
      <button className="admin-primary">검색</button><button type="button" onClick={() => setAttempt(value => value + 1)}>새로고침</button>
      {(userId || announcementId) && <Link to="/admin/notification-deliveries">전체 발송 보기</Link>}
    </form>
    <p className={styles.help}>‘푸시 제공자 접수’는 기기 표시나 사용자의 열람을 보장하지 않습니다. 발송 기록이 없는 알림도 앱 내 알림함에 생성될 수 있습니다. 재시도 대기 중인 건은 다음 예정 시각에 자동 처리됩니다.</p>
    {error ? <p role="alert">{error}</p> : !data ? <p role="status">발송 기록을 불러오는 중…</p> : <>
      <p>총 {data.totalElements.toLocaleString('ko-KR')}건 · 기기별 발송 시도 기준 · 한국 시각</p>
      {!data.items.length ? <p>조건에 맞는 발송 기록이 없습니다. 알림 신청 조건과 생성 시각을 함께 확인해 주세요.</p> : <ol className={styles.list}>
        {data.items.map(item => <li key={`${item.notificationId}:${item.deliveryId}`}><article>
          <header><h2><Link to={workDetailUrl('ANNOUNCEMENT', item.announcementId, `/admin/notification-deliveries?${params}`, 'info')}>{item.title}</Link></h2><strong>{deliveryStates[item.deliveryStatus]}</strong></header>
          <p><Link to={`/admin/users/${item.userId}`}>회원 #{item.userId}</Link> · 알림 #{item.notificationId}{item.deliveryId ? ` · 발송 #${item.deliveryId}` : ''}</p>
          <dl><div><dt>알림 생성</dt><dd>{at(item.createdAt)}</dd></div><div><dt>시도 횟수</dt><dd>{item.attempts}회</dd></div>
            <div><dt>다음 시도 예정</dt><dd>{['PENDING', 'RETRY'].includes(item.deliveryStatus) ? at(item.nextAttemptAt) : '—'}</dd></div>
            <div><dt>발송 처리 종료</dt><dd>{at(item.finishedAt)}</dd></div><div><dt>회원 열람</dt><dd>{item.readAt ? at(item.readAt) : '미열람'}</dd></div>
            <div><dt>알림함 상태</dt><dd>{item.deleted ? '회원 삭제' : Date.parse(item.expiresAt) <= Date.now() ? '보관 기한 만료' : '보관 중'}</dd></div></dl>
          {item.errorCode && <p>실패·제외 사유: <code>{item.errorCode}</code></p>}
        </article></li>)}
      </ol>}
      <ListPagination page={data.page} totalPages={data.totalPages} label="알림 발송 기록 페이지" onMove={next => setParams(current => { current.set('page', String(next)); return current })} />
    </>}
  </section>
}
