import { ListPagination } from './ListPagination'
import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { getManagementPage } from './api'
import type { ManagementPage, ManagementResource } from './managementContract'
import { ManagementSummaryTable } from './ManagementSummaryTable'
import { labels, provinces, rentals } from './fields'
import styles from './ManagementList.module.css'
import { managementListParams } from './managementNavigation'

export function ManagementList({ resource, embedded = false, compact = false, refresh = 0 }: { resource: ManagementResource; embedded?: boolean; compact?: boolean; refresh?: number }) {
  const [params, setParams] = useSearchParams()
  const [loaded, setLoaded] = useState<{ query: string; filters: string; page: ManagementPage } | null>(null)
  const [failure, setFailure] = useState<{ query: string; message: string } | null>(null)
  const [attempt, setAttempt] = useState(0)
  const search = (embedded ? managementListParams(params, resource) : params).toString()
  const activeParams = new URLSearchParams(search)
  const filterParams = new URLSearchParams(search)
  filterParams.delete('page')
  filterParams.sort()
  const filters = `${resource}?${filterParams}`
  const query = `${resource}?${search}#${attempt}-${refresh}`
  const page = loaded?.filters === filters ? loaded.page : null
  const error = failure?.query === query ? failure.message : ''
  const pending = loaded?.query !== query && failure?.query !== query
  const title = resource === 'complexes' ? '단지' : '공고'
  const returnTo = `/admin/${resource}${search ? `?${search}` : ''}`
  useEffect(() => {
    const controller = new AbortController()
    void getManagementPage(resource, new URLSearchParams(search), controller.signal).then(result => {
      if (!controller.signal.aborted) setLoaded({ query, filters, page: result })
    }).catch(cause => {
      if (!controller.signal.aborted) setFailure({ query, message: cause instanceof Error ? cause.message : '목록을 불러오지 못했습니다.' })
    })
    return () => controller.abort()
  }, [resource, search, query, filters])
  function updateFilters(next: URLSearchParams) {
    if (embedded && params.has('mode')) for (const key of ['mode', 'complexId']) {
      if (params.has(key)) next.set(key, params.get(key) ?? '')
    }
    setParams(next)
  }
  function move(next: number) { const current = new URLSearchParams(search); current.set('page', String(next)); updateFilters(current) }
  return <section className={`management-page ${styles.page}`}>
    {!embedded ? <header className="management-heading"><h1>{title} 관리</h1>
      <Link className="admin-primary" to={`/admin/${resource}/new`}>{title} 추가</Link></header> : null}
    <form key={search} data-admin-navigation className={styles.filters} onSubmit={event => {
      event.preventDefault(); const values = new FormData(event.currentTarget); const next = new URLSearchParams()
      for (const [key, value] of values) if (String(value).trim()) next.set(key, String(value).trim())
      updateFilters(next)
    }}>
      <label className={styles.search}>{title === '단지' ? '단지명·주소' : '공고명'}<input name="keyword" defaultValue={activeParams.get('keyword') ?? ''} maxLength={200} placeholder={`${title} 검색`} /></label>
      <label>지역<select name="region" defaultValue={activeParams.get('region') ?? ''}><option value="">전체 지역</option>{provinces.map(([value,label]) => <option key={value} value={value}>{label}</option>)}</select></label>
      <label>기관<select name="provider" defaultValue={activeParams.get('provider') ?? ''}><option value="">전체 기관</option>{['LH','SH','GH','ETC'].map(value => <option key={value}>{value}</option>)}</select></label>
      <div className={styles.actions}><button className="admin-primary" type="submit">검색</button><button data-admin-navigation type="button" onClick={() => setParams(embedded && params.has('mode') ? { mode: params.get('mode') ?? 'direct', ...(params.has('complexId') ? { complexId: params.get('complexId') ?? '' } : {}) } : {})}>초기화</button></div>
      <details className={styles.moreFilters} open={activeParams.get('deleted') === 'true' || activeParams.has('rental') || activeParams.has('review') || undefined}><summary>
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" aria-hidden="true"><path d="M4 7h16M4 17h16" /><circle cx="9" cy="7" r="2" fill="var(--ds-color-surface)" /><circle cx="15" cy="17" r="2" fill="var(--ds-color-surface)" /></svg>
        <span>추가 필터{activeParams.get('deleted') === 'true' ? ' · 휴지통' : ''}</span>
        <svg className={styles.filterChevron} width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" aria-hidden="true"><path d="m6 9 6 6 6-6" /></svg>
      </summary><div>
        <label>공급 유형<select name="rental" defaultValue={activeParams.get('rental') ?? ''}><option value="">전체 유형</option>{rentals.map(value => <option key={value} value={value}>{labels[value]}</option>)}</select></label>
        <label>보관 상태<select name="deleted" defaultValue={activeParams.get('deleted') ?? 'false'}><option value="false">등록 데이터</option><option value="true">휴지통</option></select></label>
        <label className={styles.review}><input name="review" type="checkbox" value="true" defaultChecked={activeParams.get('review') === 'true'} />원천 변경 확인 필요</label>
      </div></details>
      {managementListParams(params, resource).get('complexId') ? <input type="hidden" name="complexId" value={managementListParams(params, resource).get('complexId') ?? ''} /> : null}
    </form>
    {error ? <div role="alert" className="registration-error"><p>{error}</p><button onClick={() => setAttempt(value => value + 1)}>다시 불러오기</button></div> : null}
    <div className={styles.results} aria-busy={pending}>
      {pending ? <p role="status" className={page ? styles.loading : undefined}>목록을 불러오는 중…</p> : null}
      {page ? <><p className={`ingest-meta ${styles.resultCount}`}>등록일 최신순 · 총 {page.totalElements.toLocaleString('ko-KR')}건</p><ManagementSummaryTable items={page.items} resource={resource} returnTo={returnTo} inlineSearch={embedded ? search : undefined} compact={compact} fixedHeight />
        <ListPagination page={page.page} totalPages={page.totalPages} onMove={move} disabled={pending} guardNavigation={embedded} /></> : null}
    </div>
  </section>
}
