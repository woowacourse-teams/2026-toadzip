import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { Button } from '../design-system/components/Button'
import { useNotificationInbox } from './NotificationInboxContext'
import { NotificationDialog } from './NotificationDialog'
import { NotificationScheduleDetails } from './NotificationScheduleDetails'
import { NotificationApiError, type NotificationItem, type NotificationPageResult, type NotificationSchedule } from './notificationRepository'
import styles from './NotificationInbox.module.css'

const empty: NotificationPageResult = { items: [], nextCursor: null, readThrough: '0', totalCount: 0 }
interface EditSelection { readonly userId: string; readonly bound: string; readonly total: number; readonly all: boolean; readonly ids: ReadonlySet<string> }
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
  const [message, setMessage] = useState('')
  const [failedItem, setFailedItem] = useState<NotificationItem | null>(null)
  const [retry, setRetry] = useState(0)
  const [selection, setSelection] = useState<EditSelection | null>(null)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const generation = useRef(0)
  const controller = useRef<AbortController | null>(null)
  const requestId = useRef(0)
  const allCheckbox = useRef<HTMLInputElement>(null)
  const page = state.userId === userId ? state.page : empty
  const loading = state.userId !== userId || state.loading
  const edit = selection?.userId === userId ? selection : null
  const editBound = edit?.bound
  const selectedCount = edit ? edit.all ? edit.total - edit.ids.size : edit.ids.size : 0
  const allSelected = !!edit?.all && edit.ids.size === 0
  const loadPage = useCallback(async (cursor: string | null, signal: AbortSignal) => {
    if (!repository || !userId) return
    const request = ++requestId.current
    setState(current => ({ userId, page: current.userId === userId ? current.page : empty, loading: true, error: '' }))
    try {
      const result = await repository.list(cursor, signal)
      if (signal.aborted || request !== requestId.current) return
      setState(current => ({ userId, loading: false, error: '', page: cursor ? {
        ...current.page,
        items: [...current.page.items, ...result.items.filter(item => (!editBound || BigInt(item.id) <= BigInt(editBound)) && !current.page.items.some(existing => existing.id === item.id))],
        nextCursor: result.nextCursor,
      } : result }))
    } catch (error) {
      if (signal.aborted || request !== requestId.current) return
      if (error instanceof NotificationApiError && error.status === 401) sessionExpired?.()
      setState(current => ({ ...current, loading: false, error: error instanceof NotificationApiError && error.status === 400 ? '목록이 변경됐어요. 편집을 마치고 다시 확인해 주세요.' : '알림을 불러오지 못했어요.' }))
    }
  }, [repository, userId, sessionExpired, editBound])
  useEffect(() => {
    const currentGeneration = generation
    currentGeneration.current++
    const abort = new AbortController()
    controller.current = abort
    setBusy(false); setActionError(''); setFailedItem(null); setSelection(null); setConfirmDelete(false); setMessage('')
    return () => { currentGeneration.current++; abort.abort() }
  }, [userId])
  useEffect(() => {
    if (editBound) return
    const abort = new AbortController()
    void loadPage(null, abort.signal)
    return () => { abort.abort() }
  }, [loadPage, revision, count, retry, editBound])
  useEffect(() => { if (allCheckbox.current) allCheckbox.current.indeterminate = selectedCount > 0 && !allSelected }, [selectedCount, allSelected])
  async function read(item?: NotificationItem) {
    const signal = controller.current?.signal
    if (!repository || !signal || busy || edit) return
    const version = generation.current
    setBusy(true); setActionError(''); setFailedItem(null); setMessage('')
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
  function toggleItem(id: string) {
    if (!edit || busy) return
    const ids = new Set(edit.ids)
    if (ids.has(id)) ids.delete(id)
    else {
      if (ids.size >= 1000) { setActionError('개별 선택은 한 번에 1,000개까지 가능해요. 전체 선택도 사용할 수 있어요.'); return }
      ids.add(id)
    }
    setActionError(''); setSelection({ ...edit, ids })
  }
  async function remove() {
    const signal = controller.current?.signal
    if (!repository || !signal || !edit || busy || !selectedCount) return
    const version = generation.current
    setBusy(true); setActionError('')
    try {
      await repository.delete(edit.all ? { deleteThrough: edit.bound, excludedIds: [...edit.ids] } : { ids: [...edit.ids] }, signal)
      if (signal.aborted || version !== generation.current) return
      setConfirmDelete(false); setSelection(null); setMessage('선택한 알림을 삭제했어요.')
      setState(current => ({ ...current, page: { ...current.page, items: current.page.items.filter(item => edit.all ? edit.ids.has(item.id) || BigInt(item.id) > BigInt(edit.bound) : !edit.ids.has(item.id)) } }))
      context?.invalidate()
    } catch (error) {
      if (signal.aborted || version !== generation.current) return
      if (error instanceof NotificationApiError && error.status === 401) sessionExpired?.()
      setActionError('알림을 삭제하지 못했어요. 다시 시도해 주세요.')
    } finally { if (version === generation.current) setBusy(false) }
  }
  function finishEditing() { if (!busy) { setSelection(null); setConfirmDelete(false); setActionError('') } }
  if (!userId) return <p>로그인한 사용자만 이용할 수 있어요.</p>
  return <section className={`${styles.inbox} ph-no-capture`} aria-label="받은 알림 목록">
    <div className={styles.toolbar}>
      <p>{edit ? `${selectedCount.toLocaleString()}개 선택` : '90일 동안 보관해요.'}</p>
      <div className={styles.toolbarActions}>{edit
        ? <button className={styles.textButton} type="button" disabled={busy} onClick={finishEditing}>편집 마치기</button>
        : <><button className={styles.textButton} type="button" disabled={busy || loading || !page.items.some(item => !item.readAt)} onClick={() => void read()}>모두 읽음</button>
          <button className={styles.textButton} type="button" disabled={busy || loading || !page.items.length} onClick={() => { setActionError(''); setMessage(''); setSelection({ userId, bound: page.readThrough, total: page.totalCount, all: false, ids: new Set() }) }}>편집</button></>}
      </div>
    </div>
    {edit && <div className={styles.editToolbar}>
      <label className={styles.selectAll}><input ref={allCheckbox} type="checkbox" checked={allSelected} disabled={busy} onChange={event => setSelection({ ...edit, all: event.target.checked, ids: new Set() })} />전체 선택</label>
      <button className={styles.deleteButton} type="button" disabled={busy || !selectedCount} onClick={() => { setActionError(''); setConfirmDelete(true) }}>선택 삭제</button>
      {edit.all && <p>모든 페이지의 알림을 선택했어요. 편집 중 도착한 새 알림은 남겨요.</p>}
    </div>}
    {message && <p className={styles.feedback} role="status">{message}</p>}
    {actionError && !confirmDelete && <p className={styles.feedback} role="alert">{actionError} {failedItem?.targetAvailable && <Link to={`/explore?announcementId=${failedItem.announcementId}`} onClick={onNavigate}>공고만 보기</Link>}</p>}
    {state.error && <p role="alert">{state.error} <button className={styles.textButton} type="button" onClick={() => { finishEditing(); setRetry(value => value + 1) }}>다시 시도</button></p>}
    {!loading && !state.error && !page.items.length && <div className={styles.empty}><p>아직 받은 알림이 없어요.</p><p>관심 단지·지역의 새 공고와 신청한 공고 일정을 알려 드릴게요.</p></div>}
    <ul className={styles.list}>{page.items.map(item => <li key={item.id} className={item.readAt ? styles.read : styles.unread}>
      {edit ? <label className={styles.selectRow}>
        <input type="checkbox" aria-label={`${item.title} 선택`} checked={edit.all ? !edit.ids.has(item.id) : edit.ids.has(item.id)} disabled={busy} onChange={() => toggleItem(item.id)} />
        <span className={styles.content}><NotificationContent item={item} /></span>
      </label> : <button className={styles.itemButton} type="button" disabled={busy} onClick={() => void read(item)}><NotificationContent item={item} /></button>}
      {item.content?.schedule && <NotificationScheduleDetails schedule={item.content.schedule} />}
    </li>)}</ul>
    {loading && <p role="status">알림을 불러오는 중…</p>}
    {page.nextCursor && <button className={styles.moreButton} type="button" disabled={loading || busy} onClick={() => { const signal = controller.current?.signal; if (signal) void loadPage(page.nextCursor, signal) }}>더 보기</button>}
    {confirmDelete && edit && <NotificationDialog title={`알림 ${selectedCount.toLocaleString()}개를 삭제할까요?`} onClose={() => { if (!busy) { setConfirmDelete(false); setActionError('') } }}>
      <p>삭제한 알림은 다시 볼 수 없어요. 관심 단지·지역의 알림 설정은 그대로 유지돼요.</p>
      {actionError && <p role="alert">{actionError}</p>}
      <div className={styles.dialogActions}><button className={styles.textButton} type="button" disabled={busy} onClick={() => { setConfirmDelete(false); setActionError('') }}>취소</button><Button disabled={busy} onClick={() => void remove()}>{busy ? '삭제 중…' : '삭제'}</Button></div>
    </NotificationDialog>}
  </section>
}
function NotificationContent({ item }: { readonly item: NotificationItem }) {
  return <>
    {!item.readAt && <span className={styles.unreadLabel}>읽지 않음</span>}
    {item.content?.schedule && <span className={styles.scheduleLabel}>{scheduleLabel(item.content.schedule)}</span>}
    <strong>{item.title}</strong>
    {item.content?.message && !item.content.schedule && <span className={styles.announcementMessage}>{item.content.message}</span>}
    <time dateTime={item.createdAt}>{new Date(item.createdAt).toLocaleDateString('ko-KR')}</time>
    {!item.targetAvailable && <span className={styles.unavailable}>현재 확인할 수 없는 공고예요.</span>}
  </>
}

const kinds = { APPLICATION: '접수', DOCUMENT_SUBMISSION: '서류 제출', WINNER_ANNOUNCEMENT: '당첨자 발표', CONTRACT: '계약' }
function scheduleLabel(schedule: NotificationSchedule) {
  const phase = schedule.kind === 'WINNER_ANNOUNCEMENT' ? '예정' : schedule.phase === 'END' ? '마감 안내' : schedule.phase === 'START_END' ? '당일 일정' : '시작 안내'
  return `${kinds[schedule.kind]} · ${phase}`
}
