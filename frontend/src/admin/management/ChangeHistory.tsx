import { useEffect, useState } from 'react'
import { getManagementHistory } from './api'
import { type ManagementChange, type ManagementResource } from './managementContract'
import { labels } from './fields'
export function ChangeHistory({ resource, id, version }: {resource:ManagementResource;id:string;version:number}) {
  const [items, setItems] = useState<ManagementChange[]>([])
  const [page, setPage] = useState(0)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(true)
  const [attempt, setAttempt] = useState(0)
  useEffect(() => {
    let active = true; setBusy(true); setError('')
    void getManagementHistory(resource,id,page).then(value => { if(active) setItems(value) }).catch(cause => {
      if(active) setError(cause instanceof Error ? cause.message : '이력을 불러오지 못했습니다.')
    }).finally(() => { if(active) setBusy(false) })
    return () => { active = false }
  },[resource,id,page,version,attempt])
  return <section><h2>수정 이력</h2>{error ? <div role="alert">{error}<button onClick={() => setAttempt(v => v+1)}>다시 불러오기</button></div> : null}
    {busy ? <p role="status">이력을 불러오는 중…</p> : !error && !items.length ? <p>아직 관리자 변경 이력이 없습니다.</p> : null}
    {!busy && !error ? items.map(item => <details key={item.id} className="admin-change"><summary>{labels[item.action] ?? item.action} · {item.actor} · {new Date(item.occurredAt).toLocaleString('ko-KR')}</summary>
      <div className="admin-change-values"><div><h3>변경 전</h3><pre>{pretty(item.beforeValue)}</pre></div><div><h3>변경 후</h3><pre>{pretty(item.afterValue)}</pre></div></div></details>) : null}
    <div className="admin-pagination"><button disabled={page === 0 || busy} onClick={() => setPage(v => v-1)}>이전 이력</button><span>{page+1} 페이지</span><button disabled={items.length < 20 || busy} onClick={() => setPage(v => v+1)}>다음 이력</button></div></section>
}
function pretty(text:string) { try { return JSON.stringify(JSON.parse(text),null,2) } catch { return text } }
