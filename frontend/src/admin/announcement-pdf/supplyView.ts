import { cellMatches, complexGroups, groupOf, labelOf, pendingTarget, rowPendingItems, type PendingItem, type Review, type ReviewFilter, type TargetReview } from './review'

export type SupplyView = ReturnType<typeof supplyView>
/**
 * 공급정보에서 지금 보이는 PDF 단지와 주택형. 고른 주택형이 필터에 맞지 않으면 그 단지에서 필터에 맞는 첫 주택형을 보인다.
 * 이전·다음 이동 순서(order)는 단지 순서대로 모든 단지의 필터에 맞는 주택형이다. 고정 머리와 편집 영역이 같은 계산을 쓴다.
 */
export function supplyView(review: Review, selected: number, filter: ReviewFilter, pending: PendingItem[]) {
  const groups = complexGroups(review), groupIndex = Math.max(0, groupOf(review, selected)), group = groups[groupIndex] as typeof groups[number] | undefined
  // '확인 필요'는 남은 확인 항목(pendingItems)이 있는 주택형이다. 상태 배지·확인 수·미해결 이동과 같은 기준을 쓴다.
  const matches = (index: number) => filter === 'pending' ? rowPendingItems(pending, index).length > 0 : rowMatches(review, index, filter)
  const shown = group ? group.indexes.filter(matches) : []
  const current = shown.includes(selected) ? selected : shown[0] ?? -1
  return { groups, groupIndex, group, shown, current, order: groups.flatMap(item => item.indexes).filter(matches) }
}

export function targetMatches(review: Pick<Review, 'extraction' | 'labels'>, target: TargetReview, filter: ReviewFilter) {
  return filter === 'all' || [...target.cells, ...labelOf(review, target.cell)?.cells ?? []].some(cell => cellMatches(cell, filter)) || filter === 'pending' && pendingTarget(review, target)
}
function rowMatches(review: Review, index: number, filter: ReviewFilter) {
  const row = review.rows[index]
  if (filter === 'all') return true
  return row.cells.some(cell => cellMatches(cell, filter)) || row.targets.some(target => targetMatches(review, target, filter))
}

/** 주택형이 지금 공급표의 주택형을 고치는지, 새로 더하는지 */
export function rowKind(review: Review, index: number) { return review.rows[index].existing ? '기존 주택형' : '새 주택형' }
/** PDF 주택형 표기와 전용면적 */
export function rowFacts(review: Review, index: number) {
  const source = review.extraction.sheet.housingTypes[index]
  return { name: source?.name ?? '주택형 미확인', area: source?.exclusiveArea ?? null }
}
