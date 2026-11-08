import { ListFilters } from '../shared/ListFilters'
import { applicationStatuses, attentionReasons } from './listFilterLabels'
import { ListPagination } from './ListPagination'
import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { getManagementDetail, getManagementPage, managementResourcePath, requestManagementApi } from './api'
import type { ManagementPage, ManagementResource } from './managementContract'
import { ManagementSummaryTable } from './ManagementSummaryTable'
import { labels, provinces, rentals } from './fields'
import styles from './ManagementList.module.css'
import { managementListParams } from './managementNavigation'
import { verificationStatuses } from './complexVerificationContract'
import { verificationStatusLabels } from './complexVerificationPresentation'

export function ManagementList({ resource, embedded = false, compact = false, refresh = 0 }: { resource: ManagementResource; embedded?: boolean; compact?: boolean; refresh?: number }) {
  const [params, setParams] = useSearchParams()
  const [loaded, setLoaded] = useState<{ query: string; filters: string; page: ManagementPage } | null>(null)
  const [failure, setFailure] = useState<{ query: string; message: string } | null>(null)
  const [attempt, setAttempt] = useState(0)
  const [checked, setChecked] = useState<Set<number>>(new Set())
  const [bulkBusy, setBulkBusy] = useState(false)
  const [bulkNotice, setBulkNotice] = useState('')
  const search = (embedded ? managementListParams(params, resource) : params).toString()
  const activeParams = new URLSearchParams(search)
  const filterParams = new URLSearchParams(search)
  filterParams.delete('page')
  filterParams.sort()
  const filters = `${resource}?${filterParams}`
  const query = `${resource}?${search}#${attempt}-${refresh}`
  const page = loaded?.filters === filters && failure?.query !== query ? loaded.page : null
  const error = failure?.query === query ? failure.message : ''
  const pending = loaded?.query !== query && failure?.query !== query
  const title = resource === 'complexes' ? '단지' : '공고'
  const returnTo = `/admin/${resource}${search ? `?${search}` : ''}`
  const trashView = activeParams.get('deleted') === 'true'
  // 목록 조건이나 페이지가 바뀌면 선택을 비운다. 보이지 않는 행이 함께 처리되지 않게 한다.
  useEffect(() => { setChecked(new Set()) }, [search])
  async function bulk() {
    if (!checked.size || bulkBusy) return
    const action = trashView ? '복구' : '휴지통으로 이동'
    if (!window.confirm(`${title} ${checked.size}개를 ${action}할까요?${trashView ? '' : ' 휴지통에서 다시 복구할 수 있습니다.'}`)) return
    setBulkBusy(true); setBulkNotice('')
    const failed: string[] = []
    // 삭제·복구는 항목마다 최신 버전이 필요해 하나씩 조회한 뒤 처리한다.
    for (const id of checked) {
      try {
        const detail = await getManagementDetail(resource, String(id))
        await requestManagementApi(`${managementResourcePath(resource)}/${id}${trashView ? '/restore' : ''}?version=${detail.data.version}`, trashView ? 'POST' : 'DELETE')
      } catch { failed.push(page?.items.find(item => item.id === id)?.name ?? `#${id}`) }
    }
    setBulkNotice(failed.length ? `${checked.size - failed.length}개 ${action} · 실패 ${failed.length}개: ${failed.join(', ')}` : `${checked.size}개를 ${action}했습니다.`)
    setChecked(new Set()); setBulkBusy(false); setAttempt(value => value + 1)
  }
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
    const failureReturnTo = activeParams.get('failureReturnTo')
    if (failureReturnTo) next.set('failureReturnTo', failureReturnTo)
    if (embedded && params.has('mode')) for (const key of ['mode', 'complexId']) {
      if (params.has(key)) next.set(key, params.get(key) ?? '')
    }
    setParams(next)
  }
  function filter(key: string, value: string) {
    const next = new URLSearchParams(activeParams)
    next.delete('page')
    if (key === 'attention') next.delete('unconfirmed')
    if (value) next.set(key, value)
    else next.delete(key)
    updateFilters(next)
  }
  function reset() {
    const next = new URLSearchParams()
    for (const key of ['deleted', 'complexId', 'workReturnTo']) {
      const value = activeParams.get(key)
      if (value) next.set(key, value)
    }
    updateFilters(next)
  }
  const keyword = activeParams.get('keyword') ?? ''
  const attention = activeParams.get('attention') ?? (activeParams.get('unconfirmed') === 'true' ? 'UNCONFIRMED' : '')
  const applied = [
    ...['keyword', 'provider', 'region', 'rental', 'verification', 'applicationStatus'].flatMap(key => {
      const value = activeParams.get(key)
      if (!value) return []
      const label = key === 'region' ? provinces.find(([code]) => code === value)?.[1] ?? value
        : key === 'verification' ? verificationStatusLabels[value as keyof typeof verificationStatusLabels] ?? value
        : applicationStatuses[value] ?? labels[value] ?? value
      return [{ key, label }]
    }),
    ...[['review', '재검토'], ['recruiting', '모집중'], ['missingCoordinates', '좌표 미확인']].flatMap(([key, label]) =>
      activeParams.get(key) === 'true' ? [{ key, label }] : []),
    ...(attention ? [{ key: 'attention', label: attentionReasons[attention] ?? attention }] : []),
    ...(activeParams.get('complexId') ? [{ key: 'complexId', label: '연결 단지' }] : []),
  ]
  const providerFilter = <label>기관<select value={activeParams.get('provider') ?? ''} onChange={event => filter('provider', event.target.value)}>
    <option value="">전체 기관</option>{['LH', 'SH', 'GH', 'ETC'].map(value => <option key={value}>{value}</option>)}
  </select></label>
  const regionFilter = <label>지역<select value={activeParams.get('region') ?? ''} onChange={event => filter('region', event.target.value)}>
    <option value="">전체 지역</option>{provinces.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
  </select></label>
  function move(next: number) { if (next === page?.page) { setAttempt(value => value + 1); return } const current = new URLSearchParams(search); current.set('page', String(next)); updateFilters(current) }
  return <section className={`management-page ${styles.page}`}>
    {!embedded ? <header className="management-heading"><h1>{title} 관리</h1>
      <Link className="admin-primary" to={`/admin/${resource}/new`}>{title} 추가</Link></header> : null}
    {/* 휴지통은 추가 필터 안에 숨기지 않고 목록 위 탭으로 둬서 지운 항목을 바로 찾아 복구할 수 있게 한다. */}
    {!compact && <div className={styles.tabs} role="group" aria-label={`${title} 보관 상태`}>{([['false', '등록 데이터'], ['true', '휴지통']] as const).map(([value, label]) =>
      <button key={value} data-design-system="list-tab" type="button" aria-pressed={String(trashView) === value} onClick={() => {
        const next = new URLSearchParams(activeParams); next.delete('page')
        if (value === 'true') next.set('deleted', 'true'); else next.delete('deleted')
        updateFilters(next)
      }}>{label}</button>)}</div>}
    <ListFilters key={`${resource}:${keyword}`} keyword={keyword} searchLabel={resource === 'complexes' ? '단지명·주소' : '공고명'}
      initiallyExpanded={['rental', 'verification', 'review', 'unconfirmed', 'attention', 'recruiting', 'missingCoordinates'].some(key => activeParams.has(key))}
      onSearch={value => filter('keyword', value)} applied={applied} onRemove={key => filter(key, '')} onReset={reset}
      primary={<>{providerFilter}{resource === 'complexes' ? regionFilter : <label>접수 상태<select
        value={activeParams.get('applicationStatus') ?? ''} onChange={event => filter('applicationStatus', event.target.value)}>
        <option value="">전체 상태</option>{Object.entries(applicationStatuses).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
      </select></label>}</>}
      more={<>
        {resource === 'announcements' && regionFilter}
        <label>공급 유형<select value={activeParams.get('rental') ?? ''} onChange={event => filter('rental', event.target.value)}>
          <option value="">전체 유형</option>{rentals.map(value => <option key={value} value={value}>{labels[value]}</option>)}
        </select></label>
        {resource === 'complexes' && <>
          <label>검토 상태<select value={activeParams.get('verification') ?? ''} onChange={event => filter('verification', event.target.value)}>
            <option value="">전체 상태</option>{verificationStatuses.map(value => <option key={value} value={value}>{verificationStatusLabels[value]}</option>)}
          </select></label>
          <label><input type="checkbox" checked={activeParams.get('recruiting') === 'true'} onChange={event => filter('recruiting', event.target.checked ? 'true' : '')} />모집중</label>
          <label><input type="checkbox" checked={activeParams.get('missingCoordinates') === 'true'} onChange={event => filter('missingCoordinates', event.target.checked ? 'true' : '')} />좌표 미확인</label>
        </>}
        <label><input type="checkbox" checked={activeParams.get('review') === 'true'} onChange={event => filter('review', event.target.checked ? 'true' : '')} />원천 변경 확인 필요</label>
        {resource === 'announcements' && <label>확인 필요<select value={attention} onChange={event => filter('attention', event.target.value)}>
          <option value="">전체 공고</option>{Object.entries(attentionReasons).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select></label>}
      </>} />
    {error ? <div role="alert" className="registration-error"><p>{error}</p><button onClick={() => setAttempt(value => value + 1)}>다시 불러오기</button></div> : null}
    <div className={styles.results} aria-busy={pending}>
      {pending ? <p role="status" className={page ? styles.loading : undefined}>목록을 불러오는 중…</p> : null}
      {page ? <><p className={`ingest-meta ${styles.resultCount}`}>등록일 최신순 · 총 {page.totalElements.toLocaleString('ko-KR')}건</p>{!compact && checked.size > 0 && <div className={styles.bulkBar} data-active={checked.size > 0 || undefined}>
          <span>{checked.size}개 선택</span>
          <button type="button" className={trashView ? 'admin-tonal' : 'admin-danger'} disabled={!checked.size || bulkBusy} onClick={() => void bulk()}>{bulkBusy ? '처리 중…' : trashView ? '선택 복구' : '선택 삭제'}</button>
        </div>}
        {bulkNotice && <p role="status">{bulkNotice}</p>}
        <ManagementSummaryTable items={page.items} resource={resource} returnTo={returnTo} inlineSearch={embedded ? search : undefined} compact={compact} fixedHeight
          selection={compact ? undefined : { ids: checked, disabled: bulkBusy, onChange: setChecked }} trash={trashView} />
        <ListPagination page={page.page} totalPages={page.totalPages} onMove={move} disabled={pending} guardNavigation={embedded} /></> : null}
    </div>
  </section>
}
