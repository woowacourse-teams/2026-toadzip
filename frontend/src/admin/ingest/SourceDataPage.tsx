import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { ListPagination } from '../management/ListPagination'
import { SourceUrl } from '../shared/SourceUrl'
import { storedDataRows, storedDataValue } from '../shared/storedDataPresentation'
import { getSourceData, isSourceCategory, sourceCategories, type SourceCategory, type SourcePage } from './sourceApi'
import { sourceFieldLabel, sourcePresentation } from './sourcePresentation'
import styles from './SourceDataPage.module.css'

export function SourceDataPage() {
  const [params, setParams] = useSearchParams()
  const search = params.toString()
  const selected = params.get('category') ?? 'MYHOME_COMPLEX'
  const category = isSourceCategory(selected) ? selected : null
  const keyword = params.get('keyword') ?? ''
  const pageText = params.get('page') ?? '0'
  const pageNumber = Number(pageText)
  const validPage = /^(0|[1-9][0-9]*)$/.test(pageText) && Number.isSafeInteger(pageNumber)
    && pageNumber <= 2_147_483_647
  const validQuery = category !== null && validPage && keyword.length <= 200
  const [loaded, setLoaded] = useState<{ category: SourceCategory; keyword: string; search: string; data: SourcePage } | null>(null)
  const result = loaded?.category === category && loaded.keyword === keyword ? loaded.data : null
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [attempt, setAttempt] = useState(0)
  const pending = validQuery && !error && (loading || loaded?.search !== search)

  useEffect(() => {
    setError('')
    if (!validQuery || !category) return
    setLoading(true)
    const controller = new AbortController()
    void getSourceData(category, keyword, pageNumber, controller.signal).then(data => {
      if (!controller.signal.aborted) {
        setLoaded({ category, keyword, search, data })
        setLoading(false)
      }
    }).catch(cause => {
      if (!controller.signal.aborted) {
        setError(cause instanceof Error ? cause.message : '원천 데이터를 불러오지 못했습니다.')
        setLoading(false)
      }
    })
    return () => controller.abort()
  }, [category, keyword, pageNumber, validQuery, search, attempt])

  function move(page: number) {
    setParams(current => { current.set('page', String(page)); return current })
  }

  function selectCategory(next: SourceCategory) {
    setParams(current => { current.set('category', next); current.delete('page'); return current })
  }

  const rows = result?.items.map(row => ({ row, fields: new Map(storedDataRows(row.raw).map(field => [field.path, field.value])) })) ?? []
  const paths = new Set(rows.flatMap(row => [...row.fields.keys()]).filter(path => path !== 'source_key' && path !== 'collected_at'))
  const firstFields = category ? sourcePresentation[category].firstFields.filter(key => paths.has(key)) : []
  const columns = [...firstFields, ...[...paths].filter(path => !firstFields.includes(path)).sort()]
  const metadata = category ? sourcePresentation[category] : null

  return <section className={`management-page ${styles.page}`}>
    <header className="management-heading"><div><h1>원천 데이터</h1>
      <p>원천별 저장 데이터를 한 행씩 비교합니다. 열 제목에 항목 뜻과 원천 키를 함께 표시합니다.</p>
    </div><button type="button" disabled={pending} onClick={() => setAttempt(value => value + 1)}>목록 새로고침</button></header>
    <div className={styles.tabs} role="tablist" aria-label="원천 분류">
      {Object.entries(sourceCategories).map(([value, label], index, entries) => <button key={value} type="button"
        role="tab" id={`source-tab-${value}`} aria-selected={category === value} aria-controls="source-panel"
        tabIndex={category === value || (!category && index === 0) ? 0 : -1}
        onClick={() => selectCategory(value as SourceCategory)} onKeyDown={event => {
          let next: number
          if (event.key === 'ArrowRight') next = (index + 1) % entries.length
          else if (event.key === 'ArrowLeft') next = (index + entries.length - 1) % entries.length
          else if (event.key === 'Home') next = 0
          else if (event.key === 'End') next = entries.length - 1
          else return
          event.preventDefault()
          selectCategory(entries[next][0] as SourceCategory)
          event.currentTarget.parentElement?.querySelectorAll<HTMLButtonElement>('[role="tab"]')[next]?.focus()
        }}>{label}</button>)}
    </div>
    <div id="source-panel" role="tabpanel" aria-labelledby={category ? `source-tab-${category}` : undefined}>
    {metadata ? <div className={styles.sourceInfo}>
      <p><strong>{metadata.provider}</strong><a href={metadata.documentationUrl} target="_blank" rel="noreferrer">API 설명·키 발급</a>
        <Link to="/admin/ingest">API 키 입력하고 수집</Link></p>
      {result?.items[0] ? <p><span>수집 API URL</span><SourceUrl url={result.items[0].sourceUrl} /></p> : null}
    </div> : null}
    <form key={search} className="management-filters" onSubmit={event => {
      event.preventDefault()
      const values = new FormData(event.currentTarget)
      const next = new URLSearchParams({ category: category ?? 'MYHOME_COMPLEX' })
      const text = String(values.get('keyword') ?? '').trim()
      if (text) next.set('keyword', text)
      setParams(next)
    }}>
      <label className="management-search">이름·원천 식별자<input name="keyword" defaultValue={keyword} maxLength={200} placeholder="단지명, 공고명 또는 식별자 검색" /></label>
      <button type="submit" className="admin-primary">조회</button>
      <button type="button" onClick={() => setParams({ category: category ?? 'MYHOME_COMPLEX' })}>검색 초기화</button>
    </form>
    {!validQuery ? <div role="alert" className="registration-error"><p>조회 주소의 분류, 검색어 또는 페이지 번호가 올바르지 않습니다. 검색 초기화 후 다시 조회해 주세요.</p></div> : null}
    {validQuery && error ? <div role="alert" className="registration-error"><p>{error}</p><button type="button" onClick={() => setAttempt(value => value + 1)}>다시 불러오기</button></div> : null}
    <div className={styles.results} aria-busy={pending}>
    {pending ? <p role="status" className={result ? styles.loading : undefined}>원천 데이터를 불러오는 중…</p> : null}
    {validQuery && result && category ? <>
      <div className={styles.summary}><p>수집 시각 최신순 · 총 <strong>{result.totalElements.toLocaleString('ko-KR')}건</strong></p>
        <p>주요 항목부터 표시 · 좌우로 스크롤해 전체 항목 확인</p></div>
      {result.items.length === 0 ? <div className="admin-empty"><h2>표시할 원천 데이터가 없습니다.</h2>
        <p>{keyword ? '검색어를 바꿔 다시 조회해 주세요.' : '다른 분류를 선택하거나 수집·정제에서 데이터를 수집해 주세요.'}</p>
        {result.totalElements > 0 ? <button type="button" onClick={() => move(0)}>첫 페이지로 이동</button> : null}</div>
        : <div className={`admin-table-scroll ${styles.scroll}`} tabIndex={0} role="region" aria-label="원천 데이터 목록 가로 스크롤">
          <table className={styles.table}>
            <caption className="sr-only">{sourceCategories[category]} 원천 목록 · {result.page + 1}페이지</caption>
            <thead><tr>{columns.map(path => <th key={path} scope="col" data-field={path}>{sourceFieldLabel(category, path)} <span>({path})</span></th>)}
              <th scope="col">수집 시각 <span>(collected_at)</span></th>
              {category === 'LH_ANNOUNCEMENT_CATALOG' ? <th scope="col">원천 변경 감지 <span>(changed_at)</span></th> : null}
              <th scope="col">원천 식별자 <span>(source_key)</span></th><th scope="col">공식 공고 URL <span>(originalUrl)</span></th>
            </tr></thead>
            <tbody>{rows.map(({ row, fields }) => <tr key={`${row.sourceKey}:${row.id}`}>
              {columns.map((path, index) => {
                const content = storedDataValue(path, fields.get(path))
                const title = typeof content === 'string' ? content : undefined
                return index === 0
                  ? <th scope="row" key={path} data-field={path} title={title}>{content}</th>
                  : <td key={path} data-field={path} title={title}>{content}</td>
              })}
              <td className={styles.time}>{formatTime(row.collectedAt)}</td>
              {category === 'LH_ANNOUNCEMENT_CATALOG' ? <td className={styles.time}>{formatTime(row.sourceUpdatedAt)}</td> : null}
              <td title={row.sourceKey}>{row.sourceKey}</td><td className={styles.url}><SourceUrl url={row.originalUrl} /></td>
            </tr>)}</tbody>
          </table></div>}
      <ListPagination page={result.page} totalPages={result.totalPages} onMove={move} disabled={pending} />
    </> : null}
    </div>
    </div>
  </section>
}

function formatTime(value: string | null) {
  return value ? new Date(value).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul', hour12: false }) : '기록 없음'
}
