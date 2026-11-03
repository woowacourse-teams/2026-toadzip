import type { JsonWorkspace } from '../announcement-json/api'
import { assignSplitDestinations, pickHousingType, type HousingCandidates, type RowProposal } from './matching'
import { createRowReview, preserveCellChoice, type Review, type RowReview } from './review'

/** 운영자가 고른 저장 방식. UPDATE는 수정할 기존 행, SPLIT·ATTACH는 LH 행, ADD는 기준 단지(그 단지의 공급행 키, 없으면 '')다. */
export type Placement =
  | { mode: 'UPDATE'; rowKey: string }
  | { mode: 'SPLIT'; lhRow: string }
  | { mode: 'ATTACH'; lhRow: string; anchorKey: string }
  | { mode: 'ADD'; anchorKey: string }

/** 저장 위치가 바뀐 행의 검토를 다시 만들면서 운영자가 고른 값(기존·추출·직접 입력)과 포함·확인 상태를 그대로 둔다. */
function rebuild(review: Review, index: number, proposal: RowProposal, workspace: JsonWorkspace, housing: HousingCandidates, reviewed: boolean): RowReview {
  const old = review.rows[index]
  const anchor = workspace.supply.rows.find(row => row.key === proposal.anchorKey)
  const complexId = anchor?.housingComplexId ?? proposal.newComplex?.id ?? proposal.complexId
  const candidates = complexId === null ? [] : housing[complexId] ?? proposal.candidates
  const picked = pickHousingType(review.extraction, index, candidates)
  const next = createRowReview(review.extraction, index, { ...proposal, complexId, candidates, housingTypeId: picked.housingTypeId ?? proposal.housingTypeId }, workspace)
  const oldCells = [...old.cells, ...old.targets.flatMap(target => target.cells)]
  const retain = (cell: RowReview['cells'][number]) => preserveCellChoice(cell, oldCells.find(item => item.path === cell.path))
  return { ...next, key: proposal.destination === 'new' && old.proposal.destination === 'new' ? old.key : next.key, include: old.include, reviewed,
    newHousingType: old.newHousingType, priceTarget: old.priceTarget, cells: next.cells.map(retain), targets: next.targets.map(target => ({ ...target, cells: target.cells.map(retain) })) }
}

/**
 * 추출 행 하나의 저장 방식을 바꾼다. 분리 묶음에 들어가거나 빠지면 그 묶음 행들의 저장 위치(통합행을 이어받는 첫 행)를 다시 맞춘다.
 * 바꾼 행만 확인한 것으로 보고, 다른 행의 저장 방식·값 선택은 바꾸지 않는다.
 */
export function placeRow(review: Review, index: number, placement: Placement, workspace: JsonWorkspace, housing: HousingCandidates): Review {
  const old = review.rows[index].proposal
  const base: RowProposal = { ...old, saveMode: undefined, lhRow: undefined, ambiguous: false, needsConfirmation: true, newComplex: placement.mode === 'ADD' && !placement.anchorKey ? old.newComplex : undefined }
  const lh = (identifier: string) => workspace.supply.lhSupplyRows?.find(row => row.rowIdentifier === identifier)
  const proposal: RowProposal = placement.mode === 'UPDATE' ? { ...base, destination: placement.rowKey, anchorKey: placement.rowKey, reason: '저장할 기존 행을 직접 골랐습니다.' }
    : placement.mode === 'SPLIT' ? { ...base, saveMode: 'SPLIT', lhRow: placement.lhRow, destination: 'new', anchorKey: lh(placement.lhRow)?.attachedRowKey ?? old.anchorKey, reason: '통합행 분리를 직접 골랐습니다.' }
      : placement.mode === 'ATTACH' ? { ...base, saveMode: 'ATTACH', lhRow: placement.lhRow, destination: 'new', anchorKey: placement.anchorKey, reason: 'LH 행에 연결을 직접 골랐습니다.' }
        : { ...base, destination: 'new', anchorKey: placement.anchorKey, reason: '별도 추가를 직접 골랐습니다.' }
  const proposals = review.rows.map((row, at) => at === index ? proposal : row.proposal)
  const changed = new Set([index, ...assignSplitDestinations(proposals, at => review.rows[at].include, workspace.supply)])
  return { ...review, rows: review.rows.map((row, at) => changed.has(at) ? rebuild(review, at, proposals[at], workspace, housing, at === index || row.reviewed) : row) }
}

/** 행 포함·제외를 바꾼다. 분리 묶음 행이면 통합행을 이어받는 첫 행을 포함한 행으로 다시 맞춘다. */
export function includeRow(review: Review, index: number, include: boolean, workspace: JsonWorkspace, housing: HousingCandidates): Review {
  const rows = review.rows.map((row, at) => at === index ? { ...row, include, reviewed: true } : row)
  const proposals = rows.map(row => row.proposal)
  const changed = assignSplitDestinations(proposals, at => rows[at].include, workspace.supply)
  const next = { ...review, rows }
  return { ...next, rows: rows.map((row, at) => changed.includes(at) ? rebuild(next, at, proposals[at], workspace, housing, row.reviewed) : row) }
}
