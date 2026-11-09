import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Button } from '../../design-system/components/Button'
import { getInitialRegistration, initialRegistrationStatuses, refreshInitialRegistration, type InitialRegistrationResponse, type InitialRegistrationStatus } from './api'
import type { PipelineViewState } from './PipelineResult'
import styles from './InitialRegistrationProgress.module.css'
import stageStyles from './ComplexIngest.module.css'
import { ComplexStageHelp } from './ComplexStageHelp'

const labels: Record<InitialRegistrationStatus, string> = {
  EXISTING: '기존 단지', LOCATION_REQUIRED: '좌표 준비 필요', INFORMATION_REQUIRED: '정보 보완 필요',
  READY: '등록 가능', FAILED: '등록 실패', COMPLETED: '등록 완료',
}

const overviewStatuses: InitialRegistrationStatus[] = ['READY', 'INFORMATION_REQUIRED', 'COMPLETED',
  'FAILED', 'LOCATION_REQUIRED', 'EXISTING']

const pageSizes = [10, 20, 50, 100] as const
const pageSizeStorageKey = 'toadzip:admin:initial-registration:page-size'

function readPageSize(): typeof pageSizes[number] {
  try {
    const stored = localStorage.getItem(pageSizeStorageKey)
    return pageSizes.find(size => String(size) === stored) ?? 20
  } catch {
    return 20
  }
}

export function InitialRegistrationProgress({ disabled, revision, onBusyChange, registration, starting, onRegister, onRefreshRegistration, stopControl, onShowHistory }: {
  disabled: boolean; revision: string; onBusyChange: (busy: boolean) => void
  registration: PipelineViewState; starting: boolean; onRegister: () => void; onRefreshRegistration: () => void; stopControl?: ReactNode; onShowHistory?: () => void
}) {
  const [status, setStatus] = useState<InitialRegistrationStatus | ''>('')
  const [page, setPage] = useState(0)
  const [pageSize, setPageSize] = useState(readPageSize)
  const [result, setResult] = useState<InitialRegistrationResponse | null>(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const execution = registration.execution
  const registering = starting || execution.status === 'RUNNING'
  const [reload, setReload] = useState(0)
  const pending = useRef(false)
  const busy = refreshing || registering
  useEffect(() => {
    let active = true
    setLoading(!registering)
    setError('')
    void getInitialRegistration(status, page, pageSize).then(value => {
      if (active) setResult(value)
    }).catch((cause: unknown) => {
      if (active) setError(cause instanceof Error ? cause.message : '진행 상태를 조회하지 못했습니다.')
    }).finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [status, page, pageSize, revision, reload, registering, execution.finishedAt])

  useEffect(() => {
    if (!registering) return
    const timer = window.setInterval(() => setReload(value => value + 1), 2_000)
    return () => window.clearInterval(timer)
  }, [registering])

  function changePageSize(value: string) {
    const selected = pageSizes.find(size => String(size) === value)
    if (selected === undefined) return
    setPageSize(selected)
    setPage(0)
    try {
      localStorage.setItem(pageSizeStorageKey, String(selected))
    } catch {
      // 저장소가 차단되어도 현재 화면에서는 선택한 건수로 조회한다.
    }
  }

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

  return <section className={styles.panel} aria-labelledby="initial-registration-title" aria-busy={loading || busy}>
    <header className={styles.header}>
      <div className={stageStyles.stageTitle}><span className={stageStyles.number}>3</span>
        <div className={stageStyles.helpLabel}><h2 id="initial-registration-title">신규 단지 등록</h2>
          <ComplexStageHelp stage="신규 단지 등록">등록 가능한 단지 전체를 처리합니다. 목록 필터는 실행 범위를 제한하지 않습니다. 기존 단지와 관리자 보정값은 유지하며, 실패한 단지는 별도로 기록합니다. 화면을 이동해도 실행은 계속됩니다.</ComplexStageHelp>
        </div>
      </div>
      <div className={styles.headerActions}>
        <Button data-design-system disabled={disabled || busy || loading || !!error || !!registration.requestError || !result?.counts.READY}
          onClick={onRegister}>준비된 신규 단지 등록</Button>
        <Button data-design-system variant="secondary" className={styles.refreshButton}
          disabled={disabled || busy} onClick={() => void refresh()}>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" aria-hidden="true">
            <path d="M20 7v5h-5M4 17v-5h5" /><path d="M6.1 7a7 7 0 0 1 11.5-1L20 9M4 15l2.4 3A7 7 0 0 0 17.9 17" />
          </svg>대상 상태 확인
        </Button>
      </div>
    </header>
    {registration.requestError && <div className={styles.error} role="alert">
      <p>{registration.requestError} 마지막 실행 상태를 유지하며 다시 확인합니다.</p>
      <Button data-design-system variant="secondary" onClick={onRefreshRegistration}>실행 상태 다시 조회</Button>
    </div>}
    {execution.status === 'FAILED' && <p className={styles.error} role="alert">{execution.failure?.message ?? '등록 실행에 실패했습니다.'} 완료된 단지는 유지됩니다.</p>}
    {execution.status === 'STOPPED' && <p className={styles.notice} role="status">등록이 중지됐습니다. 완료된 단지는 유지됩니다.</p>}
    {refreshing && <p className={styles.notice} role="status">대상 상태 확인 중…</p>}
    {error && <div role="alert" className={styles.error}>
      <p>{error}</p><Button data-design-system variant="secondary" onClick={() => setReload(value => value + 1)}>상태 다시 조회</Button>
    </div>}
    {registering && <div className={styles.runSummary}>
      <span role="status">{execution.workProgress
        ? `등록 중 ${execution.workProgress.completedCount.toLocaleString()} / ${execution.workProgress.totalCount.toLocaleString()}곳`
        : '등록 준비 중…'}</span>
      {stopControl}
    </div>}
    {!registering && execution.status.startsWith('COMPLETED') && <div className={styles.runSummary}>
      <span role="status" aria-label="등록 실행 상태">등록이 완료되었습니다.</span>
      {onShowHistory && <button type="button" className={styles.historyLink} onClick={onShowHistory}>실행 이력 보기</button>}
    </div>}
    <div className={styles.dashboard}>
      <section className={styles.overview} aria-label="전체 등록 현황">
        <div className={styles.sectionHeading}><h3>전체 현황</h3>{result && <span>{Object.values(result.counts).reduce((sum, count) => sum + count, 0).toLocaleString()}곳</span>}</div>
        {result && <div className={styles.counts} role="group" aria-label="등록 현황">
          {overviewStatuses.map(value => <button key={value} type="button" data-status={value}
            aria-label={`${labels[value]} ${result.counts[value]}곳 보기`} aria-pressed={status === value}
            disabled={busy} onClick={() => { setStatus(current => current === value ? '' : value); setPage(0) }}>
            <span className={styles.countLabel}><span className={styles.dot} aria-hidden="true" />{labels[value]}</span>
            <strong>{result.counts[value].toLocaleString()}<span>곳</span></strong>
          </button>)}
        </div>}
      </section>
    </div>
    <div className={styles.toolbar}>
      <h3>등록 대상{!loading && !error && result && <span>{result.totalElements}곳</span>}</h3>
      <div className={styles.filters}>
        <label className={styles.filter}><span>진행 상태</span>
          <span className={styles.selectWrap}><select value={status} disabled={busy} onChange={event => {
            const selected = initialRegistrationStatuses.find(value => value === event.target.value) ?? ''
            setStatus(selected); setPage(0)
          }}><option value="">전체</option>{initialRegistrationStatuses.map(value => <option key={value} value={value}>{labels[value]}</option>)}</select>
            <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true"><path d="m4 6 4 4 4-4" /></svg>
          </span>
        </label>
        <label className={styles.filter}><span>표시 건수</span>
          <span className={styles.selectWrap}><select className={styles.pageSizeSelect} value={pageSize}
            disabled={busy} onChange={event => changePageSize(event.target.value)}>
            {pageSizes.map(size => <option key={size} value={size}>{size}건씩</option>)}
          </select>
            <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true"><path d="m4 6 4 4 4-4" /></svg>
          </span>
        </label>
      </div>
    </div>
    {loading && <p className={styles.empty} role="status">진행 상태를 불러오고 있습니다.</p>}
    {!loading && !error && result && <>
      {result.targets.length === 0 ? <p className={styles.empty}>{status ? '이 상태의 단지가 없습니다.' : '등록 대상이 없습니다. 원천 수집 후 대상 상태를 확인하세요.'}</p>
        : <div className={styles.targetList}>
          <div className={styles.listHeading} aria-hidden="true"><span>단지</span><span>상태</span><span>확인 사항</span><span /></div>
          <ul className={styles.targets}>{result.targets.map(target => <li key={target.sourceComplexIdentifier}>
            <details className={styles.target}>
              <summary className={styles.targetRow} aria-label={`${target.name || '단지명 미제공'} 상세 정보`} aria-describedby={`registration-state-${target.sourceComplexIdentifier} registration-reason-${target.sourceComplexIdentifier}`}>
                <span className={styles.identity}><strong>{target.name || '단지명 미제공'}</strong><span className={styles.address}>{target.roadAddress || '주소 미제공'}</span></span>
                <span id={`registration-state-${target.sourceComplexIdentifier}`} className={styles.badge} data-status={target.status}>{labels[target.status]}</span>
                <span id={`registration-reason-${target.sourceComplexIdentifier}`} className={styles.rowReason} title={needsReview(target.status) ? target.detail : undefined}>{needsReview(target.status) ? target.detail : ''}</span>
                <svg className={styles.chevron} viewBox="0 0 16 16" aria-hidden="true"><path d="m4 6 4 4 4-4" /></svg>
              </summary>
              <div className={styles.targetDetails}>
                {!needsReview(target.status) && <p className={styles.detail}>{target.detail}</p>}
                <dl className={styles.metadata}>
                  <div><dt>원천 식별자</dt><dd>{target.sourceComplexIdentifier}</dd></div>
                  <div><dt>최종 확인</dt><dd><time dateTime={target.checkedAt}>{new Date(target.checkedAt).toLocaleString('ko-KR')}</time></dd></div>
                  {target.location?.address && <>
                    <div><dt>{target.location.origin === 'ADMIN' ? '관리자 보완 주소' : 'ZIP 준비 주소'}</dt><dd>{target.location.address}</dd></div>
                    <div><dt>위도·경도</dt><dd>{target.location.latitude}, {target.location.longitude}</dd></div>
                  </>}
                </dl>
                {target.housingComplexId !== null && <div className={styles.rowLinks}>
                  <a href={`/admin/complexes/${target.housingComplexId}?section=verification`}>원천·적용값 비교</a>
                  <a href={`/admin/complexes/${target.housingComplexId}?section=history`}>출처·변경 이력</a>
                </div>}
              </div>
            </details>
          </li>)}</ul>
        </div>}
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

function needsReview(status: InitialRegistrationStatus): boolean {
  return status === 'LOCATION_REQUIRED' || status === 'INFORMATION_REQUIRED' || status === 'FAILED'
}
