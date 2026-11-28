import { useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router'
import { Button } from '../../design-system/components/Button'
import { ListPagination } from '../management/ListPagination'
import { AdminIcon } from '../shared/AdminIcon'
import { feedbackStates, formatShortTime, getFeedbackCounts, listFeedback, relatedLabel,
  type FeedbackCounts, type FeedbackEntry, type FeedbackPage, type FeedbackState } from './api'
import { FeedbackProcessingPanel } from './FeedbackProcessingPanel'
import { FeedbackStatusBadge } from './FeedbackStatusBadge'
import styles from './FeedbackListPage.module.css'

const tabs: { value: FeedbackState | 'ALL'; label: string }[] = [
  ...(Object.entries(feedbackStates) as [FeedbackState, string][]).map(([value, label]) => ({ value, label })),
  { value: 'ALL', label: '전체' },
]

function emptyMessage(tab: FeedbackState | 'ALL') {
  return tab === 'OPEN' ? '새로 접수된 의견이 없습니다.' : tab === 'ALL' ? '접수된 의견이 없습니다.' : '이 상태의 의견이 없습니다.'
}
function relatedText(entry: FeedbackEntry) {
  return entry.relatedResource && entry.relatedId ? relatedLabel(entry.relatedResource, entry.relatedId) : null
}

function withSelection(previous: URLSearchParams, id: number | null) {
  const next = new URLSearchParams(previous)
  if (id) next.set('feedback', String(id)); else next.delete('feedback')
  return next
}

export function FeedbackListPage() {
  const [params, setParams] = useSearchParams()
  const [loaded, setLoaded] = useState<{ query: string; data: FeedbackPage } | null>(null)
  const [failure, setFailure] = useState<{ query: string; message: string } | null>(null)
  const [attempt, setAttempt] = useState(0)
  // 저장 뒤 다시 불러올 때는 현재 목록을 그대로 두고 응답이 오면 바꾼다.
  const [reload, setReload] = useState(0)
  const [counts, setCounts] = useState<{ key: string; value: FeedbackCounts } | null>(null)
  const [countsFailure, setCountsFailure] = useState<string | null>(null)
  const [countsAttempt, setCountsAttempt] = useState(0)
  // setParams는 주소가 바뀔 때마다 새로 만들어지므로, 의견을 고를 때마다 목록을 다시 받지 않게 ref로 읽는다.
  const setParamsRef = useRef(setParams)
  useEffect(() => { setParamsRef.current = setParams })
  const afterSave = useRef<{ id: number; index: number } | null>(null)
  const keyword = params.get('keyword') ?? ''
  const statusParam = params.get('status')
  const tab = (statusParam ?? 'OPEN') as FeedbackState | 'ALL'
  const selected = Number(params.get('feedback')) || null
  const pageValue = params.get('page') ?? '0'
  const page = Number(pageValue)
  const valid = (statusParam === null || statusParam === 'ALL' || Object.hasOwn(feedbackStates, statusParam))
    && keyword.length <= 200 && /^\d+$/.test(pageValue) && Number.isSafeInteger(page) && page <= 2147483647
  const listParams = new URLSearchParams(params); listParams.delete('feedback')
  const query = `${listParams}#${attempt}`
  const term = keyword.trim()
  const data = valid && loaded?.query === query ? loaded.data : null
  const error = valid ? failure?.query === query ? failure.message : '' : '검색 조건을 확인해 주세요.'
  const pending = valid && loaded?.query !== query && failure?.query !== query
  // 같은 검색어로 다시 받는 동안에는 이전 건수를 그대로 보여 탭 줄이 흔들리지 않게 한다.
  const countsKey = term
  const tabCounts = valid && counts?.key === countsKey ? counts.value : null
  const current = data?.items.find(item => item.id === selected) ?? null

  // 저장한 의견이 탭에서 빠져 다음 의견으로 넘어가도 저장했다는 사실은 남겨 둔다. 운영자가 다른 곳으로 옮기면 지운다.
  const [savedId, setSavedId] = useState<number | null>(null)
  function select(id: number | null) { setSavedId(null); setParams(next => withSelection(next, id), { replace: true }) }

  useEffect(() => {
    if (!valid) return
    const controller = new AbortController()
    const request = new URLSearchParams({ page: String(page), size: '20' })
    if (term) request.set('keyword', term)
    if (tab !== 'ALL') request.set('status', tab)
    void listFeedback(request, controller.signal).then(result => {
      if (controller.signal.aborted) return
      const last = Math.max(0, result.totalPages - 1)
      if (page > last) {
        setParamsRef.current(next => { const copy = new URLSearchParams(next); copy.set('page', String(last)); return copy }, { replace: true })
        return
      }
      const saved = afterSave.current; afterSave.current = null
      if (saved && !result.items.some(item => item.id === saved.id)) {
        const next = result.items[saved.index] ?? result.items[0]
        setParamsRef.current(previous => withSelection(previous, next?.id ?? null), { replace: true })
      }
      setLoaded({ query, data: result })
    }).catch(cause => {
      if (!controller.signal.aborted) {
        const message = cause instanceof Error && !(cause instanceof TypeError)
          ? cause.message : '의견 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'
        setFailure({ query, message })
      }
    })
    return () => controller.abort()
  }, [valid, page, term, tab, query, reload])

  useEffect(() => {
    if (!valid || keyword.length > 200) return
    const controller = new AbortController()
    getFeedbackCounts(term, controller.signal).then(value => {
      if (controller.signal.aborted) return
      setCounts({ key: countsKey, value }); setCountsFailure(null)
    }).catch(cause => {
      if (!controller.signal.aborted) setCountsFailure(cause instanceof Error && !(cause instanceof TypeError)
        ? cause.message : '의견 건수를 불러오지 못했습니다.')
    })
    return () => controller.abort()
  }, [valid, keyword, term, countsKey, countsAttempt, reload])

  function update(change: (next: URLSearchParams) => void) {
    setSavedId(null)
    setParams(previous => { const next = new URLSearchParams(previous); change(next); return next })
  }
  function chooseTab(value: FeedbackState | 'ALL') {
    update(next => {
      if (value === 'OPEN') next.delete('status'); else next.set('status', value)
      next.delete('page'); next.delete('feedback')
    })
  }
  function move(next: number) { update(copy => { copy.set('page', String(next)); copy.delete('feedback') }) }
  function saved() {
    if (data && selected) afterSave.current = { id: selected, index: data.items.findIndex(item => item.id === selected) }
    setSavedId(selected)
    setReload(value => value + 1)
  }

  // 충돌 뒤에는 목록을 다시 받으면 의견이 탭에서 빠져 패널과 작성 내용이 사라지므로 해당 행만 바꾸고 건수만 다시 받는다.
  function refreshed(fresh: FeedbackEntry) {
    setLoaded(previous => previous && { ...previous, data: { ...previous.data,
      items: previous.data.items.map(item => item.id === fresh.id ? fresh : item) } })
    setCountsAttempt(value => value + 1)
  }

  return <section className={styles.page}>
    <header className={styles.heading}><h1>사용자 의견</h1><p>앱에서 받은 의견을 읽고 처리 기록을 남깁니다.</p></header>
    <div className={styles.toolbar}>
      <div className={`admin-section-tabs ${styles.tabs}`} role="group" aria-label="상태별 의견">
        {tabs.map(({ value, label }) => <button key={value} type="button" data-admin-navigation aria-pressed={tab === value}
          onClick={() => chooseTab(value)}>{label}{tabCounts ? ' ' : null}{tabCounts
            ? <span className={styles.tabCount}>{(value === 'ALL' ? tabCounts.total : tabCounts[value]).toLocaleString('ko-KR')}</span> : null}</button>)}
      </div>
      <form data-admin-navigation key={keyword} className={styles.search} aria-label="사용자 의견 검색" onSubmit={event => {
        event.preventDefault()
        const value = String(new FormData(event.currentTarget).get('keyword') ?? '').trim()
        if (value === term) { setAttempt(count => count + 1); setCountsAttempt(count => count + 1); return }
        update(next => { if (value) next.set('keyword', value); else next.delete('keyword'); next.delete('page'); next.delete('feedback') })
      }}>
        <label><span className={styles.visuallyHidden}>의견 내용 검색</span><span className={styles.inputWrap}>
          <AdminIcon name="search" />
          <input name="keyword" maxLength={200} defaultValue={keyword} placeholder="의견 내용 검색" />
        </span></label>
        <Button type="submit">검색</Button>
      </form>
    </div>
    {error && <div className="admin-alert" role="alert"><p>{error}</p>{valid &&
      <button type="button" onClick={() => setAttempt(value => value + 1)}>다시 불러오기</button>}</div>}
    {countsFailure && <div className="admin-alert" role="alert"><p>{countsFailure}</p>
      <button type="button" onClick={() => { setCountsFailure(null); setCountsAttempt(value => value + 1) }}>건수 다시 불러오기</button></div>}
    <div className={styles.workspace} data-detail={selected ? 'open' : 'closed'}>
      <div className={`admin-list-card ${styles.card} ${styles.listPane}`} aria-busy={pending}>
        {pending && <p className={styles.loading} role="status">의견 목록을 불러오는 중…</p>}
        {data && <>
          <div className={styles.resultsHeading}>
            <p className={styles.meta}><strong>{data.totalElements.toLocaleString('ko-KR')}</strong>건</p>
            <span className={styles.sortLabel}>접수 최신순 · 한국 시각</span>
          </div>
          {data.items.length > 0 ? <ol className={styles.list} aria-label="사용자 의견 목록">
            {data.items.map(entry => {
              const related = relatedText(entry)
              return <li key={entry.id}><button type="button" data-admin-navigation className={styles.row}
                aria-current={selected === entry.id ? 'true' : undefined} onClick={() => select(entry.id)}>
                <span className={styles.rowTop}><FeedbackStatusBadge status={entry.status} />
                  <span className={styles.rowMeta}><span>#{entry.id}</span>{related ? <span>{related}</span> : null}
                    {entry.note ? <span>메모 있음</span> : null}</span>
                  <time dateTime={entry.createdAt}>{formatShortTime(entry.createdAt)}</time></span>
                <span className={styles.preview}>{entry.content || '추가 의견 없음'}</span>
              </button></li>
            })}
          </ol> : <div className={styles.empty}>
            <p>{term ? '검색 결과가 없습니다.' : emptyMessage(tab)}</p>
            {term ? <button type="button" data-admin-navigation onClick={() => update(next => { next.delete('keyword'); next.delete('page'); next.delete('feedback') })}>검색어 지우기</button> : null}
          </div>}
          <div className={styles.pagination}><ListPagination page={data.page} totalPages={data.totalPages} onMove={move}
            disabled={pending} guardNavigation label="사용자 의견 목록 페이지" /></div>
        </>}
      </div>
      <div className={`admin-list-card ${styles.card} ${styles.panelPane}`}>
        <button type="button" data-admin-navigation className={styles.back} onClick={() => select(null)}>
          <AdminIcon name="chevronLeft" />목록</button>
        {savedId && savedId !== current?.id ? <p className={styles.savedNotice} role="status">의견 #{savedId}의 처리 기록을 저장했습니다.</p> : null}
        {current ? <FeedbackProcessingPanel key={current.id} entry={current} onSaved={saved} onRefreshed={refreshed} />
          : <p className={styles.empty}>{data ? '목록에서 처리할 의견을 선택해 주세요.' : ''}</p>}
      </div>
    </div>
  </section>
}
