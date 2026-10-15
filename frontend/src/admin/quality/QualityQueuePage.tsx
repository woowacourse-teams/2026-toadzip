import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { Button } from '../../design-system/components/Button'
import { getQualityQueue, qualityReasons, type QualityPage } from './qualityQueueApi'
import styles from './QualityWorkspace.module.css'

export function QualityQueuePage() {
  const [params, setParams] = useSearchParams()
  const [result, setResult] = useState<QualityPage | null>(null)
  const [error, setError] = useState('')
  const [attempt, setAttempt] = useState(0)
  const [keyword, setKeyword] = useState(params.get('keyword') ?? '')
  const [loading, setLoading] = useState(false)
  useEffect(() => {
    const controller = new AbortController()
    setLoading(true); setError(''); setResult(null)
    void getQualityQueue(params, controller.signal).then(value => {
      if (!controller.signal.aborted) setResult(value)
    }).catch(cause => { if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : '목록을 조회하지 못했습니다.') })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [params, attempt])
  function filter(key: string, value: string) {
    const next = new URLSearchParams(params); next.delete('page')
    if (value) next.set(key, value); else next.delete(key)
    setParams(next)
  }
  return <section className={styles.workspace}>
    <header><p className={styles.eyebrow}>데이터 품질 관리</p><h1>검토할 데이터</h1>
      <p>빠진 정보와 확인이 필요한 변경을 찾아 원천과 비교하세요. 확인 사유가 겹치는 대상은 한 번만 표시합니다.</p></header>
    <nav className={styles.links} aria-label="데이터 품질 작업"><Link to="/admin/complex-coverage">공식 목록·단지 누락 조사</Link><Link to="/admin/ingest">수집 실행·결과</Link></nav>
    <form className={styles.filters} onSubmit={event => { event.preventDefault(); filter('keyword', keyword.trim()) }}>
      <label>대상<select value={params.get('resource') ?? ''} onChange={event => filter('resource', event.target.value)}><option value="">전체</option><option value="COMPLEX">단지</option><option value="ANNOUNCEMENT">공고</option></select></label>
      <label>검토 사유<select value={params.get('reason') ?? ''} onChange={event => filter('reason', event.target.value)}><option value="">전체 사유</option>{Object.entries(qualityReasons).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select></label>
      <label>이름 검색<input value={keyword} maxLength={200} onChange={event => setKeyword(event.target.value)} /></label><Button type="submit">검색</Button>
    </form>
    <p className={styles.hint}>등록값 변경·원천 불일치·정보 누락 순으로 우선 표시합니다. 0과 미설치는 빠진 값으로 분류하지 않습니다.</p>
    {loading ? <p role="status">검토 대상을 불러오는 중…</p> : null}
    {error ? <div role="alert"><p>{error}</p><button onClick={() => setAttempt(value => value + 1)}>다시 조회</button></div> : null}
    {result ? <><p role="status">총 {result.totalElements.toLocaleString('ko-KR')}개 · {result.page + 1}페이지</p>
      {result.items.length === 0 ? <p className={styles.empty}>현재 조건에 검토할 대상이 없습니다.</p> : <ul className={styles.results}>
        {result.items.map(item => <li className={styles.card} key={`${item.resource}:${item.id}`}>
          <div><span className={styles.eyebrow}>{item.resource === 'COMPLEX' ? '단지' : '공고'}</span><h2>{item.name}</h2>
            <div className={styles.badges}>{item.reasons.map(reason => <span key={reason}>{qualityReasons[reason]}</span>)}</div>
            {item.missingFields.length ? <p>빠진 항목: <strong>{item.missingFields.join(', ')}</strong></p> : null}
            {item.updatedAt ? <p className={styles.hint}>관리자 변경: {new Date(item.updatedAt).toLocaleString('ko-KR')}</p> : null}</div>
          <Link className={styles.reviewLink} aria-label={`${item.name} 비교·검토`} to={`/admin/${item.resource === 'COMPLEX' ? 'complexes' : 'announcements'}/${item.id}?section=verification`}>비교·검토 →</Link>
        </li>)}
      </ul>}
      <nav className={styles.links} aria-label="검토 목록 페이지"><button disabled={result.page === 0} onClick={() => { const next = new URLSearchParams(params); next.set('page', String(result.page - 1)); setParams(next) }}>이전</button><button disabled={!result.hasNext} onClick={() => { const next = new URLSearchParams(params); next.set('page', String(result.page + 1)); setParams(next) }}>다음</button></nav>
    </> : null}
  </section>
}
