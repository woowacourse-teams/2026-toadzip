import { useState } from 'react'
import { Link, useLocation } from 'react-router'
import { workDetailUrl } from '../shared/workNavigation'
import { useUnsavedChanges } from '../management/useUnsavedChanges'
import { ManagementError } from '../management/api'
import { feedbackStates, formatLongTime, relatedResources, formatShortTime, getFeedback, saveFeedback,
  type FeedbackEntry, type FeedbackInput, type FeedbackState } from './api'
import { FeedbackHistory } from './FeedbackHistory'
import { FeedbackRelatedData } from './FeedbackRelatedData'
import { FeedbackStatusBadge } from './FeedbackStatusBadge'
import styles from './FeedbackListPage.module.css'

type Resource = keyof typeof relatedResources
const conflictMessage = '다른 운영자가 먼저 저장했습니다. 작성 중인 내용은 그대로 두었습니다. 최신 상태와 비교한 뒤 다시 저장하세요.'

function input(entry: FeedbackEntry): FeedbackInput {
  return { version: entry.version, status: entry.status, note: entry.note,
    relatedResource: entry.relatedResource, relatedId: entry.relatedId }
}
function relationText(resource: FeedbackInput['relatedResource'], id: number | null, names: Record<string, string>) {
  if (!resource || !id) return '없음'
  return `${relatedResources[resource]} · ${names[`${resource}:${id}`] ?? `#${id}`}`
}

export function FeedbackProcessingPanel({ entry, onSaved, onRefreshed }: {
  entry: FeedbackEntry; onSaved: (entry: FeedbackEntry) => void; onRefreshed?: (entry: FeedbackEntry) => void
}) {
  const [current, setCurrent] = useState(entry)
  const [draft, setDraft] = useState(() => input(entry))
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [latest, setLatest] = useState<FeedbackEntry | null>(null)
  const [conflict, setConflict] = useState(false)
  const [saved, setSaved] = useState(false)
  const [picking, setPicking] = useState<Resource | null>(null)
  const [names, setNames] = useState<Record<string, string>>({})
  const location = useLocation()
  const base = input(current)
  // 저장 기준 version은 충돌 뒤 갱신될 수 있으므로 변경 여부는 내용만 견준다.
  const dirty = draft.status !== base.status || draft.note !== base.note
    || draft.relatedResource !== base.relatedResource || draft.relatedId !== base.relatedId
  const noteRequired = draft.status === 'DONE' || draft.status === 'ON_HOLD'
  const noteMissing = noteRequired && !draft.note.trim()
  useUnsavedChanges(dirty)

  function edit(change: Partial<FeedbackInput>) { setDraft(previous => ({ ...previous, ...change })); setSaved(false) }

  async function save(version = draft.version) {
    setBusy(true); setError('')
    try {
      const value = await saveFeedback(entry.id, { ...draft, version })
      setCurrent(value); setDraft(input(value)); setConflict(false); setLatest(null); setSaved(true); onSaved(value)
    } catch (cause) {
      if (cause instanceof ManagementError && cause.status === 409) {
        setConflict(true); setError(conflictMessage); setLatest(null)
        try {
          // 머리 배지·처리 이력도 최신 저장을 따르게 기준값을 바꾸고, 작성 중인 내용은 그대로 둔다.
          const fresh = await getFeedback(entry.id); setLatest(fresh); setCurrent(fresh); onRefreshed?.(fresh)
        } catch { setError(`${conflictMessage} 최신 상태를 불러오지 못했습니다.`) }
      } else {
        setConflict(false)
        setError(cause instanceof Error ? cause.message : '처리 내용을 저장하지 못했습니다.')
      }
    } finally { setBusy(false) }
  }
  const link = draft.relatedResource && draft.relatedId
    ? workDetailUrl(draft.relatedResource, draft.relatedId, `${location.pathname}${location.search}`, 'info') : null
  const status = dirty ? '저장하지 않은 변경이 있습니다.' : saved ? '처리 기록을 저장했습니다.'
    : current.processedAt ? `최근 처리: ${formatShortTime(current.processedAt)} · ${current.actor}` : '접수'

  return <div className={styles.panel}>
    <header className={styles.panelHeading}>
      <h2>의견 #{entry.id}</h2><FeedbackStatusBadge status={current.status} />
      <time dateTime={entry.createdAt}>접수 {formatLongTime(entry.createdAt)}</time>
    </header>
    <form aria-label={`의견 ${entry.id} 처리`} onSubmit={event => { event.preventDefault(); if (!conflict && !noteMissing) void save() }}>
      <fieldset disabled={busy}>
        <section aria-label="의견 내용"><p className={styles.content}>{entry.content || '추가 의견 없음'}</p></section>
        <div className={styles.field} role="group" aria-label="처리 상태">
          <span className={styles.fieldLabel}>처리 상태</span>
          <div className={styles.segments}>{(Object.keys(feedbackStates) as FeedbackState[]).map(value =>
            <button key={value} type="button" aria-pressed={draft.status === value}
              onClick={() => edit({ status: value })}>{feedbackStates[value]}</button>)}</div>
        </div>
        <div className={styles.field}>
          <span className={styles.fieldLabel}>관련 단지·공고</span>
          {link && draft.relatedResource && draft.relatedId ? <div className={styles.related}>
            <div><small>{relatedResources[draft.relatedResource]}</small>
              <strong>{names[`${draft.relatedResource}:${draft.relatedId}`] ?? `#${draft.relatedId}`}</strong></div>
            <Link to={link}>정보 열기</Link>
            <button type="button" onClick={() => edit({ relatedResource: '', relatedId: null })}>연결 해제</button>
          </div> : picking ? <FeedbackRelatedData key={picking} resource={picking} onCancel={() => setPicking(null)}
            onSelect={item => {
              setNames(previous => ({ ...previous, [`${picking}:${item.id}`]: item.name }))
              edit({ relatedResource: picking, relatedId: item.id }); setPicking(null)
            }} /> : <div className={styles.connect}>
            <button type="button" onClick={() => setPicking('COMPLEX')}>단지 연결</button>
            <button type="button" onClick={() => setPicking('ANNOUNCEMENT')}>공고 연결</button>
          </div>}
        </div>
        <label className={styles.field}>
          <span className={styles.fieldLabel}>처리 결과·보류 이유{noteRequired ? ' (필수)' : ''}</span>
          <textarea maxLength={5000} rows={4} required={noteRequired} aria-invalid={noteMissing}
            value={draft.note} onChange={event => edit({ note: event.target.value })} />
        </label>
        {noteMissing ? <p className={styles.fieldError}>처리 완료·보류는 결과나 이유를 적어야 저장할 수 있습니다.</p> : null}
        {error ? <div className="admin-alert" role="alert"><p>{error}</p></div> : null}
        {conflict && latest ? <table className={styles.compare}>
          <caption>최신 저장과 내 작성 비교</caption>
          <thead><tr><th scope="col">항목</th><th scope="col">최신 저장</th><th scope="col">내 작성</th></tr></thead>
          <tbody>
            <tr><th scope="row">상태</th><td>{feedbackStates[latest.status]}</td><td>{feedbackStates[draft.status]}</td></tr>
            <tr><th scope="row">관련</th><td>{relationText(latest.relatedResource, latest.relatedId, names)}</td>
              <td>{relationText(draft.relatedResource, draft.relatedId, names)}</td></tr>
            <tr><th scope="row">메모</th><td>{latest.note || '없음'}</td><td>{draft.note || '없음'}</td></tr>
          </tbody></table> : null}
        {conflict ? <button type="button" disabled={!latest} onClick={() => latest && void save(latest.version)}>최신 상태 기준으로 다시 저장</button> : null}
        <div className={styles.actions}>
          <p role="status">{status}</p>
          <button type="button" disabled={!dirty} onClick={() => { setDraft(input(current)); setConflict(false); setError(''); setLatest(null); setPicking(null) }}>되돌리기</button>
          <button type="submit" className="admin-primary" disabled={!dirty || conflict || noteMissing}>{busy ? '저장 중…' : '처리 기록 저장'}</button>
        </div>
      </fieldset>
    </form>
    <FeedbackHistory id={entry.id} version={current.version} />
  </div>
}
