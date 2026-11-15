import type { SheetCell, SheetHousingType, SheetLeaseTerms, SheetQuota, SheetTarget, SupplySheet } from './supplySheet'
import { cellName, removeCells, updateHousingType } from './sheetEdits'
import { groupsText } from './targetGroups'

/**
 * 최신값 위에 다시 맞출 때 내 입력과 다른 관리자의 변경이 겹친 항목. 겹친 항목은 최신값으로 두고, 내 입력을 고르면 keepMine으로 바꾼다.
 * 내가 고친 항목이 최신값에서 사라졌으면 keepMine이 없는 경우가 있다(사라진 주택형·칸은 입력을 버리는 것만 고를 수 있다).
 */
export type CorrectionConflict = { id: string; scope: string; field: string; mine: string; current: string; missing: boolean
  keepMine?: (sheet: SupplySheet) => SupplySheet }
type Group<T> = [string, (keyof T)[]]
const equal = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b)
const pick = <T,>(item: T, fields: (keyof T)[]) => Object.fromEntries(fields.map(field => [field, item[field] ?? null])) as Partial<T>
function text(values: object) {
  return Object.entries(values).filter(([, value]) => value !== null && value !== '')
    .map(([field, value]) => field === 'groups' ? groupsText(value as SheetTarget['groups']) : isNamed(value) ? `새 주택형 ${value.name}` : String(value))
    .join(' · ') || '미확인'
}
function isNamed(value: unknown): value is { name: string } {
  return typeof value === 'object' && value !== null && 'name' in value
}
const typeGroups: Group<SheetHousingType>[] = [['단지·주택형 연결', ['housingComplexId', 'housingComplexName', 'housingTypeId', 'newHousingType']],
  ['공고 단지', ['announcementComplexId', 'complexName']], ['주택형 표기', ['name']], ['전용면적 (㎡)', ['exclusiveArea']], ['공급면적 (㎡)', ['supplyArea']],
  ['입주 예정 연월', ['expectedMoveInMonth']], ['공급 PNU', ['supplyPnu']], ['공급 구분', ['category']], ['표시 순서', ['displayOrder']]]
const targetFields: Group<SheetTarget>[] = [['공급대상', ['label', 'applicationCondition']], ['공급대상 분류', ['groups']], ['표시 순서', ['displayOrder']]]
const cellGroups: Group<SheetCell>[] = [['공급대상', ['target']], ['표시 순서', ['displayOrder']]]
const leaseGroups: Group<SheetLeaseTerms>[] = [['보증금 (원)', ['rentalDeposit']], ['월 임대료 (원)', ['monthlyRent']], ['전환 보증금 (원)', ['convertedDeposit']]]
const kindLabel = { WINNER: '모집', RESERVE: '예비' } as const
const membersKey = (members: string[]) => [...members].sort().join(',')
const quotaKey = (quota: Pick<SheetQuota, 'kind' | 'members'>) => `${quota.kind}|${membersKey(quota.members)}`
const leaseKey = (lease: Pick<SheetLeaseTerms, 'members'>) => membersKey(lease.members)
function byKey<T>(items: T[], key: (item: T) => string) { return new Map(items.map(item => [key(item), item])) }

/**
 * 화면이 본 공급표(base)에서 내가 고친 것(draft)만 최신 공급표(latest)에 다시 적용한다. 주택형·공급대상·칸은 키로, 묶음은 종류와 멤버 칸으로,
 * 임대조건은 멤버 칸으로 맞춘다. 내가 지운 것은 지우고 새로 만든 것은 더한다. 결과에서 사라진 칸은 묶음·임대조건 멤버에서 뺀다.
 */
export function rebaseCorrection(base: SupplySheet, draft: SupplySheet, latest: SupplySheet) {
  const conflicts: CorrectionConflict[] = []
  function merge<T extends object>(before: T, mine: T, current: T, groups: Group<T>[], id: string, scope: string, set: (sheet: SupplySheet, patch: Partial<T>) => SupplySheet): T {
    let result = current
    for (const [field, keys] of groups) {
      const was = pick(before, keys), now = pick(mine, keys), theirs = pick(current, keys)
      if (equal(was, now)) continue
      if (equal(was, theirs) || equal(now, theirs)) result = { ...result, ...now }
      else conflicts.push({ id: `${id}:${field}`, scope, field, mine: text(now), current: text(theirs), missing: false, keepMine: sheet => set(sheet, now) })
    }
    return result
  }
  function missing(id: string, scope: string, keepMine?: CorrectionConflict['keepMine']) {
    conflicts.push({ id: `${id}:missing`, scope, field: '다른 관리자가 지운 항목', mine: '내 입력 있음', current: '없음', missing: true, keepMine })
  }
  function keyed<T extends { key: string }>(name: string, items: (sheet: SupplySheet) => T[], groups: Group<T>[], scope: (item: T) => string,
    set: (sheet: SupplySheet, key: string, patch: Partial<T>) => SupplySheet) {
    const before = byKey(items(base), item => item.key), mine = byKey(items(draft), item => item.key), theirs = byKey(items(latest), item => item.key)
    const result = items(latest).flatMap(current => {
      const was = before.get(current.key), now = mine.get(current.key)
      if (!was) return [current]
      if (!now) return []
      return [merge(was, now, current, groups, `${name}:${current.key}`, scope(now), (sheet, patch) => set(sheet, current.key, patch))]
    })
    for (const now of items(draft)) {
      const was = before.get(now.key)
      if (!was) result.push(now)
      else if (!theirs.has(now.key) && !equal(was, now)) missing(`${name}:${now.key}`, scope(now))
    }
    return result
  }
  const housingTypes = keyed('type', sheet => sheet.housingTypes, typeGroups, type => `${type.complexName} · ${type.name || '이름 없는 주택형'}`, updateHousingType)
  const targets = keyed('target', sheet => sheet.targets, targetFields, target => `공급대상 ${target.label || '이름 없음'}`,
    (sheet, key, patch) => ({ ...sheet, targets: sheet.targets.map(target => target.key === key ? { ...target, ...patch } : target) }))
  const cells = keyed('cell', sheet => sheet.cells, cellGroups, cell => cellName(draft, cell.key),
    (sheet, key, patch) => ({ ...sheet, cells: sheet.cells.map(cell => cell.key === key ? { ...cell, ...patch } : cell) }))

  const beforeQuotas = byKey(base.quotas, quotaKey), mineQuotas = byKey(draft.quotas, quotaKey), theirQuotas = byKey(latest.quotas, quotaKey)
  const quotaScope = (quota: SheetQuota) => `${kindLabel[quota.kind]} 수 · ${quota.members.map(member => cellName(draft, member)).join('·')}`
  const setCount = (key: string, count: number) => (sheet: SupplySheet) => ({ ...sheet, quotas: sheet.quotas.map(quota => quotaKey(quota) === key ? { ...quota, count } : quota) })
  const quotas = latest.quotas.flatMap(current => {
    const key = quotaKey(current), was = beforeQuotas.get(key), now = mineQuotas.get(key)
    if (!was) return [current]
    if (!now) return []
    if (was.count === now.count) return [current]
    if (current.count === was.count) return [{ ...current, count: now.count }]
    if (current.count !== now.count) conflicts.push({ id: `quota:${key}`, scope: quotaScope(now), field: '수', mine: String(now.count), current: String(current.count), missing: false, keepMine: setCount(key, now.count) })
    return [current]
  })
  for (const now of draft.quotas) {
    const key = quotaKey(now), was = beforeQuotas.get(key), theirs = theirQuotas.get(key)
    if (!was && !theirs) quotas.push(now)
    else if (!was && theirs && theirs.count !== now.count) conflicts.push({ id: `quota:${key}`, scope: quotaScope(now), field: '수', mine: String(now.count), current: String(theirs.count), missing: false, keepMine: setCount(key, now.count) })
    else if (was && !theirs && was.count !== now.count) missing(`quota:${key}`, quotaScope(now), sheet => ({ ...sheet, quotas: [...sheet.quotas, now] }))
  }

  const beforeLeases = byKey(base.leaseTerms, leaseKey), mineLeases = byKey(draft.leaseTerms, leaseKey), theirLeases = byKey(latest.leaseTerms, leaseKey)
  const leaseScope = (lease: SheetLeaseTerms) => `임대조건 · ${lease.members.map(member => cellName(draft, member)).join('·')}`
  const setLease = (key: string) => (sheet: SupplySheet, patch: Partial<SheetLeaseTerms>) => ({ ...sheet, leaseTerms: sheet.leaseTerms.map(lease => leaseKey(lease) === key ? { ...lease, ...patch } : lease) })
  const leaseTerms = latest.leaseTerms.flatMap(current => {
    const key = leaseKey(current), was = beforeLeases.get(key), now = mineLeases.get(key)
    if (!was) return [current]
    if (!now) return []
    return [merge(was, now, current, leaseGroups, `lease:${key}`, leaseScope(now), setLease(key))]
  })
  for (const now of draft.leaseTerms) {
    const key = leaseKey(now), was = beforeLeases.get(key), theirs = theirLeases.get(key)
    if (!was && !theirs) leaseTerms.push(now)
    else if (!was && theirs) leaseTerms.splice(leaseTerms.indexOf(theirs), 1, merge({ ...theirs, rentalDeposit: null, monthlyRent: null, convertedDeposit: null }, now, theirs, leaseGroups, `lease:${key}`, leaseScope(now), setLease(key)))
    else if (was && !theirs && !equal(was, now)) missing(`lease:${key}`, leaseScope(now), sheet => ({ ...sheet, leaseTerms: [...sheet.leaseTerms, now] }))
  }

  const typeKeys = new Set(housingTypes.map(type => type.key)), targetKeys = new Set(targets.map(target => target.key))
  const kept = cells.filter(cell => typeKeys.has(cell.housingType) && targetKeys.has(cell.target))
  const cellKeys = new Set(kept.map(cell => cell.key))
  const dangling = [...quotas, ...leaseTerms].flatMap(item => item.members).filter(member => !cellKeys.has(member))
  return { sheet: removeCells({ housingTypes, targets, cells: kept, quotas, leaseTerms }, dangling), conflicts }
}

export function resolveConflict(sheet: SupplySheet, conflict: CorrectionConflict, keepMine: boolean) {
  return keepMine && conflict.keepMine ? conflict.keepMine(sheet) : sheet
}
