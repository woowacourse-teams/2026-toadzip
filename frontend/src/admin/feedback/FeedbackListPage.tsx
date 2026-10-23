import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router'
import { Button } from '../../design-system/components/Button'
import { ListPagination } from '../management/ListPagination'
import { listFeedback, feedbackStates, type FeedbackPage } from './api'
import { FeedbackProcessingPanel } from './FeedbackProcessingPanel'
import styles from './FeedbackListPage.module.css'

const receivedAt = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', hour12: false,
})

export function FeedbackListPage() {
  const [params, setParams] = useSearchParams()
  const [loaded, setLoaded] = useState<{ query: string; data: FeedbackPage } | null>(null)
  const [failure, setFailure] = useState<{ query: string; message: string } | null>(null)
  const [attempt, setAttempt] = useState(0)
  const keyword = params.get('keyword') ?? ''
  const state = params.get('status') ?? ''
  const selected = Number(params.get('feedback')) || null
  function setSelected(id: number | null) { setParams(current => { if (id) current.set('feedback', String(id)); else current.delete('feedback'); return current }, { replace: true }) }
  const pageValue = params.get('page') ?? '0'
  const page = Number(pageValue)
  const valid = (!state || Object.hasOwn(feedbackStates, state)) && keyword.length <= 200 && /^\d+$/.test(pageValue) && Number.isSafeInteger(page) && page <= 2147483647
  const search = params.toString()
  const listParams = new URLSearchParams(params); listParams.delete('feedback')
  const query = `${listParams}#${attempt}`
  const data = valid && loaded?.query === query ? loaded.data : null
  const error = valid ? failure?.query === query ? failure.message : '' : '검색 조건을 확인해 주세요.'
  const pending = valid && loaded?.query !== query && failure?.query !== query

  useEffect(() => {
    if (!valid) return
    const controller = new AbortController()
    const request = new URLSearchParams({ page: String(page), size: '20' })
    if (keyword.trim()) request.set('keyword', keyword.trim())
    if (state) request.set('status', state)
    void listFeedback(request, controller.signal).then(result => {
      if (controller.signal.aborted) return
      const last = Math.max(0, result.totalPages - 1)
      if (page > last) {
        setParams(current => { current.set('page', String(last)); return current }, { replace: true })
        return
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
  }, [valid, page, keyword, state, query, setParams])

  function move(next: number) { setParams(current => { current.set('page', String(next)); return current }) }

  return <section className={styles.page}>
    <header className={styles.heading}><h1>사용자 의견</h1><p>접수된 의견을 확인하고 처리 상태와 결과를 기록합니다.</p></header>
    <form data-admin-navigation key={search} className={styles.filters} aria-label="사용자 의견 검색" onSubmit={event => {
      event.preventDefault()
      const values = new FormData(event.currentTarget)
      const term = String(values.get('keyword') ?? '').trim()
      const next = new URLSearchParams()
      const filter = String(values.get('status') ?? '')
      if (filter) next.set('status', filter)
      if (term) next.set('keyword', term)
      if (next.toString() === search) setAttempt(value => value + 1)
      else setParams(next)
    }}>
      <label className={styles.searchField}>의견 내용 검색<span className={styles.inputWrap}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><circle cx="10.5" cy="10.5" r="6.5" /><path d="m16 16 4 4" /></svg>
        <input name="keyword" maxLength={200} defaultValue={keyword} placeholder="검색할 내용을 입력하세요" />
      </span></label>
      <label>상태<select aria-label="의견 상태 필터" name="status" defaultValue={state}><option value="">전체</option>{Object.entries(feedbackStates).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
      <div className={styles.actions}><Button className="admin-primary" type="submit">검색</Button>
      <button className={styles.resetButton} type="button" onClick={() => {
        if (!search) setAttempt(value => value + 1)
        else setParams({})
      }}>초기화</button></div>
    </form>
    {error && <div className={styles.error} role="alert"><p>{error}</p>{valid &&
      <button type="button" onClick={() => setAttempt(value => value + 1)}>다시 불러오기</button>}</div>}
    <div className={styles.results} aria-busy={pending}>
      {pending && <p className={styles.loading} role="status">의견 목록을 불러오는 중…</p>}
      {data && <>
        <div className={styles.resultsHeading}>
          <p className={styles.meta}>총 <strong>{data.totalElements.toLocaleString('ko-KR')}</strong>건</p>
          <span className={styles.sortLabel}>접수 최신순 · 한국 시각</span>
        </div>
        {data.items.length > 0 ? <ol className={styles.list} aria-label="사용자 의견 목록">
          {data.items.map(entry => <li key={entry.id}><article className={styles.card}>
            <header className={styles.cardHeading}><h2>의견 #{entry.id}</h2><time dateTime={entry.createdAt}>{receivedAt.format(new Date(entry.createdAt))}</time></header>
            <p className={styles.preview}>{entry.content.slice(0, 120)}{entry.content.length > 120 ? '…' : ''}</p>
            <details><summary>전체 내용 보기<svg className={styles.chevron} viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true"><path d="m5 7.5 5 5 5-5" /></svg></summary><div role="region" aria-label={`의견 ${entry.id} 전체 내용`}>
              <p className={styles.content}>{entry.content}</p>
            </div></details>
            <p>{feedbackStates[entry.status ?? 'OPEN']}{entry.note ? ` · ${entry.note}` : ''}</p>
            {entry.processedAt && <p className={styles.meta}>최근 처리: {entry.actor} · <time dateTime={entry.processedAt}>{receivedAt.format(new Date(entry.processedAt))}</time></p>}
            <button type="button" data-admin-navigation aria-expanded={selected === entry.id} onClick={() => setSelected(selected === entry.id ? null : entry.id)}>의견 처리</button>
            {selected === entry.id ? <FeedbackProcessingPanel key={entry.id} entry={entry} onSaved={updated => setLoaded(current => current ? { ...current, data: { ...current.data, items: current.data.items.map(item => item.id === updated.id ? updated : item) } } : current)} /> : null}
          </article></li>)}
        </ol> : <p className={styles.empty}>{keyword.trim() ? '검색 결과가 없습니다.' : '접수된 의견이 없습니다.'}</p>}
        <div className={styles.pagination}><ListPagination page={data.page} totalPages={data.totalPages} onMove={move} disabled={pending} label="사용자 의견 목록 페이지" /></div>
      </>}
    </div>
  </section>
}
