import type { JsonWorkspace } from '../announcement-json/api'
import type { CorrectionRow, LhSupplyRow } from '../supply/supplyCorrectionApi'
import { attachableLhRows, equalArea, lhName, sameComplex, splittableLhRows, typeKey } from './matching'
import { assembledCount, complexGroups, placementMode, type Review } from './review'

/**
 * LH 통합행 하나와 그 행을 주택형별로 적은 PDF 행들. 나누기(split)와 LH대로 1행 유지(keep)는 단지 단위 결정이다.
 * recommended는 AI가 이 LH 행을 표시했거나 PDF 모집수 합이 LH 이번 공급 수와 같을 때다. choice는 행들의 저장 방식에서 읽는다.
 */
export type SplitOffer = { lh: LhSupplyRow; merged: CorrectionRow; members: number[]; recommended: boolean; choice: 'split' | 'keep' | null }
/** 공급행이 없는 LH 행. attachedTo는 이 LH 행에 붙인 PDF 행, kept는 운영자가 이번 보정에서 그대로 두기로 한 경우다. */
export type LeftoverLh = { lh: LhSupplyRow; attachedTo: number | null; kept: boolean }
export type Correspondence = { lhRows: LhSupplyRow[]; offers: SplitOffer[]; leftovers: LeftoverLh[] }

export const pdfCount = (review: Review, index: number) => {
  const count = review.extraction.supplyRows[index].values.totalSupplyHouseholdCount
  return typeof count === 'number' ? count : null
}
export const pdfType = (review: Review, index: number) => review.extraction.supplyRows[index].typeName ?? `공급행 ${index + 1}`

/**
 * LH 행마다 그 행이 속한 PDF 단지 묶음(complexGroups 순번). PDF 행이 가리킨 LH 행 → PDF 행의 저장 위치·단지에 붙은 LH 행 → 단지 표기 순서로 찾고,
 * 어디에도 속하지 않은 공급행 없는 LH 행은 첫 단지에 둬 화면에서 빠지지 않게 한다.
 */
function lhOwners(review: Review, workspace: JsonWorkspace) {
  const groups = complexGroups(review), rows = workspace.supply.rows
  const attachable = new Set(attachableLhRows(workspace.supply).map(lh => lh.rowIdentifier))
  const referenced = (lh: LhSupplyRow, indexes: number[]) => indexes.some(index => {
    const proposal = review.rows[index].proposal
    return [review.extraction.supplyRows[index].values.lhSourceRow, proposal.lhRow, proposal.proposedLhRow].includes(lh.rowIdentifier)
  })
  const placed = (lh: LhSupplyRow, indexes: number[]) => {
    if (!lh.attachedRowKey) return false
    const attached = rows.find(row => row.key === lh.attachedRowKey)
    return indexes.some(index => {
      const proposal = review.rows[index].proposal
      return [proposal.destination, proposal.anchorKey, ...proposal.recommended].includes(lh.attachedRowKey!)
        || attached?.housingComplexId != null && attached.housingComplexId === proposal.complexId
    })
  }
  const owners = new Map<string, number>()
  for (const lh of workspace.supply.lhSupplyRows ?? []) {
    const owner = [referenced, placed, (item: LhSupplyRow, indexes: number[]) => sameComplex(item.complexLabel, review.extraction.supplyRows[indexes[0]].complexName)]
      .map(test => groups.findIndex(group => test(lh, group.indexes))).find(at => at >= 0)
    if (owner !== undefined) owners.set(lh.rowIdentifier, owner)
    else if (attachable.has(lh.rowIdentifier) && groups.length) owners.set(lh.rowIdentifier, 0)
  }
  return owners
}

/** PDF 단지 묶음 하나(complexGroups 순번)의 LH 행과 PDF 행 대응. 단지 탭 위의 대응 영역이 쓴다. */
export function complexCorrespondence(review: Review, workspace: JsonWorkspace, groupIndex: number): Correspondence {
  const indexes = complexGroups(review)[groupIndex]?.indexes ?? []
  const owners = lhOwners(review, workspace)
  const lhRows = (workspace.supply.lhSupplyRows ?? []).filter(lh => owners.get(lh.rowIdentifier) === groupIndex)
  const splittable = new Set(splittableLhRows(workspace.supply).map(lh => lh.rowIdentifier))
  const attachable = new Set(attachableLhRows(workspace.supply).map(lh => lh.rowIdentifier))
  const offers = lhRows.filter(lh => splittable.has(lh.rowIdentifier)).flatMap((lh): SplitOffer[] => {
    const merged = workspace.supply.rows.find(row => row.key === lh.attachedRowKey)!
    const members = indexes.filter(index => {
      const proposal = review.rows[index].proposal
      return [review.extraction.supplyRows[index].values.lhSourceRow, proposal.lhRow, proposal.proposedLhRow].includes(lh.rowIdentifier)
        || proposal.destination === merged.key || !proposal.saveMode && ['', 'new'].includes(proposal.destination) && proposal.recommended.includes(merged.key)
    })
    if (members.length < 2) return []
    const counts = members.map(index => pdfCount(review, index))
    const sum = counts.every(count => count !== null) ? (counts as number[]).reduce((total, count) => total + count, 0) : null
    const recommended = members.some(index => review.extraction.supplyRows[index].values.lhSourceRow === lh.rowIdentifier) || sum !== null && sum === lh.householdCount
    const included = members.filter(index => review.rows[index].include)
    const choice = included.some(index => review.rows[index].proposal.saveMode === 'SPLIT' && review.rows[index].proposal.lhRow === lh.rowIdentifier) ? 'split'
      : included.some(index => !review.rows[index].proposal.saveMode && review.rows[index].proposal.destination === merged.key) ? 'keep' : null
    return [{ lh, merged, members, recommended, choice }]
  })
  const leftovers = lhRows.filter(lh => attachable.has(lh.rowIdentifier)).map(lh => ({ lh,
    attachedTo: review.rows.findIndex(row => row.include && row.proposal.saveMode === 'ATTACH' && row.proposal.lhRow === lh.rowIdentifier),
    kept: Boolean(review.keptLhRows?.includes(lh.rowIdentifier)) })).map(item => ({ ...item, attachedTo: item.attachedTo < 0 ? null : item.attachedTo }))
  return { lhRows, offers, leftovers }
}

/** 이 PDF 행이 든 단지 묶음 순번 */
export function groupOf(review: Review, index: number) {
  return complexGroups(review).findIndex(group => group.indexes.includes(index))
}

const area = (value: string | number | null | undefined) => value == null || value === '' ? null : Number(value)

/** LH와 PDF가 이 단지를 어떻게 적었는지 한 문장으로 적는다. */
export function offerSummary(review: Review, offer: SplitOffer, single: boolean) {
  const counts = offer.members.map(index => pdfCount(review, index))
  const sum = counts.every(count => count !== null) ? (counts as number[]).reduce((total, count) => total + count, 0) : null
  const lhArea = area(offer.lh.exclusiveArea)
  const subject = single ? '이 단지를' : `'${offer.lh.typeName?.trim() || '표기 없음'}' 주택형을`
  return `LH는 ${subject} 1행(${offer.lh.householdCount != null ? `${offer.lh.householdCount}세대` : '모집수 미확인'}, ${lhArea === null ? '면적 없음' : `전용 ${lhArea}㎡`})으로, `
    + `PDF는 ${offer.members.length}행(${counts.map(count => count ?? '미확인').join('·')}, 합계 ${sum ?? '미확인'})으로 적었습니다.`
}

/** 받침 유무로 조사를 붙인다. 숫자·영문으로 끝나면 한글 읽기를 알 수 없어 받침 없는 쪽(가)으로 둔다. */
function subject(word: string, withFinal: string, withoutFinal: string) {
  const last = word.charCodeAt(word.length - 1)
  return `${word}${last >= 0xac00 && last <= 0xd7a3 && (last - 0xac00) % 28 !== 0 ? withFinal : withoutFinal}`
}
/** 고른 결정이 저장하면 어떻게 되는지 적는다. 모집수는 저장할 값(assembledCount)이다. */
export function offerResult(review: Review, offer: SplitOffer) {
  const { merged, lh } = offer
  const name = `#${String(merged.id)}(${merged.householdCount != null ? `${merged.householdCount}세대` : '모집수 미확인'})`
  const counted = (index: number) => `${pdfType(review, index)} ${assembledCount(review.rows[index]) ?? '모집수 미확인'}${assembledCount(review.rows[index]) === null ? '' : '세대'}`
  if (offer.choice === 'split') {
    const split = offer.members.filter(index => review.rows[index].include && review.rows[index].proposal.saveMode === 'SPLIT' && review.rows[index].proposal.lhRow === lh.rowIdentifier)
    const head = split.find(index => review.rows[index].proposal.destination === merged.key)
    const rest = split.filter(index => index !== head)
    if (head === undefined) return ''
    const added = rest.map(counted).join(' · ')
    return `${name}이 ${subject(counted(head), '이', '가')} 되고${rest.length ? ` ${subject(added, '이', '가')} 새로 생깁니다.` : ' 나눌 다른 행이 없습니다.'}`
  }
  if (offer.choice === 'keep') {
    const kept = offer.members.find(index => review.rows[index].include && !review.rows[index].proposal.saveMode && review.rows[index].proposal.destination === merged.key)!
    const dropped = offer.members.filter(index => !review.rows[index].include)
    return `${name}을 ${pdfType(review, kept)} 행 값으로 고칩니다.${dropped.length ? ` ${dropped.map(index => pdfType(review, index)).join(' · ')} 행은 이번 보정에서 반영하지 않습니다.` : ''}`
  }
  return ''
}

/**
 * 이 행의 대응을 운영자가 따로 확인해야 하는 까닭. 근거가 없어 고르지 않았거나, 면적이 맞지 않거나, 이번 공급 수가 0세대인 LH 행이거나,
 * AI가 표시한 LH 행과 지금 저장할 곳이 다를 때만 돌려준다. 그 밖의 자동 대응 근거는 화면에 기본으로 보이지 않는다.
 */
export function placementDoubt(review: Review, index: number, workspace: JsonWorkspace): string | null {
  const row = review.rows[index], proposal = row.proposal, source = review.extraction.supplyRows[index]
  if (!row.include) return null
  if (proposal.ambiguous && !proposal.destination) return '표기·전용면적이 맞는 기존 공급정보가 없어 자동으로 고르지 않았습니다. 공고문과 대조해 저장할 곳을 고르세요.'
  const lhRows = workspace.supply.lhSupplyRows ?? [], mode = placementMode(proposal)
  const lh = proposal.saveMode ? lhRows.find(item => item.rowIdentifier === proposal.lhRow) : lhRows.find(item => item.attachedRowKey && item.attachedRowKey === proposal.destination)
  if (lh && proposal.saveMode && lh.householdCount === 0) return `LH 행 '${lhName(lh)}'의 이번 공급 수가 0세대입니다. 이 PDF 행이 그 LH 행에서 나온 것이 맞는지 공고문과 대조하세요.`
  const marked = typeof source.values.lhSourceRow === 'string' ? lhRows.find(item => item.rowIdentifier === source.values.lhSourceRow) : undefined
  if (marked && marked.rowIdentifier !== lh?.rowIdentifier) return `AI는 이 행을 LH 행 '${lhName(marked)}'에서 나온 행으로 봤지만, 지금은 ${lh ? `LH 행 '${lhName(lh)}'` : '다른 곳'}에 저장합니다.`
  const pdfArea = source.values.exclusiveArea
  if (mode === 'UPDATE' && typeof pdfArea === 'number') {
    const existing = workspace.supply.rows.find(item => item.key === proposal.destination)
    if (existing && typeof existing.exclusiveArea === 'number' && !equalArea(existing.exclusiveArea, pdfArea) && typeKey(existing.typeName) !== typeKey(source.typeName))
      return `PDF는 전용 ${pdfArea}㎡인데 #${String(existing.id)}(${existing.typeName})은 전용 ${existing.exclusiveArea}㎡입니다.`
  }
  if (mode === 'ATTACH' && lh && typeof pdfArea === 'number') {
    const lhArea = area(lh.exclusiveArea)
    if (lhArea !== null && Math.abs(lhArea - pdfArea) >= 0.01) return `PDF는 전용 ${pdfArea}㎡인데 LH 행 '${lhName(lh)}'의 전용면적은 ${lhArea}㎡입니다.`
  }
  return null
}

/** 행 하나가 저장될 곳을 한 문장으로 적는다. */
export function placementSentence(review: Review, index: number, workspace: JsonWorkspace) {
  const proposal = review.rows[index].proposal, mode = placementMode(proposal)
  const existing = workspace.supply.rows.find(item => item.key === proposal.destination)
  const lh = (workspace.supply.lhSupplyRows ?? []).find(item => item.rowIdentifier === proposal.lhRow)
  if (mode === 'UPDATE' && existing) return `기존 공급정보 #${String(existing.id)}(${existing.typeName})을 고칩니다.`
  if (mode === 'ADD') return '새 공급정보로 추가합니다.'
  if (mode === 'SPLIT') {
    const merged = workspace.supply.rows.find(item => item.key === lh?.attachedRowKey)
    const name = merged ? `#${String(merged.id)}(${merged.householdCount != null ? `${merged.householdCount}세대` : '모집수 미확인'})` : 'LH 통합행'
    return existing ? `${name}을 나눈 첫 행입니다. 저장하면 ${merged ? `#${String(merged.id)}` : '통합행'}이 이 주택형이 됩니다.` : `${name}을 나눈 행입니다. 새 공급정보로 저장합니다.`
  }
  if (mode === 'ATTACH') return `LH 행 '${lh ? lhName(lh) : proposal.lhRow ?? ''}'에 붙는 새 공급정보입니다.`
  return '저장할 곳을 아직 정하지 않았습니다.'
}

/** 공급행 목록에 보일 저장할 곳 요약 */
export function placementShort(review: Review, index: number, workspace: JsonWorkspace) {
  const proposal = review.rows[index].proposal, mode = placementMode(proposal)
  const id = (key: string | null | undefined) => workspace.supply.rows.find(item => item.key === key)?.id
  if (mode === 'UPDATE') return `#${String(id(proposal.destination))} 고침`
  if (mode === 'ADD') return '새로 추가'
  if (mode === 'SPLIT') return `#${String(id(workspace.supply.lhSupplyRows?.find(lh => lh.rowIdentifier === proposal.lhRow)?.attachedRowKey))} 나눔`
  if (mode === 'ATTACH') return 'LH 행에 붙임'
  return '미정'
}
