import { useEffect, useState } from 'react'
import { getPipelineHistory, type DataPipelineExecution } from './api'
import { PipelineResult } from './DataPipelineControl'
import { pipelineStatusLabels } from './pipelineLabels'
const labels = {COMPLEX_COLLECTION:'단지 수집',COMPLEX_REFINEMENT:'단지 정제',ANNOUNCEMENT_COLLECTION:'공고 수집',ANNOUNCEMENT_REFINEMENT:'공고 정제'}
export function PipelineHistory() {
  const [page, setPage] = useState(0)
  const [items, setItems] = useState<DataPipelineExecution[]>([])
  const [selected, setSelected] = useState<DataPipelineExecution | null>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(true)
  const [attempt, setAttempt] = useState(0)
  useEffect(() => {
    let active=true;setBusy(true);setError('');setSelected(null)
    void getPipelineHistory(page).then(value => {if(active)setItems(value)}).catch(cause => {if(active)setError(cause instanceof Error ? cause.message : '이력을 불러오지 못했습니다.')}).finally(() => {if(active)setBusy(false)})
    return () => {active=false}
  },[page,attempt])
  return <section className="admin-detail-section"><header className="admin-inline"><h2>실행 이력</h2><button disabled={busy} onClick={() => setAttempt(v => v+1)}>이력 새로고침</button></header>
    {busy ? <p role="status">이력을 불러오는 중…</p> : error ? <p role="alert">{error}</p> : <div className="admin-table-scroll"><table className="admin-table"><thead><tr><th>작업</th><th>실행 시각</th><th>상태</th><th>결과</th></tr></thead>
      <tbody>{items.map(item => <tr key={item.executionId}><td>{labels[item.type]}</td><td>{item.startedAt ? new Date(item.startedAt).toLocaleString('ko-KR') : '기록 없음'}</td><td>{pipelineStatusLabels[item.status]}</td>
        <td><button onClick={() => setSelected(item)}>상세 보기<span className="sr-only">: {labels[item.type]} {item.startedAt}</span></button></td></tr>)}</tbody></table>{!items.length ? <p>실행 이력이 없습니다.</p> : null}</div>}
    <nav className="admin-pagination" aria-label="실행 이력 페이지"><button disabled={busy || page === 0} onClick={() => setPage(v => v-1)}>이전</button><span>{page+1} 페이지</span><button disabled={busy || items.length < 20} onClick={() => setPage(v => v+1)}>다음</button></nav>
    {selected ? <section aria-label="선택한 실행 상세"><p>선택 시점의 실행 기록입니다. 실행 중인 작업의 최신 상태와 중지는 상단에서 확인해 주세요.</p><PipelineResult type={selected.type} state={{execution:selected,requestError:null,errorResponse:null}} stopping={true} onStop={() => {}} /></section> : null}
  </section>
}
