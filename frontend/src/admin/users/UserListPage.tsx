import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { ListPagination } from '../management/ListPagination'
import { isUserProvider, listUsers, type UserPage } from './api'
import { joinedAt, providerLabels } from './presentation'
import styles from './Users.module.css'

export function UserListPage() {
  const [params, setParams] = useSearchParams()
  const [loaded, setLoaded] = useState<{ query: string; filters: string; data: UserPage } | null>(null)
  const [failure, setFailure] = useState<{ query: string; message: string } | null>(null)
  const [attempt, setAttempt] = useState(0)
  const search = params.toString()
  const keyword = params.get('keyword') ?? '', provider = params.get('provider') ?? '', pageValue = params.get('page') ?? '0'
  const number = Number(pageValue)
  const valid = keyword.length <= 200 && (!provider || isUserProvider(provider)) && /^\d+$/.test(pageValue)
    && Number.isSafeInteger(number) && number <= 2147483647
  const filters = JSON.stringify([keyword.trim(), provider])
  const query = `${search}#${attempt}`
  const data = valid && loaded?.filters === filters ? loaded.data : null
  const error = valid ? failure?.query === query ? failure.message : '' : '검색 조건을 확인해 주세요.'
  const pending = valid && loaded?.query !== query && failure?.query !== query
  const returnTo = `/admin/users${search ? `?${search}` : ''}`

  useEffect(() => {
    if (!valid) return
    const controller = new AbortController()
    const requestParams = new URLSearchParams({ page: String(number), size: '20' })
    if (keyword.trim()) requestParams.set('keyword', keyword.trim())
    if (provider) requestParams.set('provider', provider)
    void listUsers(requestParams, controller.signal).then(result => {
      if (controller.signal.aborted) return
      if (result.totalPages > 0 && number >= result.totalPages) {
        setParams(current => { current.set('page', String(result.totalPages - 1)); return current }, { replace: true })
        return
      }
      setLoaded({ query, filters, data: result })
    }).catch(cause => {
      if (!controller.signal.aborted) setFailure({ query, message: cause instanceof Error ? cause.message : '회원 목록을 불러오지 못했습니다.' })
    })
    return () => controller.abort()
  }, [valid, query, filters, number, keyword, provider, setParams])

  function move(next: number) { setParams(current => { current.set('page', String(next)); return current }) }
  return <section className={styles.page}>
    <header className={styles.heading}><h1>회원 관리</h1></header>
    <form key={search} className={styles.filters} aria-label="회원 검색" onSubmit={event => {
      event.preventDefault()
      const values = new FormData(event.currentTarget), next = new URLSearchParams()
      const term = String(values.get('keyword') ?? '').trim(), selected = String(values.get('provider') ?? '')
      if (term) next.set('keyword', term)
      if (selected) next.set('provider', selected)
      setParams(next)
    }}>
      <label className={styles.search}>로그인 이메일·회원 ID<input name="keyword" maxLength={200}
        defaultValue={keyword} placeholder="이메일 또는 회원 ID 검색" /></label>
      <label>로그인 방식<select name="provider" defaultValue={isUserProvider(provider) ? provider : ''}>
        <option value="">전체 방식</option>{Object.entries(providerLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
      </select></label>
      <div className={styles.actions}><button type="submit" className="admin-primary">검색</button><button type="button" onClick={() => setParams({})}>초기화</button></div>
    </form>
    {error ? <div className={styles.error} role="alert"><p>{error}</p>{valid
      ? <button type="button" onClick={() => setAttempt(value => value + 1)}>다시 불러오기</button> : null}</div> : null}
    <div className={styles.results} aria-busy={pending}>
      <p className={styles.meta}>{data ? `가입일 최신순 · 총 ${data.totalElements.toLocaleString('ko-KR')}명` : '\u00a0'}</p>
      {pending ? <p className={styles.loading} role="status">회원 목록을 불러오는 중…</p> : null}
      <div className={styles.scroll} role="region" tabIndex={0} aria-label="회원 목록 스크롤">
        {data?.items.length ? <table className={styles.table} aria-label="회원 목록">
          <colgroup><col className={styles.idColumn} /><col /><col className={styles.providerColumn} /><col className={styles.dateColumn} /></colgroup>
          <thead><tr><th scope="col">회원 ID</th><th scope="col">로그인 이메일</th><th scope="col">로그인 방식</th><th scope="col">가입일</th></tr></thead>
          <tbody>{data.items.map(user => <tr key={user.id}><th scope="row"><Link aria-label={`회원 ${user.id} 상세 보기`}
            to={`/admin/users/${user.id}?returnTo=${encodeURIComponent(returnTo)}`}>{user.id}</Link></th>
            <td>{user.email || '미제공'}</td><td>{providerLabels[user.provider]}</td><td><time dateTime={user.createdAt}>{joinedAt(user.createdAt)}</time></td>
          </tr>)}</tbody>
        </table> : data ? <div className={styles.empty}><h2>표시할 회원이 없습니다.</h2><p>검색 조건을 바꿔 주세요.</p></div> : null}
      </div>
      {data ? <ListPagination page={data.page} totalPages={data.totalPages} onMove={move} disabled={pending} label="회원 목록 페이지" /> : null}
    </div>
  </section>
}
