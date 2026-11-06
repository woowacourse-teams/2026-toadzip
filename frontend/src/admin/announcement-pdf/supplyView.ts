import { groupOf } from './correspondence'
import { cellMatches, complexGroups, pendingPrice, pendingRow, pendingSubtypes, pendingTarget, type Review, type ReviewFilter, type RowReview, type TargetReview } from './review'

export type SupplyView = ReturnType<typeof supplyView>
/**
 * 공급정보에서 지금 보이는 단지와 공급행. 고른 행이 필터에 맞지 않으면 그 단지에서 필터에 맞는 첫 행을 보인다.
 * 이전·다음 이동 순서(order)는 단지 순서대로 모든 단지의 필터에 맞는 행이다. 고정 공급행 머리와 편집 영역이 같은 계산을 쓴다.
 */
export function supplyView(review: Review, selected: number, filter: ReviewFilter, savesHousingTypes: boolean) {
  const groups = complexGroups(review), groupIndex = Math.max(0, groupOf(review, selected)), group = groups[groupIndex] as typeof groups[number] | undefined
  const matches = (index: number) => rowMatches(review.rows[index], filter, savesHousingTypes)
  const shown = group ? group.indexes.filter(matches) : []
  const current = shown.includes(selected) ? selected : shown[0] ?? -1
  return { groups, groupIndex, group, shown, current, order: groups.flatMap(item => item.indexes).filter(matches) }
}

export function targetMatches(target: TargetReview, filter: ReviewFilter) {
  return filter === 'all' || target.cells.some(cell => cellMatches(cell, filter)) || filter === 'pending' && pendingTarget(target)
}
function rowMatches(row: RowReview, filter: ReviewFilter, savesHousingTypes: boolean) {
  if (filter === 'all') return true
  return row.include && (filter === 'pending' && (pendingRow(row) || pendingSubtypes(row).length > 0 || savesHousingTypes && pendingPrice(row)) || row.cells.some(cell => cellMatches(cell, filter)) || row.targets.some(target => targetMatches(target, filter)))
}
