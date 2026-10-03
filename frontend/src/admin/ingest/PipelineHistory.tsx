import { useEffect, useState } from 'react'
import { getPipelineHistory, type DataPipelineExecution } from './api'
import { PipelineResult } from './PipelineResult'
import { pipelineLabels as labels, pipelineStatusLabels } from './pipelineLabels'
export function PipelineHistory() {
  const [page, setPage] = useState(0)
  const [items, setItems] = useState<DataPipelineExecution[]>([])
  const [selected, setSelected] = useState<DataPipelineExecution | null>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(true)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let active = true
    setBusy(true)
    setError('')
    setSelected(null)
    void getPipelineHistory(page)
      .then((value) => { if (active) setItems(value) })
      .catch((cause) => {
        if (active) setError(cause instanceof Error ? cause.message : '이력을 불러오지 못했습니다.')
      })
      .finally(() => { if (active) setBusy(false) })
    return () => { active = false }
  }, [page, attempt])

  return (
    <section className="admin-detail-section pipeline-history" aria-labelledby="pipeline-history-title">
      <header className="pipeline-history-heading">
        <div>
          <h2 id="pipeline-history-title">실행 이력</h2>
          <p>최근 실행 20건씩 확인할 수 있습니다.</p>
        </div>
        <button type="button" disabled={busy} onClick={() => setAttempt((value) => value + 1)}>
          새로고침
        </button>
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
              <tr key={item.executionId}>
                <td>{labels[item.type]}</td>
                <td><time dateTime={item.startedAt ?? undefined}>
                  {item.startedAt ? new Date(item.startedAt).toLocaleString('ko-KR') : '기록 없음'}
                </time></td>
                <td><span className={`pipeline-badge pipeline-${item.status.toLowerCase()}`}>
                  {pipelineStatusLabels[item.status]}
                </span></td>
                <td><button type="button" className="pipeline-history-detail-button" onClick={() => setSelected(item)}>
                  상세 보기<span className="sr-only">: {labels[item.type]} {item.startedAt}</span>
                </button></td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      ) : null}
      {(page > 0 || items.length > 0) ? (
        <nav className="admin-pagination pipeline-history-pagination" aria-label="실행 이력 페이지">
          <button type="button" disabled={busy || page === 0} onClick={() => setPage((value) => value - 1)}>이전</button>
          <span aria-live="polite">{page + 1} 페이지</span>
          <button type="button" disabled={busy || items.length < 20} onClick={() => setPage((value) => value + 1)}>다음</button>
        </nav>
      ) : null}
      {selected ? (
        <section className="pipeline-history-detail" aria-label="선택한 실행 상세">
          <header><h3>선택한 실행 상세</h3><button type="button" onClick={() => setSelected(null)}>닫기</button></header>
          <p>선택 시점의 실행 기록입니다. 실행 중인 작업의 최신 상태와 중지는 상단에서 확인해 주세요.</p>
          <PipelineResult type={selected.type} state={{ execution: selected, requestError: null, errorResponse: null }}
            stopping={true} onStop={() => {}} />
        </section>
      ) : null}
    </section>
  )
}
