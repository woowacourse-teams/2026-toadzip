import { useState } from 'react'
import { Button } from '../../design-system/components/Button'
import type { JsonWorkspace } from '../announcement-json/api'
import { evidenceFor, type Evidence } from './extraction'
import { ReviewCells } from './ReviewCells'
import { cellMatches, choices, pendingSchedule, remapSchedule, savedValue, scheduleStorable, shown, shownDateTime, type Cell, type Review, type ReviewFilter, type ScheduleReview } from './review'
import styles from './PdfCorrection.module.css'

const period = (start: unknown, end: unknown) => {
  if (start == null && end == null) return '날짜 없음'
  return start === end || end == null ? shownDateTime(start) : `${shownDateTime(start)} ~ ${shownDateTime(end)}`
}
const typeLabel = (value: unknown) => choices.scheduleType.find(([key]) => key === value)?.[1] ?? shown(value)

/**
 * PDF 일정을 일정마다 한 묶음으로 보인다. PDF 기간·근거·기존 일정·결정을 함께 두어 펼치지 않고도 무엇을 어떻게 저장하는지 읽게 한다.
 * 값을 고치거나 기존 일정 대응을 바꿀 때만 행을 펼친다.
 */
export function ReviewSchedules({ review, workspace, filter, empty, onChange, onEvidence }: { review: Review; workspace: JsonWorkspace; filter: ReviewFilter; empty: string
  onChange: (review: Review) => void; onEvidence: (evidence: Evidence, cell?: Cell) => void }) {
  const [open, setOpen] = useState<number | null>(null)
  const update = (index: number, patch: Partial<ScheduleReview>) => onChange({ ...review, schedules: review.schedules.map((item, at) => at === index ? { ...item, ...patch } : item) })
  const matches = (schedule: ScheduleReview) => filter === 'all' || filter === 'pending' && pendingSchedule(schedule) || schedule.include && schedule.cells.some(cell => cellMatches(cell, filter))
  const shownRows = review.schedules.map((schedule, index) => ({ schedule, index })).filter(({ schedule }) => matches(schedule))
  if (!shownRows.length) return <p className={styles.hint}>{empty}</p>
  return <ul className={styles.scheduleList} aria-label="일정 대조표">{shownRows.map(({ schedule, index }) => {
    const value = (field: string) => schedule.cells.find(cell => cell.field === field)?.extracted.value
    const saved = (field: string) => { const cell = schedule.cells.find(item => item.field === field); return cell ? savedValue(cell) : null }
    const evidence = evidenceFor(review.extraction.evidence, `schedules[${index}]`)
    const existing = schedule.proposal.existingIds.map(id => workspace.events.find(event => event.id === id)).filter(event => event !== undefined)
    const pending = pendingSchedule(schedule), undated = schedule.proposal.undated, storable = scheduleStorable(schedule)
    const kept = existing.length > 0 && schedule.cells.every(cell => !cell.selected)
    const [decision, tone] = pending ? ['확인 필요', 'pending'] : undated ? ['저장 안 함', 'muted'] : !schedule.include ? ['적용 안 함', 'muted']
      : kept ? schedule.cells.every(cell => cell.extracted.state === 'same') ? ['같음 · 변경 없음', 'done'] : ['기존 유지 · PDF와 다름', 'warning']
      : existing.length ? [`기존 ${existing.length}건 갱신`, 'done'] : ['새 일정 추가', 'done']
    const problem = pending ? schedule.include && !storable ? '유형·이름·시작·종료 중 빈 값이 있습니다. 값을 넣거나 적용 안 함으로 두세요.' : schedule.proposal.reason : ''
    const title = `${typeLabel(value('scheduleType'))} · ${shown(value('name'))}`
    return <li key={index} data-review-id={`schedule:${index}`} tabIndex={-1} data-pending={pending || undefined} data-excluded={!schedule.include || undefined}>
      <div className={styles.scheduleHead}><strong>{title}</strong><span className={styles.statusBadge} data-tone={tone}>{decision}</span></div>
      <dl>
        <dt>PDF</dt><dd><span className={styles.period}>{period(value('startAt'), value('endAt'))}</span></dd>
        {schedule.include && !undated && <><dt>저장</dt><dd><span><span className={styles.period}>{period(saved('startAt'), saved('endAt'))}</span>{` · ${shown(saved('name'))}`}</span></dd></>}
        <dt>기존</dt><dd>{existing.length ? existing.map(event => <span key={event.id}><span className={styles.period}>{period(event.data.startAt, event.data.endAt)}</span>{` · #${event.id} ${event.complexName ?? '공고 전체'} · ${shown(event.data.name)}`}</span>) : '없음'}</dd>
        <dt>근거</dt><dd>{evidence ? <Button data-design-system className={styles.quietButton} aria-label={`PDF p.${evidence.page} 근거 보기: ${evidence.quote}`} onClick={() => onEvidence(evidence)}>{`p.${evidence.page} “${evidence.quote}”`}</Button> : '없음'}</dd>
      </dl>
      {undated ? <p className={styles.hint}>{schedule.proposal.reason}</p> : <>
        {problem && <p className={styles.pendingLabel}>{problem}</p>}
        {!pending && schedule.proposal.reason && <p className={styles.hint}>{schedule.proposal.reason}</p>}
        <div className={styles.scheduleActions}>
          <div role="group" aria-label={`일정 ${index + 1} ${title} 반영`} className={styles.valueChoices}>
            <Button data-design-system aria-label={`일정 ${index + 1} 적용`} aria-pressed={schedule.include} disabled={!storable && !schedule.include} onClick={() => update(index, { include: true, reviewed: true })}>적용</Button>
            <Button data-design-system aria-label={`일정 ${index + 1} 적용 안 함`} aria-pressed={!schedule.include} onClick={() => update(index, { include: false, reviewed: true })}>적용 안 함</Button>
          </div>
          <Button data-design-system className={styles.quietButton} aria-expanded={open === index} onClick={() => setOpen(open === index ? null : index)}>{open === index ? '접기' : '값·대응 고치기'}</Button>
          {!storable && !schedule.include && <small className={styles.hint}>빈 값이 있어 적용하려면 값·대응 고치기에서 먼저 채우세요.</small>}
        </div></>}
      {open === index && !undated && <>
        <fieldset><legend>일정 {index + 1}에서 수정할 기존 일정</legend>{workspace.events.filter(event => event.data.scheduleType === value('scheduleType')).map(event => <label key={event.id}><input type="checkbox" checked={schedule.proposal.existingIds.includes(event.id)}
          onChange={changeEvent => onChange({ ...review, schedules: review.schedules.map((item, at) => at === index ? { ...remapSchedule(review, index, changeEvent.target.checked ? [...item.proposal.existingIds, event.id] : item.proposal.existingIds.filter(id => id !== event.id), workspace), reviewed: true } : item) })} />
          {`#${event.id} · ${event.complexName ?? '공고 전체'} · ${shown(event.data.name)} · ${period(event.data.startAt, event.data.endAt)}`}</label>)}<p className={styles.hint}>선택한 기존 일정이 없으면 새 일정으로 추가합니다.</p></fieldset>
        <ReviewCells cells={schedule.cells} scope={`일정 ${index + 1}`} filter={filter} onChange={cells => update(index, { cells })} onEvidence={onEvidence} /></>}
    </li>
  })}</ul>
}
