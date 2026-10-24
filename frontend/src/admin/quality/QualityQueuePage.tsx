import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { Button } from '../../design-system/components/Button'
import { fieldSection, getQualityQueue, landingSection, qualityReasons, type QualityPage } from './qualityQueueApi'
import { workDetailUrl } from '../shared/workNavigation'
import styles from './QualityWorkspace.module.css'

const shortcuts = [['', '모든 검토 대상'], ['SOURCE_CHANGED', '원천이 바뀐 데이터'], ['MISSING', '정보가 빠진 데이터'], ['ON_HOLD', '보류한 데이터']] as const
export function QualityQueuePage() {
  const [params, setParams] = useSearchParams()
  const [result, setResult] = useState<QualityPage | null>(null)
  const [error, setError] = useState('')
  const [attempt, setAttempt] = useState(0)
  const [keyword, setKeyword] = useState(params.get('keyword') ?? '')
  const [loading, setLoading] = useState(false)
  const queryKeyword = params.get('keyword') ?? ''
  // 단지와 공고는 검토 방법이 달라 한 목록에 섞지 않고 나눠 본다. 기본은 공고다.
  // 다른 관리 화면처럼 단지를 먼저 둔다.
  const resource = params.get('resource') === 'ANNOUNCEMENT' ? 'ANNOUNCEMENT' : 'COMPLEX'
  useEffect(() => { setKeyword(queryKeyword) }, [queryKeyword])
  useEffect(() => {
    const controller = new AbortController()
    const query = new URLSearchParams(params); query.delete('view'); query.delete('focus'); query.set('resource', resource); ['taskMine', 'taskState', 'taskOverdue', 'taskAlerts', 'taskPage'].forEach(key => query.delete(key))
    setLoading(true); setError(''); setResult(null)
    void getQualityQueue(query, controller.signal).then(value => {
      if (!controller.signal.aborted) setResult(value)
    }).catch(cause => { if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : '목록을 조회하지 못했습니다.') })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [params, attempt, resource])
  function filter(key: string, value: string) {
    const next = new URLSearchParams(params); next.delete('page'); next.delete('focus')
    if (value) next.set(key, value); else next.delete(key)
    setParams(next)
  }
  return <section className={`${styles.workspace} ${styles.queueWorkspace}`}>
    <header className={styles.pageHeading}><div><h1>검토할 데이터</h1>
      <p>원천 변경과 불일치가 있는 대상을 먼저 보여 줍니다.</p></div></header>
      <div className={styles.queueIntro}><div className={`${styles.resourceTabs} admin-folder-tabs`} role="group" aria-label="검토 대상">{([['COMPLEX', '단지'], ['ANNOUNCEMENT', '공고']] as const).map(([value, label]) =>
        <button key={value} type="button" aria-pressed={resource === value} onClick={() => filter('resource', value)}>{label}</button>)}</div></div>
      <div className={styles.queueSurface}>
        <div className={styles.shortcutBar} aria-label="빠른 검토 조건">{shortcuts.map(([reason, label]) => <button key={reason} type="button" aria-pressed={(params.get('reason') ?? '') === reason} onClick={() => filter('reason', reason)}>{label}</button>)}</div>
        <form className={`${styles.filters} ${styles.queueFilters}`} aria-label="검토 대상 검색" onSubmit={event => {
          event.preventDefault()
          const values = new FormData(event.currentTarget); const next = new URLSearchParams(params)
          next.delete('page'); next.delete('focus')
          for (const key of ['keyword', 'sourceIdentifier', 'announcementId', 'complexId', 'provider', 'applicationStatus']) {
            const value = key === 'keyword' ? keyword.trim() : String(values.get(key) ?? '').trim()
            if (value) next.set(key, value); else next.delete(key)
          }
          setParams(next)
        }}>
          <label className={styles.searchField}>이름 검색<input value={keyword} maxLength={200} placeholder={resource === 'COMPLEX' ? '단지 이름을 입력하세요' : '공고 이름을 입력하세요'} onChange={event => setKeyword(event.target.value)} /></label>
          <label>검토 사유<select value={params.get('reason') ?? ''} onChange={event => filter('reason', event.target.value)}><option value="">전체 사유</option>{Object.entries(qualityReasons).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select></label><Button data-design-system type="submit">검색</Button>
          <details className={styles.detailedFilters} open={['sourceIdentifier', 'announcementId', 'complexId', 'provider', 'applicationStatus'].some(key => params.has(key)) || undefined}>
            <summary>식별자·기관·모집상태로 좁히기</summary><div>
              <label>원천 식별자<input key={`source:${params.get('sourceIdentifier')}`} name="sourceIdentifier" maxLength={500} defaultValue={params.get('sourceIdentifier') ?? ''} placeholder="마이홈 원천 ID 또는 LH PAN ID" /></label>
              <label>공고 ID<input key={`announcement:${params.get('announcementId')}`} name="announcementId" type="number" min="1" step="1" defaultValue={params.get('announcementId') ?? ''} /></label>
              <label>단지 ID<input key={`complex:${params.get('complexId')}`} name="complexId" type="number" min="1" step="1" defaultValue={params.get('complexId') ?? ''} /></label>
              <label>공급기관<select key={`provider:${params.get('provider')}`} name="provider" defaultValue={params.get('provider') ?? ''}><option value="">전체 기관</option>{['LH', 'SH', 'GH', 'ETC'].map(value => <option key={value}>{value}</option>)}</select></label>
              <label>모집상태<select key={`status:${params.get('applicationStatus')}`} name="applicationStatus" defaultValue={params.get('applicationStatus') ?? ''}><option value="">전체 상태</option><option value="BEFORE_APPLICATION">접수 예정</option><option value="APPLYING">접수 중</option><option value="CONDITIONAL">조건부 접수</option><option value="CLOSED">접수 마감</option><option value="CANCELLED">취소 공고</option></select></label>
            </div><p>공고·단지 ID는 서비스 내부 ID입니다. 단지 ID는 연결된 공고도 찾습니다. 모집상태는 공고에만 적용되며, 확인된 일정이 있으면 그 일정을 기준으로 합니다.</p>
          </details>
        </form>
        <div className={styles.resultHeading}><h2>검토 대상 <span>{loading ? '조회 중' : result ? `${result.totalElements.toLocaleString('ko-KR')}건` : '—'}</span></h2><span>대상을 열면 원천과 나란히 비교할 수 있습니다.</span></div>
        {loading ? <p className={styles.empty} role="status">검토 대상을 불러오는 중…</p> : null}
        {error ? <div className={styles.empty} role="alert"><p>{error}</p><button onClick={() => setAttempt(value => value + 1)}>다시 조회</button></div> : null}
        {result && !loading ? <>
          {params.get('focus') && <p role="status" className={styles.hint}>{result.items.some(item => `${item.resource}:${item.id}` === params.get('focus'))
            ? '방금 확인한 대상을 목록에 표시했습니다. 남은 검토 항목을 확인하세요.'
            : '방금 확인한 대상은 현재 목록에 없습니다. 저장·재조회로 순서나 조건이 바뀔 수 있으므로, 상세의 검토 결과를 확인하세요.'}</p>}
          {result.items.length === 0 ? <div className={styles.empty}><strong>현재 조건에 검토할 대상이 없습니다.</strong><p>검색어와 검토 사유를 바꾸거나 단지·공고 관리에서 직접 찾아보세요.</p></div> : <ul className={`${styles.results} ${styles.queueRows}`}>
            {result.items.map(item => { const origin = `/admin/quality?${new URLSearchParams({ ...Object.fromEntries(params), focus: `${item.resource}:${item.id}` })}`
              return <li key={`${item.resource}:${item.id}`} className={styles.queueRow} data-returned={`${item.resource}:${item.id}` === params.get('focus') || undefined}>
              <span className={styles.resourceType} data-resource={item.resource}>{item.resource === 'COMPLEX' ? '단지' : '공고'}</span>
              {/* 이름·사유·빠진 항목을 한 줄에 둔다. 정보 누락 사유는 빠진 항목 목록과 같은 내용이라 따로 표시하지 않는다. */}
              <div className={styles.rowMain}><h3>{item.name}</h3>
                <span className={styles.reasons}>{item.reasons.filter(reason => reason !== 'MISSING' || !item.missingFields.length).map(reason => <span key={reason} className={styles.reason} data-reason={reason}>{qualityReasons[reason]}</span>)}</span>
                {item.missingFields.length ? <p className={styles.missingFields}>빠진 항목 {item.missingFields.map(field => <Link key={field} aria-label={`${field} 확인`}
                  to={workDetailUrl(item.resource, item.id, origin, fieldSection(item.resource, field))}>{field}</Link>)}</p> : <span />}</div>
              <div className={styles.rowEnd}>{item.updatedAt ? <small>수정 {new Date(item.updatedAt).toLocaleDateString('ko-KR')}</small> : null}<Link className={styles.reviewLink} aria-label={`${item.name} 비교·검토`} to={workDetailUrl(item.resource, item.id, origin, landingSection(item))}>비교·검토 <span aria-hidden="true">→</span></Link></div>
            </li> })}
          </ul>}
          <nav className={`${styles.links} ${styles.queuePagination}`} aria-label="검토 목록 페이지"><span role="status">총 {result.totalElements.toLocaleString('ko-KR')}개 · {result.page + 1}페이지</span><div><button disabled={result.page === 0} onClick={() => filter('page', String(result.page - 1))}>이전</button><button disabled={!result.hasNext} onClick={() => filter('page', String(result.page + 1))}>다음</button></div></nav>
        </> : null}
      </div>
      <footer className={styles.queueFoot}><p>다른 작업이 필요한가요?</p><nav aria-label="데이터 품질 작업"><Link to="/admin/complexes">단지 관리</Link><Link to="/admin/announcements">공고 관리</Link><Link to="/admin/ingest">수집 실행·결과</Link></nav></footer>
  </section>
}
