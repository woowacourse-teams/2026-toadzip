import { useEffect, useId, useRef, useState } from 'react'
import { getPipelineHistory, type DataPipelineExecution } from './api'
import { PipelineResult } from './PipelineResult'
import { PipelineExecutionSteps } from './PipelineExecutionSteps'
import { pipelineLabels as labels, pipelineStatusLabels } from './pipelineLabels'
import { ComplexExecutionResult } from './ComplexExecutionResult'
import { ComplexIconButton } from './ComplexIconButton'
import styles from './ComplexIngest.module.css'
import listStyles from './PipelineHistory.module.css'
import { ComplexStatusBadge } from './ComplexStatusBadge'
export function PipelineHistory({ domain, compact = false, readOnlyComplex = false, onShowRegistration }: { domain?: 'complex' | 'announcement'; compact?: boolean; readOnlyComplex?: boolean; onShowRegistration?: () => void } = {}) {
  const [page, setPage] = useState(0)
  const [items, setItems] = useState<DataPipelineExecution[]>([])
  const [selected, setSelected] = useState<DataPipelineExecution | null>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(true)
  const [attempt, setAttempt] = useState(0)
  const detailId = useId()
  const detailHeading = useRef<HTMLHeadingElement>(null)
  const detailTrigger = useRef<HTMLButtonElement | null>(null)
  // 공고 수집 화면은 이력과 실행 상세를 다른 관리자 목록처럼 각각 흰 카드로 둔다. 단지 수집은 이력 창 안에 그대로 둔다.
  const listCard = compact && !readOnlyComplex

  // 두 화면 모두 상세가 누른 행에서 떨어져 열리므로 상세 제목으로 옮긴다.
  useEffect(() => {
    if ((compact || readOnlyComplex) && selected) {
      detailHeading.current?.focus({ preventScroll: true })
      detailHeading.current?.scrollIntoView?.({ block: 'start' })
    }
  }, [compact, readOnlyComplex, selected])

  useEffect(() => {
    let active = true
    setBusy(true)
    setError('')
    setSelected(null)
    const history = domain ? getPipelineHistory(page, domain) : getPipelineHistory(page)
    void history
      .then((value) => { if (active) setItems(value) })
      .catch((cause) => {
        if (active) setError(cause instanceof Error ? cause.message : '이력을 불러오지 못했습니다.')
      })
      .finally(() => { if (active) setBusy(false) })
    return () => { active = false }
  }, [page, attempt, domain])

  function closeDetail() {
    setSelected(null)
    detailTrigger.current?.focus()
  }

  const detail = selected ? listCard ? (
    <section id={detailId} className={`admin-detail-section ${listStyles.detailCard}`} aria-labelledby={`${detailId}-title`}>
      <header className={listStyles.detailHeading}>
        <h2 id={`${detailId}-title`} ref={detailHeading} tabIndex={-1} className={listStyles.detailTitle}>실행 상세</h2>
        <button type="button" onClick={closeDetail}>닫기</button>
      </header>
      {selected.status === 'RUNNING' && <p className={listStyles.meta}>선택 시점의 기록입니다. 최신 진행과 중지는 위 기관 카드의 실행 결과에서 확인해 주세요.</p>}
      <PipelineExecutionSteps key={selected.executionId} execution={selected} focused />
    </section>
  ) : (
    <section id={detailId} className="pipeline-history-detail" aria-label="선택한 실행 상세">
      <header><h3 ref={detailHeading} tabIndex={readOnlyComplex ? -1 : undefined}>선택한 실행 상세</h3>{readOnlyComplex ? <ComplexIconButton icon="close" label="닫기" onClick={closeDetail} />
        : <button type="button" onClick={() => setSelected(null)}>닫기</button>}</header>
      <p>{readOnlyComplex ? selected.type === 'COMPLEX_REGISTRATION'
        ? '선택한 등록 실행에 저장된 단계별 결과입니다.'
        : '선택한 실행에 저장된 단계별 결과입니다. 오류 조회는 이 실행에 연결된 대상의 현재 검토 상태를 보여줍니다.'
        : '선택 시점의 실행 기록입니다. 실행 중인 작업의 최신 상태와 중지는 상단에서 확인해 주세요.'}</p>
      {readOnlyComplex ? <ComplexExecutionResult key={selected.executionId} execution={selected} onShowRegistration={onShowRegistration} />
        : <PipelineResult type={selected.type} state={{ execution: selected, requestError: null, errorResponse: null }}
          stopping={true} onStop={() => {}} />}
    </section>
  ) : null

  return (
    <>
    <section className={listCard ? `admin-list-card pipeline-history ${listStyles.listCard}` : 'admin-detail-section pipeline-history'} aria-labelledby="pipeline-history-title">
      <header className="pipeline-history-heading">
        <div>
          <h2 id="pipeline-history-title">실행 이력</h2>
          <p>최근 실행 20건씩 확인할 수 있습니다.</p>
        </div>
        {readOnlyComplex ? <ComplexIconButton icon="refresh" label="새로고침" disabled={busy} onClick={() => setAttempt(value => value + 1)} /> : <button type="button" disabled={busy} onClick={() => setAttempt((value) => value + 1)}>
          새로고침
        </button>}
      </header>
      {busy ? <p role="status">이력을 불러오는 중…</p> : null}
      {error ? <p role="alert" className="pipeline-history-error">{error}</p> : null}
      {!busy && !error && items.length === 0 ? (
        <p className="pipeline-history-empty">실행 이력이 없습니다.</p>
      ) : null}
      {!busy && !error && items.length > 0 ? (
        <div className="admin-table-scroll pipeline-history-table">
          <table className="admin-table">
            <thead><tr><th scope="col">작업</th><th scope="col">실행 시각</th><th scope="col">상태</th><th scope="col">결과</th></tr></thead>
            <tbody>{items.map((item) => (
              <tr key={item.executionId} className={(compact || readOnlyComplex) && selected?.executionId === item.executionId ? styles.selectedHistoryRow : undefined}>
                <td>{labels[item.type]}</td>
                <td><time dateTime={item.startedAt ?? undefined}>
                  {item.startedAt ? new Date(item.startedAt).toLocaleString('ko-KR') : '기록 없음'}
                </time></td>
                <td>{readOnlyComplex ? <ComplexStatusBadge status={item.status === 'RUNNING' && item.stopRequested ? 'stopping' : item.status} /> : <span className={`pipeline-badge pipeline-${item.status.toLowerCase()}`}>
                  {pipelineStatusLabels[item.status]}
                </span>}</td>
                <td><button type="button" className="pipeline-history-detail-button"
                  aria-expanded={readOnlyComplex ? selected?.executionId === item.executionId : undefined}
                  aria-controls={readOnlyComplex ? detailId : undefined}
                  title={readOnlyComplex ? selected?.executionId === item.executionId ? '실행 상세 접기' : '목록 아래에 실행 상세 펼치기' : undefined}
                  onClick={event => {
                    detailTrigger.current = event.currentTarget
                    setSelected(previous => readOnlyComplex && previous?.executionId === item.executionId ? null : item)
                  }}>
                  {readOnlyComplex ? selected?.executionId === item.executionId ? '상세 접기' : '상세 펼치기' : '상세 보기'}
                  {readOnlyComplex && <svg className={styles.historyChevron} width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="m6 9 6 6 6-6" /></svg>}
                  <span className="sr-only">: {labels[item.type]} {item.startedAt}</span>
                </button></td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      ) : null}
      {(page > 0 || items.length > 0) ? (
        <nav className="admin-pagination pipeline-history-pagination" aria-label="실행 이력 페이지">
          {readOnlyComplex ? <ComplexIconButton icon="previous" label="이전" disabled={busy || page === 0} onClick={() => setPage(value => value - 1)} />
            : <button type="button" disabled={busy || page === 0} onClick={() => setPage((value) => value - 1)}>이전</button>}
          <span aria-live="polite">{page + 1} 페이지</span>
          {readOnlyComplex ? <ComplexIconButton icon="next" label="다음" disabled={busy || items.length < 20} onClick={() => setPage(value => value + 1)} />
            : <button type="button" disabled={busy || items.length < 20} onClick={() => setPage((value) => value + 1)}>다음</button>}
        </nav>
      ) : null}
      {listCard ? null : detail}
    </section>
    {listCard ? detail : null}
    </>
  )
}
