import { useMyPageReturnState } from '../user/mypage/mypageNavigation'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { Button } from '../design-system/components/Button'
import { buttonClassName } from '../design-system/components/buttonClassName'
import { useNotificationInbox } from './NotificationInboxContext'
import { NotificationDialog } from './NotificationDialog'
import { NotificationScheduleDetails } from './NotificationScheduleDetails'
import { NotificationApiError, type NotificationItem, type NotificationPageResult, type NotificationSchedule } from './notificationRepository'
import styles from './NotificationInbox.module.css'
import { captureCampaignStat } from '../analytics/campaignStats'
import { announcementPath } from '../public-housing/navigation/detailLocation'

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
  const returnState = useMyPageReturnState()
  const [state, setState] = useState({ userId, page: empty, loading: true, error: '' })
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState('')
  const [message, setMessage] = useState('')
  const [failedItem, setFailedItem] = useState<NotificationItem | null>(null)
  const [retry, setRetry] = useState(0)
  const [selection, setSelection] = useState<EditSelection | null>(null)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [openGroups, setOpenGroups] = useState<ReadonlySet<string>>(new Set())
  const generation = useRef(0)
  const controller = useRef<AbortController | null>(null)
  const requestId = useRef(0)
  const allCheckbox = useRef<HTMLInputElement>(null)
  const countedView = useRef(false)
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
  useEffect(() => {
    if (!userId || loading || state.error || countedView.current) return
    let cancelled = false
    queueMicrotask(() => {
      if (!cancelled && !countedView.current) countedView.current = captureCampaignStat('notification_inbox_viewed')
    })
    return () => { cancelled = true }
  }, [userId, loading, state.error])
  async function read(item?: NotificationItem) {
    const signal = controller.current?.signal
    if (!repository || !signal || busy || edit) return
    const version = generation.current
    setBusy(true); setActionError(''); setFailedItem(null); setMessage('')
    try {
      // 공고로 이동하면 같은 공고의 안 읽은 알림도 함께 본 것으로 처리한다.
      const ids = item ? [item.id, ...page.items.filter(value => value.announcementId === item.announcementId && !value.readAt && value.id !== item.id).map(value => value.id)] : []
      if (item) await Promise.all(ids.map(id => repository.read(id, signal)))
      else await repository.readAll(page.readThrough, signal)
      if (signal.aborted || version !== generation.current) return
      if (item) captureCampaignStat('notification_opened')
      const now = new Date().toISOString()
      setState(current => ({ ...current, page: { ...current.page, items: current.page.items.map(value =>
        (!item && BigInt(value.id) <= BigInt(page.readThrough)) || ids.includes(value.id) ? { ...value, readAt: value.readAt ?? now } : value) } }))
      context?.invalidate()
      if (item?.targetAvailable) { onNavigate?.(); void navigate(announcementPath(String(item.announcementId)), { state: returnState }) }
    } catch (error) {
      if (signal.aborted || version !== generation.current) return
      if (error instanceof NotificationApiError && error.status === 401) sessionExpired?.()
      setActionError('읽음 처리하지 못했어요. 다시 시도해 주세요.'); setFailedItem(item ?? null)
    } finally { if (version === generation.current) setBusy(false) }
  }
  function isSelected(id: string) { return !!edit && (edit.all ? !edit.ids.has(id) : edit.ids.has(id)) }
  /** 전체 선택 중이면 ids는 뺀 알림, 아니면 고른 알림이다. */
  function setSelected(targets: readonly string[], selected: boolean) {
    if (!edit || busy) return
    const ids = new Set(edit.ids)
    for (const id of targets) { if (selected !== edit.all) ids.add(id); else ids.delete(id) }
    if (!edit.all && ids.size > 1000) { setActionError('개별 선택은 한 번에 1,000개까지 가능해요. 전체 선택도 사용할 수 있어요.'); return }
    setActionError(''); setSelection({ ...edit, ids })
  }
  function toggleItem(id: string) { setSelected([id], !isSelected(id)) }
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
  function renderItem(item: NotificationItem) {
    return <li key={item.id} data-notification-id={item.id} className={item.readAt ? styles.read : styles.unread}>
      {edit ? <label className={styles.selectRow}>
        <input type="checkbox" aria-label={`${item.title} 선택`} checked={isSelected(item.id)} disabled={busy} onChange={() => toggleItem(item.id)} />
        <span className={styles.content}><NotificationContent item={item} /></span>
      </label> : <button className={styles.itemButton} type="button" disabled={busy} onClick={() => void read(item)}><NotificationContent item={item} /></button>}
      {item.content?.schedule && <NotificationScheduleDetails schedule={item.content.schedule} disabled={busy}
        onOpen={item.targetAvailable && !edit ? () => void read(item) : undefined} />}
    </li>
  }
  // 묶음 안에서는 대표 줄이 공고명과 일정 라벨을 이미 보여 주므로 알림 내용만 둔다.
  function renderNested(item: NotificationItem) {
    // 편집 중에는 이동하지 않고 고르기만 한다.
    const open = item.targetAvailable && !edit ? () => void read(item) : undefined
    const details = item.content?.schedule
      ? <NotificationScheduleDetails schedule={item.content.schedule} disabled={busy} onOpen={open} />
      : <div className={styles.scheduleDetails}><div className={styles.scheduleHead}>
        <p className={styles.scheduleName}>{item.content?.message || item.title}</p>
        {open && <button type="button" className={`${buttonClassName({ variant: 'secondary', size: 'sm' })} ${styles.sourceButton}`} disabled={busy} onClick={open}>공고 보기</button>}
      </div><time className={styles.nestedTime} dateTime={item.createdAt}>{new Date(item.createdAt).toLocaleDateString('ko-KR')}</time></div>
    const name = item.content?.schedule ? scheduleLabel(item.content.schedule) : item.content?.message || item.title
    return <li key={item.id} data-notification-id={item.id} className={item.readAt ? styles.nestedRead : styles.nestedUnread}>
      {!item.readAt && <span className={styles.srOnly}>읽지 않음</span>}
      {edit ? <label className={styles.nestedSelect}>
        <input type="checkbox" aria-label={`${name} 선택`} checked={isSelected(item.id)} disabled={busy} onChange={() => toggleItem(item.id)} />
        <span className={styles.content}>{details}</span>
      </label> : details}
    </li>
  }
  function groupSelection(group: readonly NotificationItem[]): GroupSelection | undefined {
    if (!edit) return undefined
    const count = group.filter(item => isSelected(item.id)).length
    return { state: count === group.length ? 'all' : count ? 'some' : 'none', disabled: busy, onChange: () => setSelected(group.map(item => item.id), count !== group.length) }
  }
  function finishEditing() { if (!busy) { setSelection(null); setConfirmDelete(false); setActionError('') } }
  if (!userId) return <p>로그인한 사용자만 이용할 수 있어요.</p>
  return <section className={`${styles.inbox} ph-no-capture`} aria-label="받은 알림 목록">
    <div className={edit ? styles.editHeader : undefined}>
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
      <button className={styles.deleteButton} type="button" disabled={busy || !selectedCount} onClick={() => { setActionError(''); setConfirmDelete(true) }}>{allSelected ? '전체 삭제' : '삭제'}</button>
      {edit.all && <p>모든 페이지의 알림을 선택했어요. 편집 중 도착한 새 알림은 남겨요.</p>}
    </div>}
    </div>
    {message && <p className={styles.feedback} role="status">{message}</p>}
    {actionError && !confirmDelete && <p className={styles.feedback} role="alert">{actionError} {failedItem?.targetAvailable && <Link state={returnState} to={announcementPath(String(failedItem.announcementId))} onClick={onNavigate}>공고만 보기</Link>}</p>}
    {state.error && <p role="alert">{state.error} <button className={styles.textButton} type="button" onClick={() => { finishEditing(); setRetry(value => value + 1) }}>다시 시도</button></p>}
    {!loading && !state.error && !page.items.length && <div className={styles.empty}><p>아직 받은 알림이 없어요.</p><p>관심 단지·지역의 새 공고와 신청한 공고 일정을 알려 드릴게요.</p></div>}
    <ul className={styles.list}>{groupByAnnouncement(page.items).map(group => group.length === 1 ? renderItem(group[0])
      : <NotificationGroup key={`group-${group[0].announcementId}`} items={group} open={openGroups.has(group[0].announcementId)} renderItem={renderNested} selection={groupSelection(group)}
        onToggle={() => setOpenGroups(current => { const next = new Set(current); if (next.has(group[0].announcementId)) next.delete(group[0].announcementId); else next.add(group[0].announcementId); return next })} />)}</ul>
    {loading && <p role="status">알림을 불러오는 중…</p>}
    {page.nextCursor && <button className={styles.moreButton} type="button" disabled={loading || busy} onClick={() => { const signal = controller.current?.signal; if (signal) void loadPage(page.nextCursor, signal) }}>더 보기</button>}
    {confirmDelete && edit && <NotificationDialog title={`알림 ${selectedCount.toLocaleString()}개를 삭제할까요?`} onClose={() => { if (!busy) { setConfirmDelete(false); setActionError('') } }}>
      <p>삭제한 알림은 다시 볼 수 없어요. 관심 단지·지역의 알림 설정은 그대로 유지돼요.</p>
      {actionError && <p role="alert">{actionError}</p>}
      <div className={styles.dialogActions}><button className={styles.textButton} type="button" disabled={busy} onClick={() => { setConfirmDelete(false); setActionError('') }}>취소</button><Button disabled={busy} onClick={() => void remove()}>{busy ? '삭제 중…' : '삭제'}</Button></div>
    </NotificationDialog>}
  </section>
}
/** 받은 순서(최신 먼저)를 유지하며 같은 공고의 알림을 하나로 묶는다. */
function groupByAnnouncement(items: readonly NotificationItem[]): NotificationItem[][] {
  const groups = new Map<string, NotificationItem[]>()
  for (const item of items) groups.set(item.announcementId, [...(groups.get(item.announcementId) ?? []), item])
  return [...groups.values()]
}

interface GroupSelection { readonly state: 'all' | 'some' | 'none'; readonly disabled: boolean; readonly onChange: () => void }

/** 가장 최근 알림을 대표로 보여 주고, 펼치면 그 공고의 알림을 최신 알림부터 보여 준다. */
function NotificationGroup({ items, open, onToggle, renderItem, selection }: {
  readonly items: readonly NotificationItem[]
  readonly open: boolean
  readonly onToggle: () => void
  readonly renderItem: (item: NotificationItem) => React.ReactNode
  /** 편집 중일 때만 묶음 전체를 고르는 체크박스를 둔다. */
  readonly selection?: GroupSelection
}) {
  const latest = items[0]
  const unread = items.filter(item => !item.readAt).length
  // 최신 알림이 위로 와서 안 읽은 알림이 먼저 보인다.
  const timeline = [...items].sort((left, right) => Date.parse(right.createdAt) - Date.parse(left.createdAt) || (BigInt(left.id) < BigInt(right.id) ? 1 : -1))
  const header = <button className={styles.groupHeader} type="button" aria-expanded={open} onClick={onToggle}
      aria-label={`${latest.title} 알림 ${items.length}개 ${open ? '접기' : '펼치기'}`}>
      <span className={styles.content}><NotificationContent item={latest} /></span>
      <span className={styles.groupMeta}>
        {unread > 0 && <b className={styles.groupUnread}>안 읽음 {unread}</b>}
        <span className={styles.groupCount}>알림 {items.length}개<svg className={styles.chevron} data-open={open || undefined} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="m4 6 4 4 4-4" /></svg></span>
      </span>
    </button>
  return <li className={`${styles.group} ${unread ? styles.unread : styles.read}`}>
    {selection ? <div className={styles.groupRow}>
      <input type="checkbox" className={styles.groupCheck} aria-label={`${latest.title} 알림 ${items.length}개 선택`} checked={selection.state === 'all'} disabled={selection.disabled} onChange={selection.onChange}
        ref={box => { if (box) box.indeterminate = selection.state === 'some' }} />
      {header}
    </div> : header}
    {open && <ul className={styles.groupItems} aria-label={`${latest.title} 알림`}>{timeline.map(renderItem)}</ul>}
  </li>
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
