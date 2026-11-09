import { Button } from '../../design-system/components/Button'
import type { JsonWorkspace } from '../announcement-json/api'
import { evidenceFor, type Evidence } from './extraction'
import { ReviewCells } from './ReviewCells'
import { cellMatches, choices, currentReceptionPlaces, pendingField, receptionName, shown, type Cell, type ReceptionReview, type Review, type ReviewFilter } from './review'
import styles from './PdfCorrection.module.css'

const methodLabel = (value: unknown) => choices.method.find(([key]) => key === value)?.[1] ?? shown(value)

/**
 * 접수처를 한 곳씩 보인다. PDF 접수처는 접수 방식과 이름이 같은 지금 접수처와 짝지어 칸마다 고르고(짝이 없으면 새 접수처), PDF에 없는 지금 접수처는 값만 보이며
 * 저장에서 뺄 수 있다. 뺀 접수처는 저장하면 사라진다.
 */
export function ReviewReception({ review, workspace, needs, filter, onChange, onEvidence }: { review: Review; workspace: JsonWorkspace; needs: Map<string, string>; filter: ReviewFilter
  onChange: (review: Review) => void; onEvidence: (evidence: Evidence, cell?: Cell) => void }) {
  const current = currentReceptionPlaces(workspace)
  const update = (index: number, patch: Partial<ReceptionReview>) => onChange({ ...review, reception: review.reception.map((item, at) => at === index ? { ...item, ...patch } : item) })
  const matches = (entry: ReceptionReview) => filter === 'all' || entry.include && entry.cells.some(cell => cellMatches(cell, filter) || filter === 'pending' && pendingField(cell, needs))
  const shownPlaces = review.reception.map((entry, index) => ({ entry, index })).filter(({ entry }) => matches(entry))
  if (!shownPlaces.length) return null
  return <ul className={styles.scheduleList} aria-label="접수처 대조표">{shownPlaces.map(({ entry, index }) => {
    const name = receptionName(review, index)
    const before = entry.existing === null ? null : current[entry.existing]
    const evidence = entry.extracted === null ? null : evidenceFor(review.extraction.evidence, `receptionPlaces[${entry.extracted}]`)
    const [decision, tone] = !entry.include ? [entry.existing === null ? '추가 안 함' : '저장할 때 뺌', 'muted']
      : entry.extracted === null ? ['PDF에 없음 · 유지', 'warning'] : entry.existing === null ? ['새 접수처 추가', 'done'] : ['기존 접수처와 대조', 'done']
    return <li key={`${entry.extracted ?? 'current'}:${entry.existing ?? 'new'}`} data-review-id={`reception:${index}`} tabIndex={-1} data-excluded={!entry.include || undefined}>
      <div className={styles.scheduleHead}><strong>{name}</strong><span className={styles.statusBadge} data-tone={tone}>{decision}</span></div>
      <dl>
        <dt>기존</dt><dd>{before ? `${shown(before.name)} · ${methodLabel(before.method)}${before.address ? ` · ${String(before.address)}` : ''}` : '없음'}</dd>
        {entry.extracted !== null && <><dt>근거</dt><dd>{evidence ? <Button data-design-system className={styles.quietButton} aria-label={`PDF p.${evidence.page} 근거 보기: ${evidence.quote}`} onClick={() => onEvidence(evidence)}>{`p.${evidence.page} “${evidence.quote}”`}</Button> : '없음'}</dd></>}
      </dl>
      <div className={styles.scheduleActions}>
        <div role="group" aria-label={`${name} 저장`} className={styles.valueChoices}>
          <Button data-design-system aria-label={`${name} 저장`} aria-pressed={entry.include} onClick={() => update(index, { include: true })}>저장</Button>
          <Button data-design-system aria-label={`${name} 빼기`} aria-pressed={!entry.include} onClick={() => update(index, { include: false })}>{entry.existing === null ? '추가 안 함' : '빼기'}</Button>
        </div>
      </div>
      {entry.include && entry.extracted !== null && <ReviewCells cells={entry.cells} scope={name} needs={needs} filter={filter} onChange={cells => update(index, { cells })} onEvidence={onEvidence} />}
    </li>
  })}</ul>
}
