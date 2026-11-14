import { isRecord } from '../management/managementContract'

/**
 * 공고 공급표(관리자 계약). 주택형(신청형) × 공급대상 칸이 있고, 수량 묶음(당첨·예비)과 임대조건은 칸을 멤버로 가진다.
 * 공고문에서 한 칸이 여러 주택형·대상에 걸쳐 병합되어 있으면 묶음 하나에 멤버가 여럿이다(49A·49B·49C 함께 모집 70).
 * 저장된 개체의 key는 `row:{id}`·`target:{id}`·`cell:{id}`이고, 화면이 새로 만든 개체는 `new:` 접두를 쓴다.
 */
export type SheetHousingType = {
  key: string; id: number | null; announcementComplexId: number | null; complexName: string
  housingComplexId: number | null; housingComplexName: string | null; housingTypeId: number | null
  /** 연결할 단지에 이 저장에서 새로 만들 주택타입. housingTypeId와 함께 두지 않는다. */
  newHousingType?: { name: string; exclusiveArea: number; supplyArea: number | null } | null
  name: string; exclusiveArea: number | null; supplyArea: number | null; expectedMoveInMonth: string | null
  supplyPnu: string | null; category: 'NEW_SUPPLY' | 'RESUPPLY' | null; displayOrder: number; adminModified: boolean
}
export type SheetTarget = { key: string; id: number | null; label: string; applicationCondition: string; displayOrder: number }
export type SheetCell = { key: string; id: number | null; housingType: string; target: string; displayOrder: number }
export type QuotaKind = 'WINNER' | 'RESERVE'
export type SheetQuota = { key: string; id: number | null; kind: QuotaKind; count: number; members: string[]; adminModified: boolean }
/** 금액은 원 단위 정수 문자열이다. */
export type SheetLeaseTerms = { key: string; id: number | null; rentalDeposit: string | null; monthlyRent: string | null
  convertedDeposit: string | null; members: string[]; adminModified: boolean }
export type SupplySheet = { housingTypes: SheetHousingType[]; targets: SheetTarget[]; cells: SheetCell[]
  quotas: SheetQuota[]; leaseTerms: SheetLeaseTerms[] }

/** 서버로 보내는 공급표. 보호 표시(adminModified)는 서버가 정하고, 묶음·임대조건은 내용(종류와 멤버)으로 맞추므로 키·ID를 보내지 않는다. */
export type SupplySheetInput = {
  housingTypes: Omit<SheetHousingType, 'adminModified' | 'housingComplexName'>[]
  targets: SheetTarget[]
  cells: SheetCell[]
  quotas: { kind: QuotaKind; count: number; members: string[] }[]
  leaseTerms: { rentalDeposit: string | null; monthlyRent: string | null; convertedDeposit: string | null; members: string[] }[]
}

const count = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
const nullableId = (value: unknown) => value === null || count(value) && value > 0
const nullableText = (value: unknown) => value === null || typeof value === 'string'
const area = (value: unknown) => value === null || typeof value === 'number' && Number.isFinite(value) && value >= 0
const money = (value: unknown) => value === null || typeof value === 'string' && /^\d+$/.test(value)
const keys = (value: unknown): value is string[] => Array.isArray(value) && value.length > 0 && value.every(item => typeof item === 'string')
function invalid(): never { throw new Error('공급표 응답이 올바르지 않습니다.') }

/** 서버 응답의 공급표를 확인한다. 칸·묶음이 가리키는 키가 없으면 거절한다. */
export function parseSupplySheet(value: unknown): SupplySheet {
  if (!isRecord(value) || ![value.housingTypes, value.targets, value.cells, value.quotas, value.leaseTerms].every(Array.isArray)) invalid()
  const housingTypes = (value.housingTypes as unknown[]).map(item => {
    if (!isRecord(item) || typeof item.key !== 'string' || !nullableId(item.id) || !nullableId(item.announcementComplexId)
      || typeof item.complexName !== 'string' || !nullableId(item.housingComplexId) || !nullableText(item.housingComplexName)
      || !nullableId(item.housingTypeId) || typeof item.name !== 'string' || !area(item.exclusiveArea) || !area(item.supplyArea)
      || !nullableText(item.expectedMoveInMonth) || !nullableText(item.supplyPnu)
      || ![null, 'NEW_SUPPLY', 'RESUPPLY'].includes(item.category as string | null) || !count(item.displayOrder)
      || typeof item.adminModified !== 'boolean') invalid()
    return item as SheetHousingType
  })
  const targets = (value.targets as unknown[]).map(item => {
    if (!isRecord(item) || typeof item.key !== 'string' || !nullableId(item.id) || typeof item.label !== 'string'
      || typeof item.applicationCondition !== 'string' || !count(item.displayOrder)) invalid()
    return item as SheetTarget
  })
  const typeKeys = new Set(housingTypes.map(type => type.key))
  const targetKeys = new Set(targets.map(target => target.key))
  const cells = (value.cells as unknown[]).map(item => {
    if (!isRecord(item) || typeof item.key !== 'string' || !nullableId(item.id) || !typeKeys.has(item.housingType as string)
      || !targetKeys.has(item.target as string) || !count(item.displayOrder)) invalid()
    return item as SheetCell
  })
  const cellKeys = new Set(cells.map(cell => cell.key))
  const members = (value: unknown) => keys(value) && value.every(key => cellKeys.has(key))
  const quotas = (value.quotas as unknown[]).map(item => {
    if (!isRecord(item) || typeof item.key !== 'string' || !nullableId(item.id) || !['WINNER', 'RESERVE'].includes(item.kind as string)
      || !count(item.count) || !members(item.members) || typeof item.adminModified !== 'boolean') invalid()
    return item as SheetQuota
  })
  const leaseTerms = (value.leaseTerms as unknown[]).map(item => {
    if (!isRecord(item) || typeof item.key !== 'string' || !nullableId(item.id) || !money(item.rentalDeposit)
      || !money(item.monthlyRent) || !money(item.convertedDeposit) || !members(item.members) || typeof item.adminModified !== 'boolean') invalid()
    return item as SheetLeaseTerms
  })
  return { housingTypes, targets, cells, quotas, leaseTerms }
}

/** 주택형의 칸을 공고문 순서로. */
export function cellsOf(sheet: SupplySheet, housingTypeKey: string): SheetCell[] {
  return sheet.cells.filter(cell => cell.housingType === housingTypeKey).sort((a, b) => a.displayOrder - b.displayOrder)
}

function typesOf(sheet: SupplySheet, quota: { members: string[] }): Set<string> {
  const typeOf = new Map(sheet.cells.map(cell => [cell.key, cell.housingType]))
  return new Set(quota.members.map(member => typeOf.get(member) ?? ''))
}

/** 이 칸만 멤버인 묶음의 수(그 대상만의 수). 없으면 null이다. */
export function ownCount(sheet: SupplySheet, cellKey: string, kind: QuotaKind): number | null {
  const own = sheet.quotas.filter(quota => quota.kind === kind && quota.members.length === 1 && quota.members[0] === cellKey)
  return own.length ? own.reduce((sum, quota) => sum + quota.count, 0) : null
}

/** 주택형 모집수: 멤버가 모두 이 주택형의 칸인 당첨 묶음의 합. 없으면 null이다. */
export function housingTypeCount(sheet: SupplySheet, housingTypeKey: string): number | null {
  const own = sheet.quotas.filter(quota => {
    const types = typesOf(sheet, quota)
    return quota.kind === 'WINNER' && types.size === 1 && types.has(housingTypeKey)
  })
  return own.length ? own.reduce((sum, quota) => sum + quota.count, 0) : null
}

/**
 * 칸 여럿이 함께 쓰는 묶음 중 이 주택형의 칸이 든 것. 한 주택형의 모든 칸이 함께 쓰는 당첨 수는 주택형 모집수로 보이므로 뺀다.
 * acrossTypes는 다른 주택형과 함께 쓰는 수(병합된 수량 칸)인지다.
 */
export function sharedQuotas(sheet: SupplySheet, housingTypeKey: string): { quota: SheetQuota; acrossTypes: boolean }[] {
  const typeCells = cellsOf(sheet, housingTypeKey).map(cell => cell.key)
  return sheet.quotas.flatMap(quota => {
    if (quota.members.length < 2 || !quota.members.some(member => typeCells.includes(member))) return []
    const acrossTypes = typesOf(sheet, quota).size > 1
    const wholeType = !acrossTypes && quota.members.length === typeCells.length
    return quota.kind === 'WINNER' && wholeType ? [] : [{ quota, acrossTypes }]
  })
}

/** 칸의 임대조건. 칸 하나는 임대조건을 하나만 가진다. */
export function leaseOf(sheet: SupplySheet, cellKey: string): SheetLeaseTerms | null {
  return sheet.leaseTerms.find(lease => lease.members.includes(cellKey)) ?? null
}

/** 공고 모집수 합계: 당첨 묶음을 한 번씩 더한다. */
export function totalCount(sheet: SupplySheet, kind: QuotaKind = 'WINNER'): number | null {
  const quotas = sheet.quotas.filter(quota => quota.kind === kind)
  return quotas.length ? quotas.reduce((sum, quota) => sum + quota.count, 0) : null
}

/** 보이는 공급표를 보낼 입력으로 바꾼다. 묶음·임대조건의 키·ID와 보호 표시는 빼고 단지 이름 표시도 뺀다. */
export function toSheetInput(sheet: SupplySheet): SupplySheetInput {
  return {
    housingTypes: sheet.housingTypes.map(({ adminModified: _modified, housingComplexName: _name, ...type }) => type),
    targets: sheet.targets,
    cells: sheet.cells,
    quotas: sheet.quotas.map(({ kind, count, members }) => ({ kind, count, members })),
    leaseTerms: sheet.leaseTerms.map(({ rentalDeposit, monthlyRent, convertedDeposit, members }) =>
      ({ rentalDeposit, monthlyRent, convertedDeposit, members })),
  }
}
