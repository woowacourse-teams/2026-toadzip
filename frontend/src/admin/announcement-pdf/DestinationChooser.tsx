import { useState } from 'react'
import { Button } from '../../design-system/components/Button'
import type { JsonWorkspace } from '../announcement-json/api'
import type { CorrectionRow } from '../supply/supplyCorrectionApi'
import { chosenProposal, describeRow, matchingRows, type HousingCandidates } from './matching'
import { destinationSentence, reproposeRow, type Review, type RowReview } from './review'
import styles from './PdfCorrection.module.css'

const searchKey = (value: string) => value.normalize('NFKC').replace(/\s+/g, '').toLowerCase()
const rowText = (row: CorrectionRow) => [row.housingComplexName ?? row.complexName, row.complexName, row.typeName, row.exclusiveArea ?? '', row.householdCount ?? '', `#${String(row.id)}`].join(' ')

/**
 * 공급행 하나가 저장될 곳을 한 문장으로 보이고, 자동 대응이 틀렸을 때 '바꾸기'로 고친다. 목록을 한꺼번에 늘어놓지 않고
 * 추천과 '새 공급정보로 추가'를 먼저 보이며, 이 공고의 다른 기존 공급정보는 검색해서 고른다.
 * 적용하는 다른 PDF 행이 고르거나 함께 제외할 행은 고를 수 없게 그 공급행 번호를 보인다. 같은 행을 두 PDF 행이 고치면 저장할 때 막는다.
 */
export function DestinationChooser({ review, index, workspace, housing, onChange }: { review: Review; index: number; workspace: JsonWorkspace; housing: HousingCandidates; onChange: (row: RowReview) => void }) {
  const [open, setOpen] = useState(false), [query, setQuery] = useState('')
  const row = review.rows[index], proposal = row.proposal, label = `공급행 ${index + 1}`
  const recommendedKey = (proposal.automatic ?? proposal).destination
  const owners = new Map(review.rows.flatMap((other, at) => at === index || !other.include ? [] : [other.proposal.destination, ...other.proposal.supersedes ?? []].map(key => [key, at] as const)))
  const excluded = new Set(review.excludedRows ?? [])
  const rows = workspace.supply.rows.filter(item => !item.excluded && !excluded.has(item.key))
  const recommended = rows.find(item => item.key === recommendedKey)
  const found = query.trim() ? rows.filter(item => item.key !== recommendedKey && searchKey(rowText(item)).includes(searchKey(query))) : []
  function pick(key: string) {
    const next = chosenProposal(review.extraction.supplyRows[index], proposal, key, matchingRows(workspace.supply.rows, workspace.supply.lhSupplyRows, workspace.supply.applicationTypeNames, workspace.supply.matchingAreas), new Set(owners.keys()), housing)
    onChange({ ...reproposeRow(review, index, next, workspace), reviewed: true }); setOpen(false); setQuery('')
  }
  // 단지가 없는 기존 행은 단지 정하기에서 단지를 정해야 저장할 곳이 될 수 있다(단지 정하기가 저장할 곳을 다시 고른다).
  const option = (item: CorrectionRow, tag?: string) => {
    const owner = owners.get(item.key), noComplex = item.housingComplexId === null && item.key !== recommendedKey
    return <li key={item.key}><button type="button" disabled={owner !== undefined || noComplex} aria-pressed={proposal.destination === item.key} onClick={() => pick(item.key)}>
      <span>{tag && <><small>{tag}</small>{' · '}</>}{describeRow(item, workspace.supply)}{owner !== undefined ? ` · 공급행 ${owner + 1}이 씀` : noComplex ? ' · 단지 미연결(단지 정하기에서 정하세요)' : ''}</span></button></li>
  }
  return <div className={styles.placement}>
    <div className={styles.placementSentence}><p aria-label={`${label} 저장할 곳`}>{destinationSentence(review, index, workspace)}</p>
      <Button data-design-system className={styles.secondaryButton} aria-expanded={open} aria-label={`${label} 저장할 곳 바꾸기`} onClick={() => setOpen(!open)}>{open ? '닫기' : '바꾸기'}</Button></div>
    {open && <div className={styles.placementPicker} role="group" aria-label={`${label} 저장할 곳 고르기`}>
      <ul className={styles.placementOptions} aria-label={`${label} 저장할 곳 후보`}>
        {recommended && option(recommended, '추천')}
        <li><button type="button" aria-pressed={proposal.destination === 'new'} onClick={() => pick('new')}><span>{recommendedKey === 'new' && <><small>추천</small>{' · '}</>}새 공급정보로 추가</span></button></li>
      </ul>
      <label>{label} 다른 기존 공급정보 찾기<input type="search" value={query} placeholder="단지·주택형·면적·모집수·#ID" onChange={event => setQuery(event.target.value)} /></label>
      {query.trim() && <ul className={styles.placementOptions} aria-label={`${label} 찾은 기존 공급정보`}>
        {found.map(item => option(item))}
        {!found.length && <li className={styles.hint}>찾는 기존 공급정보가 없습니다.</li>}
      </ul>}
      <p className={styles.hint}>같은 단지에 이 행과 주택형(신청형)·전용면적이 같은 다른 공급정보가 있으면, 한 단지에는 신청형마다 공급행 하나만 두므로 저장할 때 함께 제외합니다. 신청형이 다르면 같은 주택타입을 함께 가리켜도 그대로 둡니다.</p>
    </div>}
  </div>
}
