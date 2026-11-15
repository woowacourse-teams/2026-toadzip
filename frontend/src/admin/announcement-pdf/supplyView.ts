import { assembledCount, cellMatches, complexGroups, groupOf, labelOf, pendingTarget, rowPendingItems, savedValue, sharedOf, type PendingItem, type Review, type ReviewFilter, type SharedReview, type TargetReview } from './review'

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
  return { name: source?.name ?? '주택형 미확인', area: source?.exclusiveArea ?? null, houseTypes: houseTypesOf(review, index) }
}

/** 공고문 주택형 하나가 함께 모집하는 단지 주택타입(84 = 84A·84B). 주택타입이 주택형 표기와 같은 하나뿐이면 묶은 것이 없어 빈 목록이다. */
export function houseTypesOf(review: Review, index: number) {
  const source = review.extraction.sheet.housingTypes[index]
  const types = (source?.houseTypes ?? []).filter(house => house.name)
  if (types.length === 1 && types[0].name === source?.name) return []
  return types.map(house => ({ name: house.name!, area: house.exclusiveArea }))
}

/** 다른 주택형과 함께 쓰는 모집 수(공고문에서 여러 주택형에 걸쳐 병합된 모집 칸, 55A·55B·55C 함께 모집 70) 중 이 주택형 칸이 든 것. */
function acrossTypeWinners(review: Review, index: number): SharedReview[] {
  const { quotas, cells } = review.extraction.sheet
  return sharedOf(review, index).filter(entry => quotas[entry.quota].kind === 'WINNER' && quotas[entry.quota].members.some(member => cells[member].housingType !== index))
}
const sharedCount = (entry: SharedReview) => { const value = savedValue(entry.cell); return typeof value === 'number' ? value : null }

/**
 * 주택형 목록의 모집수 표기. 병합 모집은 주택형 하나의 수가 아니라 나누지 않고 '함께 70'으로 보이고, 주택형 모집수도 있으면 함께 적는다(12 · 함께 70).
 * 둘 다 없거나 함께 쓰는 수를 읽지 못했으면 미확인이다.
 */
export function rowCountText(review: Review, index: number) {
  const own = assembledCount(review.rows[index]), shared = acrossTypeWinners(review, index).map(sharedCount)
  if (shared.some(count => count === null) || own === null && !shared.length) return '미확인'
  return [...own === null ? [] : [String(own)], ...shared.map(count => `함께 ${count}`)].join(' · ')
}

/** 단지 주택형들의 모집 합. 병합 모집은 한 번만 더하고, 모집수를 모르는 주택형이 있으면 null이다. */
export function groupCount(review: Review, indexes: number[]) {
  const shared = new Map(indexes.flatMap(index => acrossTypeWinners(review, index).map(entry => [entry.quota, sharedCount(entry)] as const)))
  const own = indexes.map(index => assembledCount(review.rows[index]))
  if ([...shared.values()].some(count => count === null) || indexes.some((index, at) => own[at] === null && !acrossTypeWinners(review, index).length)) return null
  return [...own, ...shared.values()].reduce<number>((total, count) => total + (count ?? 0), 0)
}
