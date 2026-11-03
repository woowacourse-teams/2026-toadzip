import { useState } from 'react'
import { Button } from '../../design-system/components/Button'
import type { JsonWorkspace } from '../announcement-json/api'
import type { CorrectionRow } from '../supply/supplyCorrectionApi'
import { attachableLhRows, lhRowLabel, splittableLhRows, type ComplexLabels } from './matching'
import type { Placement } from './placement'
import { destinationLabel, placementLabels, placementMode, type PlacementMode, type PlacementProblem, type RowReview } from './review'
import styles from './PdfCorrection.module.css'

const order: PlacementMode[] = ['UPDATE', 'SPLIT', 'ATTACH', 'ADD']
const searchKey = (value: string) => value.normalize('NFKC').replace(/\s+/g, '').toLowerCase()
function rowText(row: CorrectionRow) {
  return [row.housingComplexName ?? row.complexName, row.complexName, row.typeName, row.exclusiveArea ?? '', row.householdCount ?? '', `#${String(row.id)}`].join(' ')
}

/**
 * 추출 행 하나의 저장 방식(기존 행 수정 · 통합행 분리 · LH 행에 연결 · 별도 추가)과 그 대상을 고른다.
 * 기존 행 수정은 추천 행을 먼저 두고 이 공고의 제외하지 않은 공급행 전체를 단지·주택형·면적·모집수로 찾을 수 있다.
 */
export function PlacementChooser({ row, index, workspace, complexes, problem, onPlace, onFixDuplicate, onFocusProblem }: { row: RowReview; index: number; workspace: JsonWorkspace; complexes: ComplexLabels
  problem?: PlacementProblem; onPlace: (placement: Placement) => void; onFixDuplicate?: () => void; onFocusProblem?: (target: string) => void }) {
  const [query, setQuery] = useState('')
  const proposal = row.proposal, mode = placementMode(proposal), label = `공급행 ${index + 1}`
  const rows = workspace.supply.rows.filter(item => !item.excluded)
  const splittable = splittableLhRows(workspace.supply), unattached = attachableLhRows(workspace.supply)
  const linked = rows.filter(item => item.housingComplexId !== null)
  const anchors = linked.filter((item, at) => linked.findIndex(other => other.housingComplexId === item.housingComplexId) === at)
  const anchorFor = (key: string) => {
    const complexId = workspace.supply.rows.find(item => item.key === key)?.housingComplexId
    return anchors.find(item => item.housingComplexId === complexId)?.key ?? ''
  }
  const recommended = rows.filter(item => proposal.recommended.includes(item.key))
  const others = rows.filter(item => !proposal.recommended.includes(item.key) && (!query.trim() || searchKey(rowText(item)).includes(searchKey(query)) || item.key === proposal.destination))
  const unavailable: Partial<Record<PlacementMode, string>> = {
    SPLIT: splittable.length ? undefined : '나눌 수 있는 LH 통합행이 없습니다.',
    ATTACH: unattached.length ? undefined : '공급행이 없는 LH 행이 없습니다.',
    UPDATE: rows.length ? undefined : '수정할 기존 공급행이 없습니다.',
  }
  function choose(next: PlacementMode) {
    if (next === 'UPDATE') onPlace({ mode: 'UPDATE', rowKey: proposal.recommended[0] ?? rows[0].key })
    else if (next === 'SPLIT') onPlace({ mode: 'SPLIT', lhRow: (splittable.find(lh => lh.attachedRowKey === proposal.destination) ?? splittable[0]).rowIdentifier })
    else if (next === 'ATTACH') onPlace({ mode: 'ATTACH', lhRow: unattached[0].rowIdentifier, anchorKey: proposal.anchorKey })
    else onPlace({ mode: 'ADD', anchorKey: proposal.anchorKey })
  }
  const anchorSelect = anchors.length > 0 && <label>{label} 기준 단지<select value={anchorFor(proposal.anchorKey)} onChange={event => onPlace(mode === 'ATTACH'
    ? { mode: 'ATTACH', lhRow: proposal.lhRow!, anchorKey: event.target.value } : { mode: 'ADD', anchorKey: event.target.value })}><option value="">다른 단지 찾기</option>
    {anchors.map(item => <option key={item.key} value={item.key}>{complexes[item.housingComplexId!] ?? item.housingComplexName ?? `단지 #${item.housingComplexId}`}</option>)}</select></label>
  return <div className={styles.placement} data-review-id={`placement:${index}`}>
    <fieldset className={styles.placementModes}><legend>{label} 저장 방식</legend>
      {order.map(item => <label key={item} title={unavailable[item]} data-disabled={Boolean(unavailable[item]) || undefined}>
        <input type="radio" name={`placement-${index}`} checked={mode === item} disabled={Boolean(unavailable[item])} onChange={() => choose(item)} />{placementLabels[item]}</label>)}
    </fieldset>
    {!mode && <p className={styles.hint}>저장 방식을 고르세요.{proposal.recommended.length ? ` 추천: ${recommended.map(item => `${item.typeName} #${String(item.id)}`).join(', ')}` : ''}</p>}
    {mode === 'UPDATE' && <div className={styles.mapping}>
      <label>{label} 기존 행 찾기<input type="search" value={query} placeholder="단지·주택형·면적·모집수" onChange={event => setQuery(event.target.value)} /></label>
      <label>{label} 수정할 기존 행<select value={proposal.destination} onChange={event => onPlace({ mode: 'UPDATE', rowKey: event.target.value })}>
        {recommended.length > 0 && <optgroup label="추천">{recommended.map(item => <option key={item.key} value={item.key}>추천: {destinationLabel(item)}</option>)}</optgroup>}
        <optgroup label="이 공고의 다른 공급행">{others.map(item => <option key={item.key} value={item.key}>{destinationLabel(item)}</option>)}</optgroup>
      </select></label>
    </div>}
    {mode === 'SPLIT' && <div className={styles.mapping}>
      <label>{label} 나눌 LH 통합행<select value={proposal.lhRow} onChange={event => onPlace({ mode: 'SPLIT', lhRow: event.target.value })}>
        {splittable.map(lh => <option key={lh.rowIdentifier} value={lh.rowIdentifier}>{lhRowLabel(lh)} · 통합행 #{String(workspace.supply.rows.find(item => item.key === lh.attachedRowKey)?.id)}</option>)}</select></label>
      <p className={styles.hint}>{proposal.destination === 'new' ? '통합행에서 나눈 새 공급행으로 저장합니다.' : '통합행(ID 유지)을 이 주택형의 첫 분리행으로 바꿉니다.'}</p>
    </div>}
    {mode === 'ATTACH' && <div className={styles.mapping}>
      <label>{label} 연결할 LH 행<select value={proposal.lhRow} onChange={event => onPlace({ mode: 'ATTACH', lhRow: event.target.value, anchorKey: proposal.anchorKey })}>
        {unattached.map(lh => <option key={lh.rowIdentifier} value={lh.rowIdentifier}>{lhRowLabel(lh)}</option>)}</select></label>
      {anchorSelect}
    </div>}
    {mode === 'ADD' && <div className={styles.mapping}>{anchorSelect}</div>}
    {proposal.ambiguous && <p className={styles.warningNote}>모호: 표기·면적 근거가 없어 자동으로 고르지 않았습니다. 후보 {recommended.map(item => `${item.typeName} #${String(item.id)}`).join(', ') || '없음'}</p>}
    {problem && <div className={problem.level === 'error' ? styles.errorNote : styles.warningNote} role={problem.level === 'error' ? 'alert' : 'note'}>
      <p><strong>{problem.level === 'error' ? '오류' : '경고'}</strong> {problem.message}</p>
      {problem.kind === 'duplicate' && onFixDuplicate && <Button data-design-system className={styles.secondaryButton} onClick={onFixDuplicate}>{`${label} 통합행 분리로 바꾸기`}</Button>}
      {problem.focus && onFocusProblem && <Button data-design-system className={styles.secondaryButton} onClick={() => onFocusProblem(problem.focus!)}>첫 분리행 모집수 고치기</Button>}
    </div>}
  </div>
}
