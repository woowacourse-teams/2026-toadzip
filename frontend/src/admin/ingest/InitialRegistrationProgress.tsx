import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Button } from '../../design-system/components/Button'
import { getInitialRegistration, initialRegistrationStatuses, refreshInitialRegistration, type InitialRegistrationResponse, type InitialRegistrationStatus } from './api'
import type { PipelineViewState } from './PipelineResult'
import styles from './InitialRegistrationProgress.module.css'
import stageStyles from './ComplexIngest.module.css'
import { ComplexStageHelp } from './ComplexStageHelp'
import { RegistrationInformationEditor } from './RegistrationInformationEditor'
import { RegistrationFailureHistory } from './RegistrationFailureHistory'
import recoveryStyles from './RegistrationRecovery.module.css'

const labels: Record<InitialRegistrationStatus, string> = {
  EXISTING: '기존 단지', LOCATION_REQUIRED: '좌표 준비 필요', INFORMATION_REQUIRED: '정보 보완 필요',
  READY: '등록 가능', FAILED: '등록 실패', COMPLETED: '등록 완료',
}

const overviewStatuses: InitialRegistrationStatus[] = ['READY', 'INFORMATION_REQUIRED', 'COMPLETED',
  'FAILED', 'LOCATION_REQUIRED', 'EXISTING']

const pageSizes = [10, 20, 50, 100] as const
const pageSizeStorageKey = 'toadzip:admin:initial-registration:page-size'
const maxRetryTargets = 10000

function readPageSize(): typeof pageSizes[number] {
  try {
    const stored = localStorage.getItem(pageSizeStorageKey)
    return pageSizes.find(size => String(size) === stored) ?? 20
  } catch {
    return 20
  }
}

export function InitialRegistrationProgress({ disabled, revision, onBusyChange, registration, starting, onRegister, onRefreshRegistration, stopControl, onRetry }: {
  disabled: boolean; revision: string; onBusyChange: (busy: boolean) => void
  registration: PipelineViewState; starting: boolean; onRegister: () => void; onRefreshRegistration: () => void; stopControl?: ReactNode; onShowHistory?: () => void
  onRetry?: (identifiers: string[]) => void
}) {
  const [status, setStatus] = useState<InitialRegistrationStatus | ''>('')
  const [page, setPage] = useState(0)
  const [pageSize, setPageSize] = useState(readPageSize)
  const [result, setResult] = useState<InitialRegistrationResponse | null>(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [selectingAll, setSelectingAll] = useState(false)
  const [selected, setSelected] = useState<string[]>([])
  const [editing, setEditing] = useState<string | null>(null)
  const [history, setHistory] = useState<string | null>(null)
  const [notice, setNotice] = useState('')
  const [retryOutcome, setRetryOutcome] = useState('')
  const outcomeDialog = useRef<HTMLDialogElement>(null)
  const backdropPressed = useRef(false)
  const selectionAnchor = useRef<string | null>(null)
  const [retryRequest, setRetryRequest] = useState<{ previousId: string | null; count: number } | null>(null)
  const execution = registration.execution
  const registering = starting || execution.status === 'RUNNING'
  const [reload, setReload] = useState(0)
  const pending = useRef(false)
  const selectionGeneration = useRef(0)
  const busy = refreshing || registering || selectingAll
  const retryTargets = result?.targets.filter(target => target.status !== 'COMPLETED' && target.status !== 'EXISTING'
    && target.housingComplexId === null) ?? []
  const selectedIdentifiers = selected
  const allSelected = retryTargets.length > 0 && retryTargets.every(target => selected.includes(target.sourceComplexIdentifier))
  useEffect(() => { setSelected([]); setNotice(''); setSelectingAll(false); selectionAnchor.current = null; selectionGeneration.current += 1 }, [status])
  useEffect(() => { selectionAnchor.current = null }, [page, pageSize])
  useEffect(() => () => { selectionGeneration.current += 1 }, [])
  async function selectAllTargets() {
    if (disabled || busy || pending.current) return
    pending.current = true
    setSelectingAll(true)
    setError('')
    onBusyChange(true)
    const generation = selectionGeneration.current
    try {
      const first = await getInitialRegistration(status, 0, 200)
      const pages = [first]
      for (let next = 1; next < first.totalPages; next += 1) {
        if (generation !== selectionGeneration.current) return
        const current = await getInitialRegistration(status, next, 200)
        if (current.totalElements !== first.totalElements || current.totalPages !== first.totalPages) {
          throw new Error('대상 목록이 변경되었습니다. 다시 전체 선택해 주세요.')
        }
        pages.push(current)
      }
      const targets = pages.flatMap(value => value.targets)
      if (new Set(targets.map(target => target.sourceComplexIdentifier)).size !== first.totalElements) {
        throw new Error('대상 목록이 변경되었습니다. 다시 전체 선택해 주세요.')
      }
      const identifiers = [...new Set(targets
        .filter(target => target.status !== 'COMPLETED' && target.status !== 'EXISTING' && target.housingComplexId === null)
        .map(target => target.sourceComplexIdentifier))]
      if (identifiers.length > maxRetryTargets) throw new Error('한 번에 최대 10,000곳까지 선택할 수 있습니다. 상태별로 나누어 처리해 주세요.')
      if (generation !== selectionGeneration.current) return
      setSelected(identifiers)
      selectionAnchor.current = null
      setNotice(`현재 필터의 재처리 대상 ${identifiers.length.toLocaleString()}곳을 선택했습니다.`)
    } catch (cause: unknown) {
      if (generation === selectionGeneration.current) setError(cause instanceof Error ? cause.message : '전체 선택에 실패했습니다.')
    } finally {
      pending.current = false
      if (generation === selectionGeneration.current) setSelectingAll(false)
      onBusyChange(false)
    }
  }
  function selectTarget(identifier: string, checked: boolean, shift: boolean) {
    setNotice('')
    const identifiers = retryTargets.map(target => target.sourceComplexIdentifier)
    const anchor = selectionAnchor.current === null ? -1 : identifiers.indexOf(selectionAnchor.current)
    const index = identifiers.indexOf(identifier)
    const affected = shift && anchor !== -1 && index !== -1
      ? identifiers.slice(Math.min(anchor, index), Math.max(anchor, index) + 1) : [identifier]
    if (!shift || anchor === -1) selectionAnchor.current = identifier
    setSelected(current => checked ? [...new Set([...current, ...affected])] : current.filter(value => !affected.includes(value)))
  }
  function outsideDialog(element: HTMLDialogElement, x: number, y: number) {
    const bounds = element.getBoundingClientRect()
    return x < bounds.left || x > bounds.right || y < bounds.top || y > bounds.bottom
  }
  function retry(identifiers: string[]) {
    setRetryOutcome('')
    setRetryRequest({ previousId: execution.executionId, count: identifiers.length })
    setNotice(`${identifiers.length}곳의 재처리를 요청했습니다. 결과를 확인하고 있습니다.`)
    onRetry?.(identifiers)
    setSelected([])
  }
  useEffect(() => {
    if (retryRequest && registration.requestError) {
      setNotice('재처리 요청의 결과를 확인하지 못했습니다. 실행 상태를 다시 조회하세요.')
      setRetryRequest(null)
      return
    }
    if (!retryRequest || !execution.executionId || execution.executionId === retryRequest.previousId || registering) return
    if (execution.status.startsWith('COMPLETED')) {
      setNotice('')
      setRetryOutcome(execution.status === 'COMPLETED'
        ? `${retryRequest.count}곳의 재처리가 끝났습니다. 아래 목록에서 현재 상태를 확인하세요.`
        : `${retryRequest.count}곳의 재처리가 끝났지만 확인이 필요한 대상이 있습니다. 확인 사항과 이력을 확인하세요.`)
      setRetryRequest(null)
    } else if (execution.status === 'FAILED' || execution.status === 'STOPPED') {
      setNotice('재처리가 완료되지 않았습니다. 실행 상태를 확인하세요.')
      setRetryRequest(null)
    }
  }, [execution.executionId, execution.status, registering, registration.requestError, retryRequest])
  useEffect(() => {
    if (retryOutcome && !outcomeDialog.current?.open) outcomeDialog.current?.showModal()
  }, [retryOutcome])
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
        <Button data-design-system variant="secondary" className={styles.refreshButton} data-refreshing={refreshing}
          disabled={disabled || busy} onClick={() => void refresh()}>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" aria-hidden="true">
            <path d="M20 7v5h-5M4 17v-5h5" /><path d="M6.1 7a7 7 0 0 1 11.5-1L20 9M4 15l2.4 3A7 7 0 0 0 17.9 17" />
          </svg><span role="status">{refreshing ? '대상 상태 확인 중…' : '대상 상태 확인'}</span>
        </Button>
      </div>
    </header>
    {registration.requestError && <div className={styles.error} role="alert">
      <p>{registration.requestError} 마지막 실행 상태를 유지하며 다시 확인합니다.</p>
      <Button data-design-system variant="secondary" onClick={onRefreshRegistration}>실행 상태 다시 조회</Button>
    </div>}
    {execution.status === 'FAILED' && <p className={styles.error} role="alert">{execution.failure?.message ?? '등록 실행에 실패했습니다.'} 완료된 단지는 유지됩니다.</p>}
    {execution.status === 'STOPPED' && <p className={styles.notice} role="status">등록이 중지됐습니다. 완료된 단지는 유지됩니다.</p>}
    {notice && <p className={styles.notice} role="status">{notice}</p>}
    <dialog ref={outcomeDialog} className={styles.outcomeDialog} aria-labelledby="retry-outcome-title"
      aria-describedby="retry-outcome-message" onClose={() => { setRetryOutcome(''); backdropPressed.current = false }}
      onPointerDown={event => { backdropPressed.current = event.target === event.currentTarget && outsideDialog(event.currentTarget, event.clientX, event.clientY) }}
      onPointerCancel={() => { backdropPressed.current = false }}
      onClick={event => {
        if (backdropPressed.current && event.target === event.currentTarget && outsideDialog(event.currentTarget, event.clientX, event.clientY)) event.currentTarget.close()
        backdropPressed.current = false
      }}>
      <h3 id="retry-outcome-title">재처리 결과</h3>
      <p id="retry-outcome-message">{retryOutcome}</p>
      <Button data-design-system onClick={() => outcomeDialog.current?.close()}>확인</Button>
    </dialog>
    {error && <div role="alert" className={styles.error}>
      <p>{error}</p><Button data-design-system variant="secondary" onClick={() => setReload(value => value + 1)}>상태 다시 조회</Button>
    </div>}
    {registering && <div className={styles.runSummary}>
      <span role="status">{execution.workProgress
        ? `등록 중 ${execution.workProgress.completedCount.toLocaleString()} / ${execution.workProgress.totalCount.toLocaleString()}곳`
        : '등록 준비 중…'}</span>
      {stopControl}
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
    {!loading && !error && result && (selected.length > 0 || result.counts.READY + result.counts.FAILED + result.counts.INFORMATION_REQUIRED + result.counts.LOCATION_REQUIRED > 0) && <div className={recoveryStyles.actions}>
      <Button data-design-system variant="secondary" disabled={disabled || busy || !retryTargets.length} onClick={() => {
        const pageIds = retryTargets.map(target => target.sourceComplexIdentifier)
        setSelected(current => allSelected ? current.filter(id => !pageIds.includes(id)) : [...new Set([...current, ...pageIds])]); selectionAnchor.current = null; setNotice('')
      }}>{allSelected ? '이 페이지 전체 선택 해제' : '이 페이지 전체 선택'}</Button>
      <Button data-design-system variant="secondary" disabled={disabled || busy} onClick={() => void selectAllTargets()}>{selectingAll ? '전체 대상 확인 중…' : '등록 대상 전체 선택'}</Button>
      {!!selected.length && <Button data-design-system variant="secondary" disabled={disabled || busy} onClick={() => { setSelected([]); setNotice(''); selectionAnchor.current = null }}>선택 모두 해제</Button>}
      <Button data-design-system disabled={disabled || busy || !selectedIdentifiers.length || selectedIdentifiers.length > maxRetryTargets || !onRetry || !!registration.requestError}
        onClick={() => retry(selectedIdentifiers)}>선택한 {selectedIdentifiers.length.toLocaleString()}곳 다시 처리</Button>
      {selectedIdentifiers.length > maxRetryTargets && <span role="alert">한 번에 최대 10,000곳까지 처리할 수 있습니다.</span>}
      <span className={recoveryStyles.help}><ComplexStageHelp stage="대상 선택">현재 필터의 재처리 대상을 최대 10,000곳까지 선택할 수 있습니다. 페이지를 이동해도 선택이 유지됩니다. Shift 클릭으로 현재 페이지에서 연속 선택할 수 있습니다.</ComplexStageHelp></span>
    </div>}
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
            {retryTargets.some(value => value.sourceComplexIdentifier === target.sourceComplexIdentifier) && <label className={styles.rowSelection}>
              <input type="checkbox" disabled={disabled || busy} checked={selectedIdentifiers.includes(target.sourceComplexIdentifier)}
                aria-label={`${target.name || '단지명 미제공'} 재처리 선택`} onChange={event => selectTarget(target.sourceComplexIdentifier,
                  event.target.checked, (event.nativeEvent as MouseEvent).shiftKey)} />
            </label>}
            <details className={styles.target}>
              <summary className={styles.targetRow} aria-label={`${target.name || '단지명 미제공'} 상세 정보`} aria-describedby={`registration-state-${target.sourceComplexIdentifier} registration-reason-${target.sourceComplexIdentifier}`}>
                <span className={styles.identity}><strong>{target.name || '단지명 미제공'}</strong><span className={styles.address}>{target.roadAddress || '주소 미제공'}</span></span>
                <span id={`registration-state-${target.sourceComplexIdentifier}`} className={styles.badge} data-status={target.status}>{labels[target.status]}</span>
                <span id={`registration-reason-${target.sourceComplexIdentifier}`} className={styles.rowReason} title={needsReview(target.status) ? target.detail : undefined}>{needsReview(target.status) ? target.detail : ''}</span>
                <svg className={styles.chevron} viewBox="0 0 16 16" aria-hidden="true"><path d="m4 6 4 4 4-4" /></svg>
              </summary>
              <div className={styles.targetDetails}>
                <div className={recoveryStyles.actions}>
                {retryTargets.some(value => value.sourceComplexIdentifier === target.sourceComplexIdentifier) && <>
                  <Button data-design-system variant="secondary" disabled={disabled || busy || !onRetry || !!registration.requestError}
                    onClick={() => retry([target.sourceComplexIdentifier])}>이 단지 다시 처리</Button>
                  <Button data-design-system variant="secondary" disabled={disabled || busy} onClick={() => { setHistory(null); setEditing(current => current === target.sourceComplexIdentifier ? null : target.sourceComplexIdentifier) }}>등록 정보 보완</Button>
                  {target.status === 'LOCATION_REQUIRED' && <a href="#complex-location">주소·좌표 보완으로 이동</a>}
                </>}
                <Button data-design-system variant="secondary" onClick={() => { setEditing(null); setHistory(current => current === target.sourceComplexIdentifier ? null : target.sourceComplexIdentifier) }}
                  aria-expanded={history === target.sourceComplexIdentifier}>보류·실패 이력</Button>
                </div>
                {editing === target.sourceComplexIdentifier && <RegistrationInformationEditor identifier={target.sourceComplexIdentifier}
                  disabled={disabled || busy} onBusyChange={onBusyChange} onCancel={() => setEditing(null)}
                  onSaved={() => { setEditing(null); setReload(value => value + 1); setNotice('보완값을 저장하고 검증했습니다. 현재 상태와 남은 사유를 확인하세요.') }} />}
                {history === target.sourceComplexIdentifier && <RegistrationFailureHistory identifier={target.sourceComplexIdentifier} />}
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
