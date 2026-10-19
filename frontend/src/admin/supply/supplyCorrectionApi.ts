import { requestManagementApi } from '../management/api'
import { isRecord } from '../management/managementContract'

export type CorrectionTarget = {
  id: number | null; key: string; excluded: boolean; protectedValues: boolean; displayOrder: number;
  target: string; supplyRank: string | null; householdCount: number | null; reserveCount: number | null;
  deposit: string | null; rent: string | null; convertedDeposit: string | null; condition: string | null;
}
export type CorrectionRow = {
  id: number | null; key: string; housingComplexId: number | null; housingComplexName: string | null;
  housingTypeId: number | null; newHousingType: { name: string; exclusiveArea: number; supplyArea: number | null } | null;
  groupId: number | null; sharedHouseholdCount: number | null; protectedValues: boolean; excluded: boolean;
  displayOrder: number; complexName: string; typeName: string; pnu: string; moveInMonth: string | null;
  category: 'NEW_SUPPLY' | 'RESUPPLY'; householdCount: number | null; areaOverride: boolean;
  exclusiveArea: number | null; supplyArea: number | null; targets: CorrectionTarget[]; targetCountsComparable: boolean;
}
export type CorrectionSource = { category: string; sourceKey: string; rows: { collectedAt?: string | null; raw: unknown }[] }
export type SupplyWorkspace = { snapshotToken: string; version: number; rows: CorrectionRow[]; sources: CorrectionSource[]; sourceWarnings?: string[] }
export type SupplyCorrectionInput = { operationId: string; snapshotToken: string; reason: string; sourceUrl: string; sourcePage: string | null; rows: CorrectionRow[] }
export type SupplyPreview = { confirmationToken: string; before: CorrectionRow[]; after: CorrectionRow[]; warnings: string[] }
export type SupplyApplied = { changeId: number; alreadyApplied: boolean; workspace: SupplyWorkspace }
const count = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
const nullableId = (value: unknown) => value === null || count(value) && value > 0
const nullableCount = (value: unknown) => value === null || count(value)
const nullableText = (value: unknown) => value === null || typeof value === 'string'
const area = (value: unknown) => value === null || typeof value === 'number' && Number.isFinite(value) && value >= 0
const money = (value: unknown) => value === null || typeof value === 'string' && /^\d+$/.test(value)
function invalid(): never { throw new Error('공급정보 보정 응답이 올바르지 않습니다.') }
function target(value: unknown): CorrectionTarget {
  if (!isRecord(value) || !nullableId(value.id) || typeof value.key !== 'string'
    || typeof value.excluded !== 'boolean' || typeof value.protectedValues !== 'boolean' || !count(value.displayOrder)
    || typeof value.target !== 'string' || !nullableText(value.supplyRank) || !nullableText(value.condition)
    || !nullableCount(value.householdCount) || !nullableCount(value.reserveCount)
    || !money(value.deposit) || !money(value.rent) || !money(value.convertedDeposit)) invalid()
  return value as CorrectionTarget
}
function row(value: unknown): CorrectionRow {
  if (!isRecord(value) || !nullableId(value.id) || typeof value.key !== 'string'
    || !nullableId(value.housingComplexId) || !nullableText(value.housingComplexName) || !nullableId(value.housingTypeId)
    || !nullableId(value.groupId) || !nullableCount(value.sharedHouseholdCount)
    || typeof value.protectedValues !== 'boolean' || typeof value.excluded !== 'boolean' || !count(value.displayOrder)
    || typeof value.complexName !== 'string' || typeof value.typeName !== 'string' || typeof value.pnu !== 'string'
    || !nullableText(value.moveInMonth) || !['NEW_SUPPLY', 'RESUPPLY'].includes(String(value.category))
    || !nullableCount(value.householdCount) || typeof value.areaOverride !== 'boolean'
    || !area(value.exclusiveArea) || !area(value.supplyArea) || !Array.isArray(value.targets)
    || typeof value.targetCountsComparable !== 'boolean') invalid()
  if (value.newHousingType !== null && (!isRecord(value.newHousingType) || typeof value.newHousingType.name !== 'string'
    || typeof value.newHousingType.exclusiveArea !== 'number' || !area(value.newHousingType.exclusiveArea)
    || !area(value.newHousingType.supplyArea))) invalid()
  return { ...value, targets: value.targets.map(target) } as CorrectionRow
}
export function parseSupplyWorkspace(value: unknown): SupplyWorkspace {
  if (!isRecord(value) || typeof value.snapshotToken !== 'string' || !count(value.version)
    || !Array.isArray(value.rows) || !Array.isArray(value.sources)) invalid()
  for (const source of value.sources) {
    if (!isRecord(source) || typeof source.category !== 'string' || typeof source.sourceKey !== 'string'
      || !Array.isArray(source.rows) || !source.rows.every(item => isRecord(item) && 'raw' in item)) invalid()
  }
  if (value.sourceWarnings !== undefined && (!Array.isArray(value.sourceWarnings) || !value.sourceWarnings.every(item => typeof item === 'string'))) invalid()
  return { sourceWarnings: (value.sourceWarnings ?? []) as string[], snapshotToken: value.snapshotToken, version: value.version, rows: value.rows.map(row), sources: value.sources as CorrectionSource[] }
}
const path = (id: string) => `/api/admin/announcements/${encodeURIComponent(id)}/supply-corrections`
export async function loadSupplyWorkspace(id: string, signal?: AbortSignal): Promise<SupplyWorkspace> {
  return parseSupplyWorkspace(await requestManagementApi(path(id), 'GET', undefined, signal))
}
export async function previewSupply(id: string, input: SupplyCorrectionInput): Promise<SupplyPreview> {
  return parseSupplyPreview(await requestManagementApi(`${path(id)}/preview`, 'POST', input))
}
export function parseSupplyPreview(value: unknown): SupplyPreview {
  if (!isRecord(value) || typeof value.confirmationToken !== 'string' || !Array.isArray(value.before)
    || !Array.isArray(value.after) || !Array.isArray(value.warnings) || !value.warnings.every(item => typeof item === 'string')) invalid()
  return { confirmationToken: value.confirmationToken, before: value.before.map(row), after: value.after.map(row), warnings: value.warnings }
}
export async function applySupply(id: string, correction: SupplyCorrectionInput, confirmationToken: string): Promise<SupplyApplied> {
  const value = await requestManagementApi(path(id), 'POST', { correction, confirmationToken })
  if (!isRecord(value) || !count(value.changeId) || typeof value.alreadyApplied !== 'boolean') invalid()
  return { changeId: value.changeId, alreadyApplied: value.alreadyApplied, workspace: parseSupplyWorkspace(value.workspace) }
}
export function blankTarget(order: number): CorrectionTarget {
  return { id: null, key: crypto.randomUUID(), excluded: false, protectedValues: false, displayOrder: order,
    target: '', supplyRank: null, householdCount: null, reserveCount: null, deposit: null, rent: null, convertedDeposit: null, condition: null }
}
export function blankRow(order: number): CorrectionRow {
  return { id: null, key: crypto.randomUUID(), housingComplexId: null, housingComplexName: null, housingTypeId: null,
    newHousingType: null, groupId: null, sharedHouseholdCount: null, protectedValues: false, excluded: false,
    displayOrder: order, complexName: '', typeName: '', pnu: '', moveInMonth: null, category: 'NEW_SUPPLY',
    householdCount: null, areaOverride: true, exclusiveArea: null, supplyArea: null, targets: [], targetCountsComparable: false }
}

export function parseCorrectionInput(value: unknown): SupplyCorrectionInput {
  if (!isRecord(value) || !['operationId', 'snapshotToken', 'reason', 'sourceUrl'].every(key => typeof value[key] === 'string')
    || !nullableText(value.sourcePage) || !Array.isArray(value.rows)) invalid()
  return { ...value, rows: value.rows.map(row) } as SupplyCorrectionInput
}
