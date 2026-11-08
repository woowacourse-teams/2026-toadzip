import type { JsonWorkspace } from '../announcement-json/api'
import type { MatchingHousingType } from '../ingest/supplyMatchingApi'
import { expectedHousingType, linkedHousingTypeId, pendingRow, type Review } from './review'

/**
 * 단지 주택형 하나를 이번 공고 기준으로 본 값. 건설호수·면적·기본 임대조건은 단지 정보(주택형 후보)이고,
 * 모집수·예비자 수·연결 공급행은 이 공고의 공급행에서 센다. 값이 없으면 null로 두고 추정하지 않는다.
 */
export type ComplexTypeView = { type: MatchingHousingType; rows: { key: string; id: number | null; typeName: string; householdCount: number | null }[]
  recruitment: number | null; reserve: number | null; extracted: number[]; badges: ('미연결' | '중복 후보' | '확인 필요')[] }

export function complexTypeViews(complexId: number | null, review: Review, workspace: JsonWorkspace, types: MatchingHousingType[]): ComplexTypeView[] {
  const rows = workspace.supply.rows.filter(row => !row.excluded && row.housingComplexId === complexId)
  return types.map(type => {
    const linked = rows.filter(row => row.housingTypeId === type.id || (row.linkedHousingTypes ?? []).some(link => link.housingTypeId === type.id))
    const counts = linked.map(row => row.groupId !== null ? row.sharedHouseholdCount : row.householdCount)
    const reserves = linked.flatMap(row => row.targets.filter(target => !target.excluded).map(target => target.reserveCount))
    // 연결 주택형을 비운 행은 저장할 때 서버가 같은 전용면적 주택형에 연결한다.
    const extracted = review.rows.flatMap((row, index) => row.include && ((linkedHousingTypeId(row) ?? expectedHousingType(row)?.id) === type.id || row.subtypes?.some(subtype => subtype.choice === type.id)) ? [index] : [])
    const badges: ComplexTypeView['badges'] = []
    if (!linked.length) badges.push('미연결')
    if (linked.length > 1 || extracted.length > 1) badges.push('중복 후보')
    if (extracted.some(index => pendingRow(review.rows[index]))) badges.push('확인 필요')
    return { type, rows: linked.map(row => ({ key: row.key, id: row.id, typeName: row.typeName, householdCount: row.householdCount })),
      recruitment: linked.length && counts.every(count => count !== null) ? (counts as number[]).reduce((sum, count) => sum + count, 0) : null,
      reserve: reserves.length && reserves.every(count => count !== null) ? (reserves as number[]).reduce((sum, count) => sum + count, 0) : null,
      extracted, badges }
  })
}
