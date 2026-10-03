import { ManagementSummaryTable } from './ManagementSummaryTable'
import { ListPagination } from './ListPagination'
import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { getManagementPage } from './api'
import { type ManagementPage, type ManagementResource } from './managementContract'
import { labels, provinces, rentals } from './fields'

export function ManagementList({ resource }: { resource: ManagementResource }) {
  const [params, setParams] = useSearchParams()
  const [page, setPage] = useState<ManagementPage | null>(null)
  const [error, setError] = useState('')
  const [attempt, setAttempt] = useState(0)
  const search = params.toString()
  const title = resource === 'complexes' ? '단지' : '공고'
  const returnTo = `/admin/${resource}${search ? `?${search}` : ''}`
  useEffect(() => {
    const controller = new AbortController()
    setPage(null); setError('')
    void getManagementPage(resource, new URLSearchParams(search), controller.signal).then(setPage).catch(cause => {
      if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : '목록을 불러오지 못했습니다.')
    })
    return () => controller.abort()
  }, [resource, search, attempt])
  function move(next: number) { setParams(current => { current.set('page', String(next)); return current }) }
  return <section className="management-page">
    <header className="management-heading"><div><h1>{title} 관리</h1><p>등록된 {title}를 찾고 정보를 확인하거나 수정합니다.</p></div>
      <Link className="admin-primary" to={`/admin/${resource}/new`}>{title} 등록</Link></header>
    <form key={search} className="management-filters" onSubmit={event => {
      event.preventDefault(); const values = new FormData(event.currentTarget); const next = new URLSearchParams()
      for (const [key, value] of values) if (String(value).trim()) next.set(key, String(value).trim())
      setParams(next)
    }}>
      <label className="management-search">{title === '단지' ? '단지명·주소' : '공고명'}<input name="keyword" defaultValue={params.get('keyword') ?? ''} maxLength={200} placeholder={`${title} 검색`} /></label>
      <label>지역<select name="region" defaultValue={params.get('region') ?? ''}><option value="">전체 지역</option>{provinces.map(([value,label]) => <option key={value} value={value}>{label}</option>)}</select></label>
      <label>기관<select name="provider" defaultValue={params.get('provider') ?? ''}><option value="">전체 기관</option>{['LH','SH','GH','ETC'].map(value => <option key={value}>{value}</option>)}</select></label>
      <label>공급 유형<select name="rental" defaultValue={params.get('rental') ?? ''}><option value="">전체 유형</option>{rentals.map(value => <option key={value} value={value}>{labels[value]}</option>)}</select></label>
      <label>보관 상태<select name="deleted" defaultValue={params.get('deleted') ?? 'false'}><option value="false">등록 데이터</option><option value="true">휴지통</option></select></label>
      <label className="admin-check"><input name="review" type="checkbox" value="true" defaultChecked={params.get('review') === 'true'} />원천 변경 확인 필요</label>
      {params.get('complexId') ? <input type="hidden" name="complexId" value={params.get('complexId') ?? ''} /> : null}
      <button className="admin-primary" type="submit">검색</button><button type="button" onClick={() => setParams({})}>초기화</button>
    </form>
    {error ? <div role="alert" className="registration-error"><p>{error}</p><button onClick={() => setAttempt(value => value + 1)}>다시 불러오기</button></div> : null}
    {!page && !error ? <p role="status">목록을 불러오는 중…</p> : null}
    {page ? <><p className="ingest-meta">등록일 최신순 · 총 {page.totalElements.toLocaleString('ko-KR')}건</p><ManagementSummaryTable items={page.items} resource={resource} returnTo={returnTo} />
      <ListPagination page={page.page} totalPages={page.totalPages} onMove={move} /></> : null}
  </section>
}
