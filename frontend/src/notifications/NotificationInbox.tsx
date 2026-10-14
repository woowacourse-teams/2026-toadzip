import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { useNotificationInbox } from './NotificationInboxContext'
import { NotificationApiError, type NotificationItem, type NotificationPageResult } from './notificationRepository'
import styles from './NotificationInbox.module.css'

const empty: NotificationPageResult = { items: [], nextCursor: null, readThrough: '0' }
export function NotificationInbox({ onNavigate }: { readonly onNavigate?: () => void }) {
  const context = useNotificationInbox()
  const userId = context?.userId ?? null
  const repository = context?.repository
  const sessionExpired = context?.sessionExpired
  const revision = context?.revision
  const count = context?.unreadCount
  const navigate = useNavigate()
  const [state, setState] = useState({ userId, page: empty, loading: true, error: '' })
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState('')
  const [failedItem, setFailedItem] = useState<NotificationItem | null>(null)
  const [retry, setRetry] = useState(0)
  const generation = useRef(0)
  const controller = useRef<AbortController | null>(null)
  const requestId = useRef(0)
  const page = state.userId === userId ? state.page : empty
  const loading = state.userId !== userId || state.loading
  const loadPage = useCallback(async (cursor: string | null, signal: AbortSignal) => {
    if (!repository || !userId) return
    const request = ++requestId.current
    setState(current => ({ userId, page: current.userId === userId ? current.page : empty, loading: true, error: '' }))
    try {
      const result = await repository.list(cursor, signal)
      if (signal.aborted || request !== requestId.current) return
      setState(current => ({ userId, loading: false, error: '', page: cursor ? {
        items: [...current.page.items, ...result.items.filter(item => !current.page.items.some(existing => existing.id === item.id))],
        nextCursor: result.nextCursor, readThrough: current.page.readThrough,
      } : result }))
    } catch (error) {
      if (signal.aborted || request !== requestId.current) return
      if (error instanceof NotificationApiError && error.status === 401) sessionExpired?.()
      setState(current => ({ ...current, loading: false, error: error instanceof NotificationApiError && error.status === 400 ? '목록이 변경됐어요. 처음부터 다시 확인해 주세요.' : '알림을 불러오지 못했어요.' }))
    }
  }, [repository, userId, sessionExpired])
  useEffect(() => {
    const currentGeneration = generation
    currentGeneration.current++
    const abort = new AbortController()
    controller.current = abort
    setBusy(false); setActionError(''); setFailedItem(null)
    return () => { currentGeneration.current++; abort.abort() }
  }, [userId])
  useEffect(() => {
    const abort = new AbortController()
    void loadPage(null, abort.signal)
    return () => { abort.abort() }
  }, [loadPage, revision, count, retry])
  async function read(item?: NotificationItem) {
    const signal = controller.current?.signal
    if (!repository || !signal || busy) return
    const version = generation.current
    setBusy(true); setActionError(''); setFailedItem(null)
    try {
      if (item) await repository.read(item.id, signal)
      else await repository.readAll(page.readThrough, signal)
      if (signal.aborted || version !== generation.current) return
      const now = new Date().toISOString()
      setState(current => ({ ...current, page: { ...current.page, items: current.page.items.map(value =>
        (!item && BigInt(value.id) <= BigInt(page.readThrough)) || value.id === item?.id ? { ...value, readAt: value.readAt ?? now } : value) } }))
      context?.invalidate()
      if (item?.targetAvailable) { onNavigate?.(); void navigate(`/explore?announcementId=${item.announcementId}`) }
    } catch (error) {
      if (signal.aborted || version !== generation.current) return
      if (error instanceof NotificationApiError && error.status === 401) sessionExpired?.()
      setActionError('읽음 처리하지 못했어요. 다시 시도해 주세요.'); setFailedItem(item ?? null)
    } finally { if (version === generation.current) setBusy(false) }
  }
  if (!userId) return <p>로그인한 사용자만 이용할 수 있어요.</p>
  return <section className={`${styles.inbox} ph-no-capture`} aria-label="받은 알림 목록">
    <div className={styles.toolbar}><p>알림은 90일 동안 보관해요.</p><button type="button" disabled={busy || loading || !page.items.some(item => !item.readAt)} onClick={() => void read()}>모두 읽음</button></div>
    {actionError && <p role="alert">{actionError} {failedItem?.targetAvailable && <Link to={`/explore?announcementId=${failedItem.announcementId}`} onClick={onNavigate}>공고만 보기</Link>}</p>}
    {state.error && <p role="alert">{state.error} <button type="button" onClick={() => setRetry(value => value + 1)}>다시 시도</button></p>}
    {!loading && !state.error && !page.items.length && <div className={styles.empty}><p>아직 받은 알림이 없어요.</p><p>관심 단지·지역의 새 공고가 도착하면 알려 드릴게요.</p></div>}
    <ul className={styles.list}>{page.items.map(item => <li key={item.id} className={item.readAt ? styles.read : styles.unread}>
      <button type="button" disabled={busy} onClick={() => void read(item)}>
        {!item.readAt && <span className={styles.unreadLabel}>읽지 않음</span>}
        <strong>{item.title}</strong><time dateTime={item.createdAt}>{new Date(item.createdAt).toLocaleDateString('ko-KR')}</time>
        {!item.targetAvailable && <span>현재 확인할 수 없는 공고예요.</span>}
      </button>
    </li>)}</ul>
    {loading && <p role="status">알림을 불러오는 중…</p>}
    {page.nextCursor && <button type="button" disabled={loading} onClick={() => { const signal = controller.current?.signal; if (signal) void loadPage(page.nextCursor, signal) }}>더 보기</button>}
  </section>
}
