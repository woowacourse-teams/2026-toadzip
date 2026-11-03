import { useState } from 'react'
import { Button } from '../../design-system/components/Button'
import type { JsonWorkspace } from '../announcement-json/api'
import type { CorrectionRow } from '../supply/supplyCorrectionApi'
import type { ComplexLabels } from './matching'
import type { Placement } from './placement'
import { placementSentence } from './correspondence'
import { destinationLabel, placementMode, rowName, rowOfReviewId, type PlacementProblem, type Review } from './review'
import styles from './PdfCorrection.module.css'

const searchKey = (value: string) => value.normalize('NFKC').replace(/\s+/g, '').toLowerCase()
function rowText(row: CorrectionRow) {
  return [row.housingComplexName ?? row.complexName, row.complexName, row.typeName, row.exclusiveArea ?? '', row.householdCount ?? '', `#${String(row.id)}`].join(' ')
}

/**
 * 추출 행 하나가 저장될 곳을 한 문장으로 보이고, '바꾸기'로 고칠 기존 공급정보(추천 먼저, 이 공고의 제외하지 않은 공급행 전체 검색)나
 * 새 공급정보 추가를 고르게 한다. LH 통합행 나누기와 공급행 없는 LH 행에 붙이기는 단지 대응(ComplexMatch)에서 정하고, 여기서는 그리로 이동만 한다.
 */
export function PlacementChooser({ review, index, workspace, complexes, problem, onPlace, onFixDuplicate, onFocusProblem, onShowMatch }: { review: Review; index: number; workspace: JsonWorkspace; complexes: ComplexLabels
  problem?: PlacementProblem; onPlace: (placement: Placement) => void; onFixDuplicate?: () => void; onFocusProblem?: (target: string) => void
  /** 이 행이 든 단지의 LH·PDF 대응으로 이동한다. 그 단지에 LH 행이 없으면 없다. */
  onShowMatch?: () => void }) {
  const [open, setOpen] = useState(false), [query, setQuery] = useState('')
  const proposal = review.rows[index].proposal, mode = placementMode(proposal), label = `공급행 ${index + 1}`
  const rows = workspace.supply.rows.filter(item => !item.excluded)
  const linked = rows.filter(item => item.housingComplexId !== null)
  const anchors = linked.filter((item, at) => linked.findIndex(other => other.housingComplexId === item.housingComplexId) === at)
  const anchorFor = (key: string) => {
    const complexId = workspace.supply.rows.find(item => item.key === key)?.housingComplexId
    return anchors.find(item => item.housingComplexId === complexId)?.key ?? ''
  }
  const recommended = rows.filter(item => proposal.recommended.includes(item.key))
  const others = rows.filter(item => !proposal.recommended.includes(item.key) && (!query.trim() || searchKey(rowText(item)).includes(searchKey(query))))
  function pick(placement: Placement) { onPlace(placement); setOpen(false); setQuery('') }
  const anchorSelect = (mode === 'ADD' || mode === 'ATTACH') && anchors.length > 0 && <label>{label} 기준 단지<select value={anchorFor(proposal.anchorKey)} onChange={event => onPlace(mode === 'ATTACH'
    ? { mode: 'ATTACH', lhRow: proposal.lhRow!, anchorKey: event.target.value } : { mode: 'ADD', anchorKey: event.target.value })}><option value="">다른 단지 찾기</option>
    {anchors.map(item => <option key={item.key} value={item.key}>{complexes[item.housingComplexId!] ?? item.housingComplexName ?? `단지 #${item.housingComplexId}`}</option>)}</select></label>
  const option = (item: CorrectionRow, tag?: string) => <li key={item.key}><button type="button" aria-pressed={mode === 'UPDATE' && proposal.destination === item.key} onClick={() => pick({ mode: 'UPDATE', rowKey: item.key })}>
    <span>{tag && <small>{tag} · </small>}{destinationLabel(item)}</span></button></li>
  return <div className={styles.placement} data-review-id={`placement:${index}`}>
    <div className={styles.placementSentence}><p aria-label={`${label} 저장할 곳`}>{placementSentence(review, index, workspace)}</p>
      {(mode === 'SPLIT' || mode === 'ATTACH') && onShowMatch && <Button data-design-system className={styles.quietButton} onClick={onShowMatch}>LH·PDF 대응 보기</Button>}
      <Button data-design-system className={styles.secondaryButton} aria-expanded={open} aria-label={`${label} 저장할 곳 바꾸기`} onClick={() => setOpen(!open)}>{open ? '닫기' : '바꾸기'}</Button></div>
    {!mode && recommended.length > 0 && <p className={styles.hint}>추천: {recommended.map(item => `#${String(item.id)}(${item.typeName})`).join(', ')}</p>}
    {anchorSelect}
    {open && <div className={styles.placementPicker} role="group" aria-label={`${label} 저장할 곳 고르기`}>
      <label>{label} 기존 공급정보 찾기<input type="search" value={query} placeholder="단지·주택형·면적·모집수·#ID" onChange={event => setQuery(event.target.value)} /></label>
      <ul className={styles.placementOptions} aria-label={`${label} 고칠 기존 공급정보`}>
        {recommended.map(item => option(item, '추천'))}{others.map(item => option(item))}
        {!recommended.length && !others.length && <li className={styles.hint}>찾는 기존 공급정보가 없습니다.</li>}
      </ul>
      <Button data-design-system className={styles.secondaryButton} aria-pressed={mode === 'ADD'} onClick={() => pick({ mode: 'ADD', anchorKey: proposal.anchorKey })}>새 공급정보로 추가</Button>
    </div>}
    {problem && <div className={problem.level === 'error' ? styles.errorNote : styles.warningNote} role={problem.level === 'error' ? 'alert' : 'note'}>
      <p><strong>{problem.level === 'error' ? '오류' : '경고'}</strong> {problem.message}</p>
      <div className={styles.actions}>
        {problem.kind === 'duplicate' && onFixDuplicate && <Button data-design-system className={styles.secondaryButton} onClick={onFixDuplicate}>{`${label} PDF 행마다 나누기`}</Button>}
        {problem.focus && onFocusProblem && <Button data-design-system className={styles.secondaryButton} onClick={() => onFocusProblem(problem.focus!)}>{`${rowName(review, rowOfReviewId(problem.focus) ?? index)} 모집수 고치기`}</Button>}
        {problem.kind === 'split' && !problem.focus && onShowMatch && <Button data-design-system className={styles.secondaryButton} onClick={onShowMatch}>LH·PDF 대응 보기</Button>}
        {problem.level === 'error' && !problem.focus && !open && <Button data-design-system className={styles.secondaryButton} onClick={() => setOpen(true)}>{`${label} 저장할 곳 다시 고르기`}</Button>}
      </div>
    </div>}
  </div>
}
