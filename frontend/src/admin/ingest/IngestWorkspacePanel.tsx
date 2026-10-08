import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { IngestFailurePanel } from './IngestFailurePanel'
import { getWorkspace, workspaceStatuses, type IngestDomain, type WorkspacePage } from './workspaceApi'
import { IngestCorrectionForm } from './IngestCorrectionForm'
import styles from './IngestWorkspace.module.css'

export function IngestWorkspacePanel({ domain, disabled, onBusyChange }: {
  domain: IngestDomain; disabled: boolean; onBusyChange?: (busy: boolean) => void
}) {
  const [params, setParams] = useSearchParams()
  const candidate = params.get('workspaceStatus') ?? 'ALL'
  const status = Object.hasOwn(workspaceStatuses, candidate) ? candidate : 'ALL'
  const pageValue = Number(params.get('workspacePage') ?? 0)
  const page = Number.isSafeInteger(pageValue) && pageValue >= 0 && pageValue <= 2_147_483_647 ? pageValue : 0
  const [result, setResult] = useState<WorkspacePage | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [attempt, setAttempt] = useState(0)
  const [selected, setSelected] = useState<string | null>(null)
  const [savedId, setSavedId] = useState<number | null>(null)
  useEffect(() => {
    const controller = new AbortController()
    setLoading(true); setError(''); setResult(null)
    void getWorkspace(domain, status, page, controller.signal).then(value => {
      if (!controller.signal.aborted) setResult(value)
    }).catch(cause => {
      if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : '목록 조회 실패')
    }).finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [domain, status, page, attempt, disabled])
  function move(nextStatus: string, nextPage: number) {
    setParams(previous => { const next = new URLSearchParams(previous)
      next.set('workspaceStatus', nextStatus); next.set('workspacePage', String(nextPage)); return next })
  }
  return <section className={styles.section} aria-label={`${domain === 'complex' ? '단지' : '공고'} 처리 결과`}>
    <h2>처리 결과 · 데이터 보완</h2>
    <p>확보된 원천과 등록 데이터를 확인합니다. API가 반환하지 않은 전체 데이터의 누락 여부는 알 수 없습니다.</p>
    <div className={styles.summary}>
      <button type="button" aria-pressed={status === 'ALL'} onClick={() => move('ALL', 0)}>전체</button>
      {Object.entries(workspaceStatuses).map(([key, label]) => <button key={key} type="button"
        aria-pressed={status === key} onClick={() => move(key, 0)}>{label} {result?.counts[key as keyof typeof workspaceStatuses] ?? '—'}건</button>)}
      <button type="button" onClick={() => setAttempt(value => value + 1)} disabled={loading}>목록 새로고침</button>
    </div>
    {loading && <p role="status">처리 결과를 불러오는 중…</p>}
    {error && <p role="alert">{error}</p>}
    {!loading && !error && result?.items.length === 0 && <p>해당 상태의 데이터가 없습니다.</p>}
    {result && result.items.length > 0 && <div className={styles.scroll}><table className="admin-table">
      <thead><tr><th>이름 · 식별자</th><th>상태</th><th>확인 사항</th><th>작업</th></tr></thead>
      <tbody>{result.items.map(item => <tr key={item.identifier}>
        <td>{item.name ?? '이름 미확인'}<br /><small>{item.identifier}</small></td>
        <td>{workspaceStatuses[item.status]}</td><td>{item.detail}</td>
        <td><button type="button" disabled={disabled} onClick={() => { setSelected(item.identifier); setSavedId(null) }}>
          확인·보완<span className="sr-only"> {item.name ?? item.identifier}</span></button></td>
      </tr>)}</tbody></table></div>}
    {result && <nav aria-label="처리 결과 페이지" className={styles.actions}>
      <button type="button" disabled={loading || page === 0} onClick={() => move(status, page - 1)}>이전</button>
      <span>{page + 1} 페이지 · {result.totalElements}건</span>
      <button type="button" disabled={loading || !result.hasNext} onClick={() => move(status, page + 1)}>다음</button>
    </nav>}
    {selected && <section aria-label="선택한 데이터 보완" className={styles.section}>
      <IngestCorrectionForm key={`${domain}:${selected}`} domain={domain} identifier={selected} disabled={disabled}
        onBusyChange={onBusyChange}
        onClose={() => setSelected(null)} onSaved={id => { setSavedId(id); setAttempt(value => value + 1) }} />
      {savedId && <p role="status">저장 완료. <Link to={`/admin/${domain === 'complex' ? 'complexes' : 'announcements'}/${savedId}`}>
        등록 데이터 관리로 이동</Link></p>}
    </section>}
    <details><summary>수집·정제 실패 이력</summary>
      <p>API 호출 실패는 재수집으로 처리합니다. 정제 실패는 위 목록에서 값을 확인·보완해 주세요.</p>
      <IngestFailurePanel domain={domain} executionId={null} refreshToken={attempt} />
    </details>
  </section>
}
