import { useEffect, useState } from 'react'
import { Link, useLocation } from 'react-router'
import { taskAssignees } from '../quality/reviewTaskApi'
import { workDetailUrl } from '../shared/workNavigation'
import { useUnsavedChanges } from '../management/useUnsavedChanges'
import { ManagementError } from '../management/api'
import { feedbackStates, getFeedback, saveFeedback, type FeedbackEntry, type FeedbackInput, type FeedbackState } from './api'
import { FeedbackRelatedData } from './FeedbackRelatedData'
import styles from './FeedbackListPage.module.css'

function input(entry: FeedbackEntry): FeedbackInput {
  return { version: entry.version ?? 0, status: entry.status ?? 'OPEN', assignee: entry.assignee ?? '',
    note: entry.note ?? '', relatedResource: entry.relatedResource ?? '', relatedId: entry.relatedId ?? null }
}
export function FeedbackProcessingPanel({ entry, onSaved }: { entry: FeedbackEntry; onSaved: (entry: FeedbackEntry) => void }) {
  const [current, setCurrent] = useState(entry)
  const [draft, setDraft] = useState(() => input(entry))
  const [assignees, setAssignees] = useState<string[]>([])
  const [error, setError] = useState(''); const [directoryError, setDirectoryError] = useState('')
  const [busy, setBusy] = useState(false); const [conflict, setConflict] = useState(false); const [notice, setNotice] = useState('')
  const location = useLocation()
  const dirty = JSON.stringify(draft) !== JSON.stringify(input(current))
  useUnsavedChanges(dirty)
  useEffect(() => {
    const controller = new AbortController()
    void taskAssignees(controller.signal).then(value => { if (!controller.signal.aborted) setAssignees(value) })
      .catch(() => { if (!controller.signal.aborted) setDirectoryError('담당자 목록을 불러오지 못했습니다. 현재 담당자는 유지됩니다.') })
    return () => controller.abort()
  }, [])
  async function save() {
    setBusy(true); setError(''); setNotice('')
    try {
      const value = await saveFeedback(entry.id, draft)
      setCurrent(value); setDraft(input(value)); setConflict(false); setNotice('처리 기록을 저장했습니다.'); onSaved(value)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '처리 내용을 저장하지 못했습니다.')
      setConflict(cause instanceof ManagementError && cause.status === 409)
    } finally { setBusy(false) }
  }
  async function refresh() {
    setBusy(true); setError('')
    try {
      const value = await getFeedback(entry.id)
      setCurrent(value); setDraft(previous => ({ ...previous, version: value.version })); setConflict(false)
      setNotice(`최신 상태: ${feedbackStates[value.status]} · 담당자 ${value.assignee || '미배정'} · 관련 대상 ${value.relatedResource || '없음'} ${value.relatedId ?? ''} · ${value.note || '처리 메모 없음'}. 작성 중인 내용을 유지했습니다. 비교 후 저장하세요.`)
    } catch (cause) { setError(cause instanceof Error ? cause.message : '최신 의견을 확인하지 못했습니다.') }
    finally { setBusy(false) }
  }
  return <form className={styles.processing} aria-label={`의견 ${entry.id} 처리`} onSubmit={event => { event.preventDefault(); void save() }}>
    <fieldset disabled={busy}><legend>처리 기록</legend>
      <div className={styles.processingFields}>
        <label>처리 상태<select value={draft.status} onChange={event => setDraft({ ...draft, status: event.target.value as FeedbackState })}>{Object.entries(feedbackStates).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
        <label>담당자<select value={draft.assignee} onChange={event => setDraft({ ...draft, assignee: event.target.value })}><option value="">미배정</option>{[...new Set([...assignees, ...(draft.assignee ? [draft.assignee] : [])])].map(name => <option key={name}>{name}</option>)}</select></label>
        <label>관련 대상<select value={draft.relatedResource} onChange={event => setDraft({ ...draft, relatedResource: event.target.value as FeedbackInput['relatedResource'], relatedId: null })}><option value="">없음</option><option value="COMPLEX">단지</option><option value="ANNOUNCEMENT">공고</option></select></label>

      </div>
      {draft.relatedResource && <FeedbackRelatedData key={draft.relatedResource} resource={draft.relatedResource} id={draft.relatedId} onSelect={id => setDraft({ ...draft, relatedId: id })} />}
      <label>처리 결과·보류 이유<textarea maxLength={5000} rows={4} required={draft.status === 'DONE' || draft.status === 'ON_HOLD'} value={draft.note} onChange={event => setDraft({ ...draft, note: event.target.value })} /></label>
      {directoryError ? <p role="alert">{directoryError}</p> : null}
      {current.relatedResource && current.relatedId ? <p><Link to={workDetailUrl(current.relatedResource, current.relatedId, `${location.pathname}${location.search}`, 'info')}>관련 {current.relatedResource === 'COMPLEX' ? '단지' : '공고'} 확인</Link></p> : null}
      {current.processedAt ? <p>최근 처리: {current.actor} · {new Date(current.processedAt).toLocaleString('ko-KR')}</p> : null}
      <p>처리 완료는 내부 기록입니다. 사용자에게 답변을 발송하지 않습니다.</p>
      {error ? <p role="alert">{error}</p> : null}{notice ? <p role="status">{notice}</p> : null}
      <button type="submit" disabled={!dirty || conflict || !!draft.relatedResource && !draft.relatedId}>{busy ? '저장 중…' : '처리 기록 저장'}</button>
      {conflict ? <button type="button" onClick={() => void refresh()}>입력을 유지하고 최신 상태 확인</button> : null}
    </fieldset>
  </form>
}
