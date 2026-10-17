import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { reviewTaskPage, taskStates, type TaskPage } from './reviewTaskApi'
import styles from './QualityWorkspace.module.css'

export function ReviewTaskBoard({ revision = 0, expanded = false }: { revision?: number; expanded?: boolean }) {
  const [open, setOpen] = useState(false); const [alerts, setAlerts] = useState(false)
  const [count, setCount] = useState<number | null>(null); const [mine, setMine] = useState(true); const [overdue, setOverdue] = useState(false)
  const [state, setState] = useState(''); const [page, setPage] = useState(0); const [attempt, setAttempt] = useState(0)
  const [result, setResult] = useState<TaskPage | null>(null); const [error, setError] = useState(''); const [alertError, setAlertError] = useState('')
  const [loading, setLoading] = useState(false)
  useEffect(() => {
    let controller: AbortController | null = null
    function refresh() {
      controller?.abort(); controller = new AbortController(); const signal = controller.signal
      void reviewTaskPage(new URLSearchParams(), true, signal).then(value => {
        if (!signal.aborted) { setCount(value.totalElements); setAlertError('') }
      }).catch(() => { if (!signal.aborted) setAlertError('내 담당 알림을 조회하지 못했습니다.') })
    }
    refresh()
    const timer = setInterval(() => { if (document.visibilityState === 'visible') refresh() }, 60000)
    return () => { clearInterval(timer); controller?.abort() }
  }, [revision, attempt])
  useEffect(() => {
    if (!open && !expanded) return
    const controller = new AbortController(); setLoading(true); setError(''); setResult(null)
    void reviewTaskPage(new URLSearchParams({ mine: String(mine), overdue: String(overdue), state, page: String(page) }), alerts, controller.signal)
      .then(value => { if (!controller.signal.aborted) setResult(value) })
      .catch(cause => { if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : '업무 목록을 조회하지 못했습니다.') })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [open, expanded, alerts, mine, overdue, state, page, attempt, revision])
  const Container = expanded ? 'section' : 'details'
  return <Container className={`${styles.taskBoard} ${expanded ? styles.taskPage : ''}`} onToggle={event => setOpen((event.currentTarget as HTMLDetailsElement).open)}>
    {expanded ? <header className={styles.resultHeading}><h2>내 담당 업무</h2><span>{count === null ? '알림 확인 중' : `확인할 알림 ${count}건`}</span></header> : <summary>담당 업무·기한 알림 {count === null ? '' : `· 내 담당 알림 ${count}건`}</summary>}
    <p className={styles.hint}>내 담당 중 기한이 지난 미완료 업무와 확인한 원천의 변경·소실을 표시합니다. 한국 날짜 기준이며 화면을 보고 있는 동안 1분마다 알림 수를 갱신합니다.</p>
    <div className={styles.links}><button aria-pressed={!alerts} onClick={() => { setAlerts(false); setPage(0) }}>업무 목록</button><button aria-pressed={alerts} onClick={() => { setAlerts(true); setPage(0) }}>내 담당 알림 보기</button><button onClick={() => setAttempt(value => value + 1)}>업무·알림 새로고침</button></div>
    {!alerts ? <div className={styles.filters}><label><input type="checkbox" checked={mine} onChange={event => { setMine(event.target.checked); setPage(0) }} />내 담당만</label><label><input type="checkbox" checked={overdue} onChange={event => { setOverdue(event.target.checked); setPage(0) }} />기한 초과만</label><label>업무 상태 필터<select value={state} onChange={event => { setState(event.target.value); setPage(0) }}><option value="">전체 상태</option>{Object.entries(taskStates).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select></label></div> : null}
    {alertError ? <p role="alert">{alertError}</p> : null}{error ? <p role="alert">{error}</p> : null}{loading ? <p role="status">담당 업무 조회 중…</p> : null}
    {result ? <><p>총 {result.totalElements}개 · {result.page + 1}페이지</p><ul className={styles.results}>{result.items.map(task => <li className={styles.card} key={`${task.resource}:${task.id}`}><div>
      <Link to={`/admin/${task.resource === 'COMPLEX' ? 'complexes' : 'announcements'}/${task.id}?section=verification`}>{task.name} 업무·검토</Link>
      <p>{task.assignee || '미배정'} · {taskStates[task.status]} · 기한 {task.dueDate ?? '없음'}</p><p>{task.note}</p>
      <div className={styles.badges}>{task.overdue ? <span>기한 초과</span> : null}{task.sourceChanged ? <span>원천 변경·소실</span> : null}</div>
    </div></li>)}</ul>{!result.items.length ? <p>현재 조건의 담당 업무가 없습니다.</p> : null}
      <nav className={styles.links} aria-label="담당 업무 페이지"><button disabled={page === 0} onClick={() => setPage(value => value - 1)}>이전 업무</button><button disabled={!result.hasNext} onClick={() => setPage(value => value + 1)}>다음 업무</button></nav></> : null}
  </Container>
}
