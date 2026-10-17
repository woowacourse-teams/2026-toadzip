import { useState } from 'react'
import { display } from '../management/fields'
import type { ReviewResult, ReviewTarget } from './documentReview'
import styles from './DocumentReview.module.css'

export function IncomingReviewPanel({ current, incoming, target, onApply, onCancel }: {
  current: ReviewResult; incoming: ReviewResult; target: ReviewTarget;
  onApply: (result: ReviewResult) => void; onCancel: () => void
}) {
  const [selected, setSelected] = useState<string[]>([])
  function mergeSelected() {
    const added = incoming.proposals.filter(proposal => selected.includes(proposal.field))
    const proposals = [...current.proposals.filter(proposal => !selected.includes(proposal.field)), ...added]
    const confirmedFields = new Set(proposals.map(proposal => proposal.field))
    const unresolved = new Map([...current.unresolved, ...incoming.unresolved]
      .filter(item => !confirmedFields.has(item.field)).map(item => [item.field, item]))
    onApply({ proposals, unresolved: [...unresolved.values()] })
  }
  return <section className={styles.card} aria-label="가져온 수정안 비교">
    <h3>기존 수정안과 가져온 결과를 비교하세요</h3>
    <p>기존 {current.proposals.length}개 · 가져온 수정안 {incoming.proposals.length}개 · 보류 {incoming.unresolved.length}개. 선택한 항목만 추가하며 같은 항목은 새 근거로 교체합니다.</p>
    {incoming.proposals.map(proposal => {
      const previous = current.proposals.find(item => item.field === proposal.field)
      const label = target.fields.find(field => field.name === proposal.field)?.label ?? proposal.field
      return <article className={styles.proposal} key={proposal.field}>
        <label><input type="checkbox" checked={selected.includes(proposal.field)} onChange={event => setSelected(fields => event.target.checked
          ? [...fields, proposal.field] : fields.filter(field => field !== proposal.field))} />{label} 가져오기</label>
        <p>{previous ? `기존 수정안 ${display(previous.value)} → ${display(proposal.value)} · 선택 시 교체` : `추가할 수정안 ${display(proposal.value)}`} · {proposal.unit}</p>
        {previous ? <div><p>기존 근거 · PDF {previous.page}쪽</p><blockquote>{previous.quote}</blockquote><p>{previous.reason}</p></div> : null}
        <p>{proposal.scope} · PDF {proposal.page}쪽</p><blockquote>{proposal.quote}</blockquote><p>{proposal.reason}</p>
      </article>
    })}
    {incoming.unresolved.length ? <details><summary>가져온 보류 사유 확인</summary><ul>{incoming.unresolved.map((item, index) => <li key={index}>{item.field}: {item.reason}</li>)}</ul></details> : null}
    <p className={styles.hint}>반영 전 입력은 이 화면에 한 번 보관합니다. 원문 확인 선택은 해제되며 DB 저장은 별도로 수행합니다.</p>
    <div className={styles.actions}>
      <button type="button" disabled={!selected.length} onClick={mergeSelected}>선택한 {selected.length}개 수정안 추가·교체</button>
      <button type="button" onClick={() => onApply(incoming)}>가져온 결과로 전체 교체</button>
      <button type="button" onClick={onCancel}>이번 결과 가져오기 취소</button>
    </div>
  </section>
}
