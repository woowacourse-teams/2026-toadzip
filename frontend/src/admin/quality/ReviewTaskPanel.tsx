import { useEffect, useRef, useState } from 'react'
import { ManagementError } from '../management/api'
import { useUnsavedChanges } from '../management/useUnsavedChanges'
import { loadReviewTask, saveReviewTask, taskAssignees, taskStates, type ReviewTask, type TaskResource, type TaskState } from './reviewTaskApi'
import styles from './QualityWorkspace.module.css'

export function ReviewTaskPanel({ resource, id, disabled = false, onSaved }: {
  resource: TaskResource; id: number; disabled?: boolean; onSaved?: () => void
}) {
  const [task, setTask] = useState<ReviewTask | null>(null); const [names, setNames] = useState<string[]>([])
  const [loaded, setLoaded] = useState(false); const [revision, setRevision] = useState(0)
  const [assignee, setAssignee] = useState(''); const [due, setDue] = useState(''); const [state, setState] = useState<TaskState>('OPEN')
  const [note, setNote] = useState(''); const [dirty, setDirty] = useState(false); const [conflict, setConflict] = useState(false)
  const [busy, setBusy] = useState(false); const [error, setError] = useState(''); const [notice, setNotice] = useState('')
  const request = useRef<AbortController | null>(null)
  useEffect(() => () => request.current?.abort(), [])
  useUnsavedChanges(dirty)
  async function load() {
    if (busy || disabled) return
    const controller = new AbortController(); request.current = controller; setBusy(true); setError('')
    try {
      const [next, accounts] = await Promise.all([loadReviewTask(resource, id, controller.signal), taskAssignees(controller.signal)])
      if (controller.signal.aborted) return
      setTask(next); setRevision(next?.revision ?? 0); setNames(accounts); setConflict(false)
      if (!loaded) { setAssignee(next?.assignee ?? ''); setDue(next?.dueDate ?? ''); setState(next?.status ?? 'OPEN'); setNote(next?.note ?? '') }
      setLoaded(true); setNotice(loaded ? '최신 업무를 확인했습니다. 현재 입력은 유지했습니다. 아래 기존 업무와 비교한 뒤 저장하세요.' : '')
    } catch (cause) { if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : '업무를 조회하지 못했습니다.') }
    finally { if (!controller.signal.aborted) setBusy(false) }
  }
  async function save() {
    if (!loaded || busy || disabled || conflict) return
    const controller = new AbortController(); request.current = controller; setBusy(true); setError(''); setNotice('')
    try {
      const next = await saveReviewTask(resource, id, { expectedRevision: revision, assignee, dueDate: due || null, status: state, note }, controller.signal)
      if (controller.signal.aborted) return
      setTask(next); setRevision(next.revision); setDirty(false); setNotice('담당자·기한·업무 상태를 저장했습니다. 데이터 검증 기록과 등록값은 별도입니다.'); onSaved?.()
    } catch (cause) { if (!controller.signal.aborted) { setError(cause instanceof Error ? cause.message : '업무를 저장하지 못했습니다.'); if (cause instanceof ManagementError && cause.status === 409) setConflict(true) } }
    finally { if (!controller.signal.aborted) setBusy(false) }
  }
  return <details className={`${styles.workspace} ${styles.taskPanel}`}><summary>업무 담당·기한 관리</summary>
    <p className={styles.hint}>담당 업무의 완료와 데이터 검증 완료는 별개입니다. 원천 확인과 값 수정은 비교 화면에서 기록하세요.</p>
    <button disabled={busy || disabled} onClick={() => void load()}>{loaded ? '최신 업무 조회' : '담당 업무 조회'}</button>
    {task ? <div className={styles.taskCurrent}><p>기존 업무: {task.assignee || '미배정'} · {task.dueDate ?? '기한 없음'} · {taskStates[task.status]} · revision {task.revision}</p><p>{task.note}</p>{task.overdue ? <strong>기한 초과</strong> : null}{task.sourceChanged ? <strong>원천 변경·소실</strong> : null}</div> : null}
    {loaded ? <form onSubmit={event => { event.preventDefault(); void save() }} onChange={() => setDirty(true)}>
      <fieldset className={styles.filters} disabled={busy || disabled}><legend>검토 업무</legend>
        <label>담당자<select value={assignee} onChange={event => setAssignee(event.target.value)}><option value="">미배정</option>{names.map(name => <option key={name}>{name}</option>)}</select></label>
        <label>검토 기한 · 한국 날짜<input type="date" value={due} onChange={event => setDue(event.target.value)} /></label>
        <label>업무 상태<select value={state} onChange={event => setState(event.target.value as TaskState)}>{Object.entries(taskStates).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select></label>
        <label>업무 메모<textarea required maxLength={2000} value={note} onChange={event => setNote(event.target.value)} /></label>
        <button disabled={!dirty || !note.trim() || conflict} type="submit">담당 업무 저장</button>
      </fieldset>
    </form> : null}
    {busy ? <p role="status">담당 업무 처리 중…</p> : null}{error ? <p role="alert">{error}</p> : null}{notice ? <p role="status">{notice}</p> : null}
  </details>
}
