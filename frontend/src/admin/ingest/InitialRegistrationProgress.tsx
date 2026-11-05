import { useEffect, useRef, useState } from 'react'
import { Button } from '../../design-system/components/Button'
import { getInitialRegistration, initialRegistrationStatuses, refreshInitialRegistration, type InitialRegistrationResponse, type InitialRegistrationStatus } from './api'
import styles from './InitialRegistrationProgress.module.css'

const labels: Record<InitialRegistrationStatus, string> = {
  EXISTING: '기존 단지', LOCATION_REQUIRED: '좌표 준비 필요', INFORMATION_REQUIRED: '정보 보완 필요',
  READY: '등록 가능', FAILED: '등록 실패', COMPLETED: '등록 완료',
}

export function InitialRegistrationProgress({ disabled, revision, onBusyChange }: {
  disabled: boolean; revision: string; onBusyChange: (busy: boolean) => void
}) {
  const [status, setStatus] = useState<InitialRegistrationStatus | ''>('')
  const [page, setPage] = useState(0)
  const [result, setResult] = useState<InitialRegistrationResponse | null>(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [reload, setReload] = useState(0)
  const pending = useRef(false)
  useEffect(() => {
    let active = true
    setLoading(true)
    setError('')
    void getInitialRegistration(status, page).then(value => {
      if (active) setResult(value)
    }).catch((cause: unknown) => {
      if (active) setError(cause instanceof Error ? cause.message : '진행 상태를 조회하지 못했습니다.')
    }).finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [status, page, revision, reload])

  async function refresh() {
    if (disabled || pending.current) return
    pending.current = true
    setRefreshing(true)
    onBusyChange(true)
    setError('')
    try {
      await refreshInitialRegistration()
      setPage(0)
      setReload(value => value + 1)
    } catch (cause: unknown) {
      setError(cause instanceof Error ? cause.message : '대상 상태 확인에 실패했습니다.')
    } finally {
      pending.current = false
      setRefreshing(false)
      onBusyChange(false)
    }
  }

  return <section className={styles.panel} aria-labelledby="initial-registration-title" aria-busy={loading || refreshing}>
    <header className={styles.header}>
      <div><h2 id="initial-registration-title">최초 등록 진행 상태</h2>
        <p className={styles.description}>단지별 등록 여부와 다음에 필요한 작업을 확인하세요.</p></div>
      <Button data-design-system variant="secondary" className={styles.refreshButton}
        disabled={disabled || refreshing} onClick={() => void refresh()}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" aria-hidden="true">
          <path d="M20 7v5h-5M4 17v-5h5" /><path d="M6.1 7a7 7 0 0 1 11.5-1L20 9M4 15l2.4 3A7 7 0 0 0 17.9 17" />
        </svg>대상 상태 확인
      </Button>
    </header>
    {refreshing && <p className={styles.notice} role="status">현재 원천과 기존 단지를 확인하고 있습니다.</p>}
    {error && <div role="alert" className={styles.error}>
      <p>{error}</p><Button data-design-system variant="secondary" onClick={() => setReload(value => value + 1)}>상태 다시 조회</Button>
    </div>}
    {result && <dl className={styles.counts} aria-label="상태별 단지 수">
      {initialRegistrationStatuses.map(value => <div key={value} data-status={value}>
        <dt><span className={styles.dot} aria-hidden="true" />{labels[value]}</dt>
        <dd>{result.counts[value]}<span>곳</span></dd>
      </div>)}
    </dl>}
    <div className={styles.toolbar}>
      <h3>대상 목록{!loading && !error && result && <span>{result.totalElements}곳</span>}</h3>
      <label className={styles.filter}><span>진행 상태</span>
        <span className={styles.selectWrap}><select value={status} disabled={refreshing} onChange={event => {
          const selected = initialRegistrationStatuses.find(value => value === event.target.value) ?? ''
          setStatus(selected); setPage(0)
        }}><option value="">전체</option>{initialRegistrationStatuses.map(value => <option key={value} value={value}>{labels[value]}</option>)}</select>
          <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true"><path d="m4 6 4 4 4-4" /></svg>
        </span>
      </label>
    </div>
    {loading && <p className={styles.empty} role="status">진행 상태를 불러오고 있습니다.</p>}
    {!loading && !error && result && <>
      {result.targets.length === 0 ? <p className={styles.empty}>{status ? '이 상태의 단지가 없습니다.' : '등록 대상이 없습니다. 원천 수집 후 대상 상태를 확인하세요.'}</p>
        : <ul className={styles.targets}>{result.targets.map(target => <li key={target.sourceComplexIdentifier}>
          <div className={styles.targetHeading}>
            <div><strong>{target.name || '단지명 미제공'}</strong><p className={styles.address}>{target.roadAddress || '주소 미제공'}</p></div>
            <span className={styles.badge} data-status={target.status}>{labels[target.status]}</span>
          </div>
          <p className={styles.detail} data-status={target.status}>{target.detail}</p>
          <dl className={styles.metadata}>
            <div><dt>원천</dt><dd>{target.sourceComplexIdentifier}</dd></div>
            {target.housingComplexId !== null && <div><dt>서비스 단지</dt><dd>{target.housingComplexId}</dd></div>}
            <div><dt>확인</dt><dd><time dateTime={target.checkedAt}>{new Date(target.checkedAt).toLocaleString('ko-KR')}</time></dd></div>
          </dl>
        </li>)}</ul>}
      <nav className={styles.pagination} aria-label="등록 대상 페이지">
        <Button data-design-system variant="secondary" className={styles.pageButton} aria-label="이전 대상" title="이전 대상"
          disabled={page === 0} onClick={() => setPage(value => value - 1)}>
          <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true"><path d="m10 4-4 4 4 4" /></svg>
        </Button>
        <span>{result.totalPages === 0 ? 0 : page + 1} / {result.totalPages}</span>
        <Button data-design-system variant="secondary" className={styles.pageButton} aria-label="다음 대상" title="다음 대상"
          disabled={page + 1 >= result.totalPages} onClick={() => setPage(value => value + 1)}>
          <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true"><path d="m6 4 4 4-4 4" /></svg>
        </Button>
      </nav>
    </>}
  </section>
}
