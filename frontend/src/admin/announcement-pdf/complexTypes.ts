import type { JsonWorkspace } from '../announcement-json/api'
import type { MatchingHousingType } from '../ingest/supplyMatchingApi'
import { housingTypeCount, type SupplySheet } from '../supply/supplySheet'
import { expectedHousingType, linkedHousingTypeId, pendingItems, rowPendingItems, type Review } from './review'

/**
 * 단지 주택타입 하나를 이번 공고 기준으로 본 값. 건설호수·면적·기본 임대조건은 단지 정보(주택타입 후보)이고,
 * 모집수·예비자 수·연결 주택형은 지금 공고 공급표에서 센다. 값이 없으면 null로 두고 추정하지 않는다.
 */
export type ComplexTypeView = { type: MatchingHousingType; rows: { key: string; id: number | null; typeName: string; householdCount: number | null }[]
  recruitment: number | null; reserve: number | null; extracted: number[]; badges: ('미연결' | '중복 후보' | '확인 필요')[] }

/** 멤버가 모두 이 주택형의 칸인 예비 묶음의 합. 없으면 null이다. */
function reserveCount(sheet: SupplySheet, housingTypeKey: string) {
  const typeOf = new Map(sheet.cells.map(cell => [cell.key, cell.housingType]))
  const own = sheet.quotas.filter(quota => quota.kind === 'RESERVE' && quota.members.every(member => typeOf.get(member) === housingTypeKey))
  return own.length ? own.reduce((sum, quota) => sum + quota.count, 0) : null
}
const sum = (values: (number | null)[]) => values.length && values.every(value => value !== null) ? (values as number[]).reduce((total, value) => total + value, 0) : null

export function complexTypeViews(complexId: number | null, review: Review, workspace: JsonWorkspace, types: MatchingHousingType[]): ComplexTypeView[] {
  const sheet = workspace.supply.sheet, saved = sheet.housingTypes.filter(type => type.housingComplexId === complexId)
  const pending = pendingItems(review)
  return types.map(type => {
    const linked = saved.filter(item => item.housingTypeId === type.id)
    // 연결 주택타입을 비운 주택형은 저장할 때 같은 이름의 주택타입에 연결한다.
    const extracted = review.rows.flatMap((row, index) => (linkedHousingTypeId(row) ?? expectedHousingType(review, index)?.id) === type.id ? [index] : [])
    const badges: ComplexTypeView['badges'] = []
    if (!linked.length) badges.push('미연결')
    if (linked.length > 1 || extracted.length > 1) badges.push('중복 후보')
    if (extracted.some(index => rowPendingItems(pending, index).length > 0)) badges.push('확인 필요')
    return { type, rows: linked.map(item => ({ key: item.key, id: item.id, typeName: item.name, householdCount: housingTypeCount(sheet, item.key) })),
      recruitment: sum(linked.map(item => housingTypeCount(sheet, item.key))), reserve: sum(linked.map(item => reserveCount(sheet, item.key))), extracted, badges }
  })
}
