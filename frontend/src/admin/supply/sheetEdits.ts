import { cellsOf, leaseOf, ownCount, type QuotaKind, type SheetCell, type SheetHousingType, type SheetLeaseTerms, type SheetQuota, type SheetTarget, type SupplySheet } from './supplySheet'

/**
 * 공급정보 편집표가 공급표를 고치는 동작. 모두 새 공급표를 돌려주고, 칸·공급대상·주택형을 더하거나 지워도 공급표가 어긋나지 않게 한다:
 * 지운 칸은 묶음·임대조건 멤버에서 빼고, 멤버가 없어진 묶음·임대조건과 어느 칸도 쓰지 않는 공급대상은 지운다.
 */
export const newKey = () => `new:${crypto.randomUUID()}`
export const emptySheet = (): SupplySheet => ({ housingTypes: [], targets: [], cells: [], quotas: [], leaseTerms: [] })
const nextOrder = (items: { displayOrder: number }[]) => items.reduce((next, item) => Math.max(next, item.displayOrder + 1), 0)
const sameMembers = (left: string[], right: string[]) => left.length === right.length && left.every(key => right.includes(key))
/** 수량 입력 칸의 값. 빈 칸은 미확인(null), 숫자 0은 확인된 0이다. */
export const countInput = (text: string) => text === '' ? null : Math.max(0, Math.trunc(Number(text)))
export type LeaseField = 'rentalDeposit' | 'monthlyRent' | 'convertedDeposit'
export const leaseFields: LeaseField[] = ['rentalDeposit', 'monthlyRent', 'convertedDeposit']

/** 칸 하나의 값: 공급대상, 그 대상만의 모집·예비 수와 임대조건. 검토 키 `cell:{id}`가 가리키는 범위다. */
export function cellValues(sheet: SupplySheet, cell: SheetCell) {
  const target = sheet.targets.find(item => item.key === cell.target)
  const lease = leaseOf(sheet, cell.key)
  return { label: target?.label ?? '', applicationCondition: target?.applicationCondition ?? '',
    winner: ownCount(sheet, cell.key, 'WINNER'), reserve: ownCount(sheet, cell.key, 'RESERVE'),
    rentalDeposit: lease?.rentalDeposit ?? null, monthlyRent: lease?.monthlyRent ?? null, convertedDeposit: lease?.convertedDeposit ?? null }
}

/** 화면에 보이는 칸 이름("49A 일반"). 신청조건이 있으면 괄호로 붙인다. */
export function cellName(sheet: SupplySheet, cellKey: string) {
  const cell = sheet.cells.find(item => item.key === cellKey)
  if (!cell) return '사라진 칸'
  const type = sheet.housingTypes.find(item => item.key === cell.housingType)
  const target = sheet.targets.find(item => item.key === cell.target)
  return `${type?.name || '이름 없는 주택형'} ${target?.label || '이름 없는 대상'}${target?.applicationCondition ? `(${target.applicationCondition})` : ''}`
}

/** 한 주택형의 모든 칸이 함께 쓰는 당첨 묶음(주택형 모집수). 칸이 없으면 없다. */
export function wholeTypeQuota(sheet: SupplySheet, housingTypeKey: string): SheetQuota | null {
  const members = cellsOf(sheet, housingTypeKey).map(cell => cell.key)
  return members.length ? sheet.quotas.find(quota => quota.kind === 'WINNER' && sameMembers(quota.members, members)) ?? null : null
}

export function removeCells(sheet: SupplySheet, cellKeys: string[]): SupplySheet {
  const removed = new Set(cellKeys)
  const cells = sheet.cells.filter(cell => !removed.has(cell.key))
  const prune = <T extends { members: string[] }>(items: T[]) => items
    .map(item => item.members.some(member => removed.has(member)) ? { ...item, members: item.members.filter(member => !removed.has(member)) } : item)
    .filter(item => item.members.length > 0)
  const used = new Set(cells.map(cell => cell.target))
  return { ...sheet, cells, targets: sheet.targets.filter(target => used.has(target.key)), quotas: prune(sheet.quotas), leaseTerms: prune(sheet.leaseTerms) }
}

export function removeHousingType(sheet: SupplySheet, housingTypeKey: string): SupplySheet {
  const next = removeCells(sheet, cellsOf(sheet, housingTypeKey).map(cell => cell.key))
  return { ...next, housingTypes: next.housingTypes.filter(type => type.key !== housingTypeKey) }
}

export function addHousingType(sheet: SupplySheet, fields: Partial<SheetHousingType> = {}): { sheet: SupplySheet; key: string } {
  const type: SheetHousingType = { key: newKey(), id: null, announcementComplexId: null, complexName: '', housingComplexId: null, housingComplexName: null,
    housingTypeId: null, newHousingType: null, name: '', exclusiveArea: null, supplyArea: null, expectedMoveInMonth: null, supplyPnu: null, category: null,
    adminModified: false, ...fields, displayOrder: nextOrder(sheet.housingTypes) }
  return { sheet: { ...sheet, housingTypes: [...sheet.housingTypes, type] }, key: type.key }
}

export function updateHousingType(sheet: SupplySheet, housingTypeKey: string, patch: Partial<SheetHousingType>): SupplySheet {
  return { ...sheet, housingTypes: sheet.housingTypes.map(type => type.key === housingTypeKey ? { ...type, ...patch } : type) }
}

/** 표시 순서를 한 칸 옮기고 0부터 다시 매긴다. */
export function moveHousingType(sheet: SupplySheet, housingTypeKey: string, delta: -1 | 1): SupplySheet {
  const ordered = [...sheet.housingTypes].sort((a, b) => a.displayOrder - b.displayOrder)
  const at = ordered.findIndex(type => type.key === housingTypeKey)
  if (at < 0 || !ordered[at + delta]) return sheet
  ;[ordered[at], ordered[at + delta]] = [ordered[at + delta], ordered[at]]
  return { ...sheet, housingTypes: ordered.map((type, order) => ({ ...type, displayOrder: order })) }
}

/**
 * 주택형에 빈 공급대상 칸을 더한다. 칸이 둘 이상인 주택형의 모집수(모든 칸이 함께 쓰는 당첨 묶음)는 새 칸도 멤버로 넣어 주택형 모집수로 남긴다.
 * 칸이 하나뿐이면 그 묶음은 그 대상만의 수와 구분할 수 없어 넓히지 않는다.
 */
export function addCell(sheet: SupplySheet, housingTypeKey: string): SupplySheet {
  const typeCells = cellsOf(sheet, housingTypeKey)
  const target: SheetTarget = { key: newKey(), id: null, label: '', applicationCondition: '', displayOrder: nextOrder(sheet.targets) }
  const cell: SheetCell = { key: newKey(), id: null, housingType: housingTypeKey, target: target.key, displayOrder: nextOrder(typeCells) }
  const whole = typeCells.length >= 2 ? wholeTypeQuota(sheet, housingTypeKey) : null
  return { ...sheet, targets: [...sheet.targets, target], cells: [...sheet.cells, cell],
    quotas: sheet.quotas.map(quota => quota === whole ? { ...quota, members: [...quota.members, cell.key] } : quota) }
}

/**
 * 칸의 공급대상 이름·신청조건을 바꾼다. 공급대상은 여러 주택형의 칸이 함께 가리키므로, 다른 칸이 쓰는 대상은 고치지 않고
 * 같은 이름·조건의 대상으로 옮기거나 새 대상을 만든다. 이 칸만 쓰는 대상은 그대로 고친다.
 */
export function setCellTarget(sheet: SupplySheet, cellKey: string, label: string, applicationCondition: string): SupplySheet {
  const cell = sheet.cells.find(item => item.key === cellKey)
  if (!cell) return sheet
  const siblings = new Set(sheet.cells.filter(item => item.housingType === cell.housingType && item.key !== cellKey).map(item => item.target))
  const match = sheet.targets.find(target => target.key !== cell.target && !siblings.has(target.key)
    && target.label === label && target.applicationCondition === applicationCondition)
  const alone = sheet.cells.every(item => item.key === cellKey || item.target !== cell.target)
  if (!match && alone) return { ...sheet, targets: sheet.targets.map(target => target.key === cell.target ? { ...target, label, applicationCondition } : target) }
  const target = match ?? { key: newKey(), id: null, label, applicationCondition, displayOrder: nextOrder(sheet.targets) }
  const cells = sheet.cells.map(item => item.key === cellKey ? { ...item, target: target.key } : item)
  const used = new Set(cells.map(item => item.target))
  return { ...sheet, cells, targets: [...sheet.targets, ...(match ? [] : [target])].filter(item => used.has(item.key)) }
}

/** 멤버가 정확히 이 칸들인 묶음의 수를 정한다. null이면 그 묶음을 지운다(미확인). */
export function setMembersCount(sheet: SupplySheet, members: string[], kind: QuotaKind, count: number | null): SupplySheet {
  const same = (quota: SheetQuota) => quota.kind === kind && sameMembers(quota.members, members)
  const first = sheet.quotas.find(same)
  if (count === null) return { ...sheet, quotas: sheet.quotas.filter(quota => !same(quota)) }
  if (!first) return { ...sheet, quotas: [...sheet.quotas, { key: newKey(), id: null, kind, count, members, adminModified: false }] }
  return { ...sheet, quotas: sheet.quotas.flatMap(quota => quota === first ? [{ ...quota, count }] : same(quota) ? [] : [quota]) }
}

export function setQuotaCount(sheet: SupplySheet, quotaKey: string, count: number): SupplySheet {
  return { ...sheet, quotas: sheet.quotas.map(quota => quota.key === quotaKey ? { ...quota, count } : quota) }
}

export function removeQuota(sheet: SupplySheet, quotaKey: string): SupplySheet {
  return { ...sheet, quotas: sheet.quotas.filter(quota => quota.key !== quotaKey) }
}

/** 칸 여럿이 함께 쓰는 수를 더한다. 같은 종류·같은 칸 묶음이 이미 있으면 더하지 않고 이유를 돌려준다. */
export function addSharedQuota(sheet: SupplySheet, kind: QuotaKind, members: string[], count: number): SupplySheet | string {
  if (members.length < 2) return '함께 쓰는 수는 공급대상 칸을 둘 이상 골라야 합니다.'
  if (sheet.quotas.some(quota => quota.kind === kind && sameMembers(quota.members, members))) return '같은 칸들이 함께 쓰는 수가 이미 있습니다. 목록에서 수를 고치세요.'
  return { ...sheet, quotas: [...sheet.quotas, { key: newKey(), id: null, kind, count, members, adminModified: false }] }
}

/**
 * 칸의 임대조건 금액 하나를 바꾼다. 임대조건을 다른 칸과 함께 쓰면 함께 쓰는 칸 모두에 적용된다.
 * 세 금액이 모두 비면 그 임대조건을 지운다.
 */
export function setLeaseAmount(sheet: SupplySheet, cellKey: string, field: LeaseField, value: string | null): SupplySheet {
  const lease = leaseOf(sheet, cellKey)
  if (!lease) {
    if (value === null) return sheet
    const created: SheetLeaseTerms = { key: newKey(), id: null, rentalDeposit: null, monthlyRent: null, convertedDeposit: null, [field]: value, members: [cellKey], adminModified: false }
    return { ...sheet, leaseTerms: [...sheet.leaseTerms, created] }
  }
  const next = { ...lease, [field]: value }
  const empty = leaseFields.every(key => next[key] === null)
  return { ...sheet, leaseTerms: sheet.leaseTerms.flatMap(item => item !== lease ? [item] : empty ? [] : [next]) }
}
