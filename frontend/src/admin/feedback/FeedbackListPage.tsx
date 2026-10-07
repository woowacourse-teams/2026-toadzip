import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router'
import { Button } from '../../design-system/components/Button'
import { ListPagination } from '../management/ListPagination'
import { listFeedback, type FeedbackPage } from './api'
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
  const pageValue = params.get('page') ?? '0'
  const page = Number(pageValue)
  const valid = keyword.length <= 200 && /^\d+$/.test(pageValue) && Number.isSafeInteger(page) && page <= 2147483647
  const search = params.toString()
  const query = `${search}#${attempt}`
  const data = valid && loaded?.query === query ? loaded.data : null
  const error = valid ? failure?.query === query ? failure.message : '' : '검색 조건을 확인해 주세요.'
  const pending = valid && loaded?.query !== query && failure?.query !== query

  useEffect(() => {
    if (!valid) return
    const controller = new AbortController()
    const request = new URLSearchParams({ page: String(page), size: '20' })
    if (keyword.trim()) request.set('keyword', keyword.trim())
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
  }, [valid, page, keyword, query, setParams])

  function move(next: number) { setParams(current => { current.set('page', String(next)); return current }) }

  return <section className={styles.page}>
    <header className={styles.heading}><h1>사용자 의견</h1><p>불편 사항과 개선 제안을 접수 최신순으로 확인합니다.</p></header>
    <form key={search} className={styles.filters} aria-label="사용자 의견 검색" onSubmit={event => {
      event.preventDefault()
      const values = new FormData(event.currentTarget)
      const term = String(values.get('keyword') ?? '').trim()
      const next = new URLSearchParams()
      if (term) next.set('keyword', term)
      if (next.toString() === search) setAttempt(value => value + 1)
      else setParams(next)
    }}>
      <label>의견 내용 검색<input name="keyword" maxLength={200} defaultValue={keyword} placeholder="검색할 내용을 입력하세요" /></label>
      <Button type="submit">검색</Button>
      <button type="button" onClick={() => {
        if (!search) setAttempt(value => value + 1)
        else setParams({})
      }}>초기화</button>
    </form>
    {error && <div className={styles.error} role="alert"><p>{error}</p>{valid &&
      <button type="button" onClick={() => setAttempt(value => value + 1)}>다시 불러오기</button>}</div>}
    <div aria-busy={pending}>
      {pending && <p role="status">의견 목록을 불러오는 중…</p>}
      {data && <>
        <p className={styles.meta}>접수 최신순 · 총 {data.totalElements.toLocaleString('ko-KR')}건 · 한국 시각</p>
        {data.items.length > 0 ? <ol className={styles.list} aria-label="사용자 의견 목록">
          {data.items.map(entry => <li key={entry.id}><article className={styles.card}>
            <header><h2>의견 #{entry.id}</h2><time dateTime={entry.createdAt}>{receivedAt.format(new Date(entry.createdAt))}</time></header>
            <p className={styles.preview}>{entry.content.slice(0, 120)}{entry.content.length > 120 ? '…' : ''}</p>
            <details><summary>전체 내용 보기</summary><div role="region" aria-label={`의견 ${entry.id} 전체 내용`}>
              <p className={styles.content}>{entry.content}</p>
            </div></details>
          </article></li>)}
        </ol> : <p className={styles.empty}>{keyword.trim() ? '검색 결과가 없습니다.' : '접수된 의견이 없습니다.'}</p>}
        <ListPagination page={data.page} totalPages={data.totalPages} onMove={move} disabled={pending} label="사용자 의견 목록 페이지" />
      </>}
    </div>
  </section>
}
