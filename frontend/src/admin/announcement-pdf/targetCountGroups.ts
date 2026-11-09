import type { CorrectionTarget, TargetCountGroup } from '../supply/supplyCorrectionApi'
export { dropLoneGroups } from '../supply/supplyCorrectionApi'
import type { Cell } from './review'
import type { Extraction } from './extraction'

/**
 * 한 공급행 안에서 대상 여럿이 함께 쓰는 수(공통 모집수). 공고문이 '청년(소득 없음)·청년(소득 있음) 예비 20명'처럼 대상끼리 한 번만 적은 수는
 * 대상마다 나누거나 같은 수를 중복 저장하지 않고 공급행 아래 묶음 하나로 저장한다(서버 SupplyTargetCountGroups).
 * 단지·공급행이 다른 행끼리 함께 쓰는 행 모집수는 여기서 다루지 않는다(subtypes의 접기와 공통 모집 묶음).
 */
export type SharedField = 'supplyHouseholdCount' | 'reserveCount'
/** 대상 하나가 쓰는 종류별 공통 수. members는 그 수를 함께 쓰는 대상 번호다. */
export type SharedGroups = Partial<Record<SharedField, TargetCountGroup & { members: number[] }>>
export type RowCountGroups = Map<number, SharedGroups>
const targetPath = /^supplyRows\[(\d+)\]\.targets\[(\d+)\]\.(supplyHouseholdCount|reserveCount)$/

/** 공급행 rowIndex의 대상 번호 → 종류별 공통 수. 한 공급행·한 종류의 대상 둘 이상이 수를 함께 쓸 때만 묶음이다. */
export function rowCountGroups(extraction: Extraction, rowIndex: number): RowCountGroups {
  const result: RowCountGroups = new Map()
  extraction.sharedCounts.forEach((group, index) => {
    const matches = group.paths.map(path => targetPath.exec(path))
    if (group.count === null || matches.some(match => !match || Number(match[1]) !== rowIndex || match[3] !== matches[0]![3])) return
    const members = [...new Set(matches.map(match => Number(match![2])))]
    if (members.length < 2) return
    const field = matches[0]![3] as SharedField
    for (const member of members) result.set(member, { ...result.get(member), [field]: { key: `pdf:${index}`, count: group.count, members } })
  })
  return result
}

export function sharedCountLabel(field: SharedField, count: number, names: string[]) {
  return `${names.join('·')} 공통 ${field === 'reserveCount' ? `예비 ${count}명` : `모집 ${count}세대`}`
}

/** 공통 수를 쓰는 대상 칸은 대상만의 수가 아니므로 확인할 칸이 아니다. 묶음으로 저장한다고 알린다. */
export function markSharedCells(cells: Cell[], shared: SharedGroups | undefined, names: (members: number[]) => string[]) {
  for (const field of ['supplyHouseholdCount', 'reserveCount'] as const) {
    const group = shared?.[field], cell = cells.find(item => item.field === field)
    if (!group || !cell) continue
    const note = `${sharedCountLabel(field, group.count, names(group.members))}으로 저장합니다. 대상마다 나누지 않습니다.`
    Object.assign(cell, { state: 'notApplicable', selected: false, value: null, note, extracted: { ...cell.extracted, state: 'notApplicable', note } })
  }
}

/** 저장할 대상에 공통 수를 넣는다. PDF가 대상만의 수를 주면 그 종류의 옛 묶음은 뺀다. */
export function applySharedCounts(target: CorrectionTarget, shared: SharedGroups | undefined) {
  const pairs = [['supplyHouseholdCount', 'householdCount', 'householdCountGroup'], ['reserveCount', 'reserveCount', 'reserveCountGroup']] as const
  for (const [field, own, slot] of pairs) {
    const group = shared?.[field]
    if (group) { target[slot] = { key: group.key, count: group.count }; target[own] = null }
    else if (target[own] !== null) target[slot] = null
  }
}
