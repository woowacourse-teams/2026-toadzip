import { cellMatches, complexGroups, groupOf, pendingTarget, rowPendingItems, type PendingItem, type Review, type ReviewFilter, type RowReview, type TargetReview } from './review'

export type SupplyView = ReturnType<typeof supplyView>
/**
 * 공급정보에서 지금 보이는 단지와 공급행. 고른 행이 필터에 맞지 않으면 그 단지에서 필터에 맞는 첫 행을 보인다.
 * 이전·다음 이동 순서(order)는 단지 순서대로 모든 단지의 필터에 맞는 행이다. 고정 공급행 머리와 편집 영역이 같은 계산을 쓴다.
 */
export function supplyView(review: Review, selected: number, filter: ReviewFilter, pending: PendingItem[]) {
  const groups = complexGroups(review), groupIndex = Math.max(0, groupOf(review, selected)), group = groups[groupIndex] as typeof groups[number] | undefined
  // '확인 필요'는 남은 확인 항목(pendingItems)이 있는 행이다. 상태 배지·확인 수·미해결 이동과 같은 기준을 쓴다.
  const matches = (index: number) => filter === 'pending' ? review.rows[index].include && rowPendingItems(pending, index).length > 0 : rowMatches(review.rows[index], filter)
  const shown = group ? group.indexes.filter(matches) : []
  const current = shown.includes(selected) ? selected : shown[0] ?? -1
  return { groups, groupIndex, group, shown, current, order: groups.flatMap(item => item.indexes).filter(matches) }
}

export function targetMatches(target: TargetReview, filter: ReviewFilter) {
  return filter === 'all' || target.cells.some(cell => cellMatches(cell, filter)) || filter === 'pending' && pendingTarget(target)
}
function rowMatches(row: RowReview, filter: ReviewFilter) {
  if (filter === 'all') return true
  return row.include && (row.cells.some(cell => cellMatches(cell, filter)) || row.targets.some(target => targetMatches(target, filter)))
}
