import { useEffect, useId, useState } from 'react'
import { getFailureReviews, type FailureReview, type FailureReviewPageData } from './failureReviewApi'
import { collectionCause, failureTarget } from './failurePresentation'
import styles from './ComplexIngest.module.css'
import { ComplexIconButton } from './ComplexIconButton'
import { ComplexStageHelp } from './ComplexStageHelp'
import { IconButton } from '../../design-system/components/IconButton'

type Mode = 'collection' | 'complex' | 'household' | 'coordinates'
const labels: Record<Mode, string> = { collection: '수집 오류', complex: '단지 정제 오류', household: '세대수 보강 오류', coordinates: '좌표 문제' }
const reasonLabels: Record<string, string> = {
  GEOCODING_ERROR: '주소에 맞는 좌표를 찾지 못함', MISSING_REQUIRED_VALUE: '필수 원천값 누락',
  INVALID_VALUE: '원천 값 확인 필요', INVALID_SOURCE: '원천 데이터 형식 오류', CONFLICTING_SOURCE_VALUE: '원천 값 충돌',
  COMPLEX_NOT_FOUND: '연결할 단지를 찾지 못함', HOUSING_TYPE_NOT_FOUND: '연결할 주택형을 찾지 못함',
  AMBIGUOUS_HOUSING_TYPE: '일치하는 주택형이 여러 개', DUPLICATE_TARGET_COMPLEX: '같은 단지에 여러 원천이 연결됨',
}

export function ComplexRunErrors({ executionId, phase }: { executionId: string; phase?: 'collection' | 'refinement' }) {
  const modes: Mode[] = phase === 'collection' ? ['collection'] : phase === 'refinement'
    ? ['complex', 'coordinates', 'household'] : ['collection', 'complex', 'coordinates', 'household']
  const [mode, setMode] = useState<Mode>(modes[0])
  const [page, setPage] = useState(0)
  const [keyword, setKeyword] = useState('')
  const [attempt, setAttempt] = useState(0)
  const searchId = useId()
  const [loaded, setLoaded] = useState<{ query: string; data: FailureReviewPageData } | null>(null)
  const [error, setError] = useState<{ query: string; message: string } | null>(null)
  const query = JSON.stringify([executionId, mode, page, keyword, attempt])
  const data = loaded?.query === query ? loaded.data : null
  const message = error?.query === query ? error.message : null
  const busy = !data && !message
  useEffect(() => {
    const controller = new AbortController()
    async function load() {
      const category = mode === 'coordinates' ? 'complex' : mode
      let result = await getFailureReviews('complex', category, 'ALL', page, controller.signal, { executionId, keyword })
      // 기존 API에는 사유 필터가 없다. 좌표 문제는 모든 페이지를 확인한 뒤 분류한다.
      if (mode === 'coordinates') {
        const rows = [...result.items]
        while (result.hasNext && !controller.signal.aborted) {
          result = await getFailureReviews('complex', 'complex', 'ALL', result.page + 1, controller.signal, { executionId, keyword })
          rows.push(...result.items)
        }
        const items = rows.filter(row => /GEOCODING/.test(row.reason))
        result = { items, page: 0, totalElements: items.length, totalPages: items.length ? 1 : 0, hasNext: false }
      }
      if (!controller.signal.aborted) setLoaded({ query, data: result })
    }
    void load().catch(cause => {
      if (!controller.signal.aborted) setError({ query, message: cause instanceof Error ? cause.message : '오류를 조회하지 못했습니다.' })
    })
    return () => controller.abort()
  }, [executionId, mode, page, keyword, query])

  return <section className={styles.errors} aria-label="이번 실행의 오류">
    <div className={`${styles.helpLabel} ${styles.errorHeading}`}><h3>결과 오류</h3>
      <ComplexStageHelp stage="결과 오류 조회">선택한 실행에서 마지막으로 발생한 오류의 현재 기록입니다. 실행 완료가 개별 오류의 해결을 뜻하지는 않습니다.</ComplexStageHelp>
    </div>
    <div className={styles.errorTools}>
      <label>오류 종류<select value={mode} onChange={event => { setMode(event.target.value as Mode); setPage(0) }}>
        {modes.map(value => <option key={value} value={value}>{labels[value]}</option>)}
      </select></label>
      <form role="search" aria-label="오류 대상 검색" className={styles.searchForm} onSubmit={event => { event.preventDefault(); setPage(0); setKeyword(String(new FormData(event.currentTarget).get('keyword') ?? '').trim()) }}>
        <label htmlFor={searchId}>대상 검색</label><div className={styles.searchField}>
          <input id={searchId} className={styles.searchInput} type="search" name="keyword" maxLength={200} placeholder="이름 또는 원천 식별자 검색" />
          <IconButton data-design-system type="submit" label="검색" title="검색" className={styles.searchButton}>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" aria-hidden="true"><circle cx="10.5" cy="10.5" r="6.5" /><path d="m16 16 4.5 4.5" /></svg>
          </IconButton>
        </div>
      </form>
      <button type="button" disabled={busy} onClick={() => setAttempt(value => value + 1)}>다시 조회</button>
    </div>
    {busy && <p role="status">{mode === 'coordinates' ? '정제 오류 전체에서 좌표 문제를 확인하고 있습니다…' : '오류를 조회하고 있습니다…'}</p>}
    {message && <p role="alert" className="form-error">오류 조회 실패: {message}</p>}
    {data && <>
      <p>{data.totalElements ? `${labels[mode]} ${data.totalElements.toLocaleString('ko-KR')}건` : `이 실행에 기록된 ${labels[mode]}가 없습니다.`}</p>
      <ul className={styles.errorList}>{data.items.map(row => <ErrorItem key={`${row.category}-${row.id}`} row={row} />)}</ul>
      {mode !== 'coordinates' && data.totalPages > 1 && <nav className={styles.errorTools} aria-label="오류 페이지">
        <ComplexIconButton icon="previous" label="이전" disabled={page === 0} onClick={() => setPage(value => value - 1)} />
        <span>{page + 1} / {data.totalPages} 페이지</span>
        <ComplexIconButton icon="next" label="다음" disabled={!data.hasNext} onClick={() => setPage(value => value + 1)} />
      </nav>}
    </>}
  </section>
}

function ErrorItem({ row }: { row: FailureReview }) {
  const target = row.category === 'collection' ? failureTarget(row) : { title: row.target, context: row.sourceKey, source: null }
  const cause = row.category === 'collection' ? collectionCause(row) : {
    title: reasonLabels[row.reason] ?? '원천 데이터 확인 필요',
    action: /GEOCODING/.test(row.reason) ? '원천의 도로명주소와 업로드한 좌표 파일을 확인한 뒤, 필요한 경우 2단계 적재와 3단계 정제를 다시 실행하세요.'
      : '오류에 기록된 원천 값과 공식 자료를 확인한 뒤, 필요한 수집 또는 정제 단계를 다시 실행하세요.',
  }
  return <li><div className={styles.resultHeading}><strong>{target.title}</strong>
    <span>{row.status === 'RESOLVED' ? '해결됨' : row.status === 'SKIPPED' ? '건너뜀' : '미해결'}</span></div>
    <small>{[target.source, target.context].filter(Boolean).join(' · ')}</small>
    <p><strong>{cause.title}</strong></p><p>{row.detail}</p>
    <p className={styles.muted}>다음 행동: {row.status === 'RESOLVED' ? '해결된 기록입니다. 재발 여부는 이후 실행에서 확인하세요.' : cause.action}</p>
  </li>
}
