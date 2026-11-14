import { requestManagementApi } from '../management/api'
import { isRecord } from '../management/managementContract'
import { parseSupplySheet, toSheetInput, type SupplySheet, type SupplySheetInput } from './supplySheet'

/**
 * 공고 단지. 주택형의 complexName이 이 name이며, PDF 단지를 주소로 대응할 때 쓴다.
 * linkMethod는 연결 방법(AUTO 주소 규칙·ADMIN 관리자·MIGRATED 기존 연결 이관), 연결이 없으면 null이다. rowCount는 이 공고 단지의 주택형 수다.
 */
export type ComplexLinkMethod = 'AUTO' | 'ADMIN' | 'MIGRATED'
export type AnnouncementComplex = { id: number; name: string; roadAddress: string | null; housingComplexId: number | null; housingComplexName: string | null; linkMethod: ComplexLinkMethod | null; rowCount: number }
/** LH·GH 원천 공급 행. 저장하지 않으며 공급표 수량을 검산할 때만 참고한다. */
export type SourceReference = { complexLabel: string; typeName: string; exclusiveArea: number | string | null; householdCount: number | null }
export type SupplyWorkspace = { snapshotToken: string; version: number; sheet: SupplySheet; announcementComplexes: AnnouncementComplex[]
  applicationTypeNames: string[]; sourceReference: SourceReference[]; warnings: string[] }
/** 공급정보 편집표와 PDF 보정이 함께 보내는 요청. 공급표에서 빠진 개체는 서버가 지운다. */
export type SupplyCorrectionInput = { operationId: string; snapshotToken: string; reason: string; sourceUrl: string; sourcePage: string | null; sheet: SupplySheetInput }
/** after는 서버가 저장할 공급표다. 새 개체의 `new:*` 키는 그대로 남는다. */
export type SupplyPreview = { confirmationToken: string; before: SupplySheet; after: SupplySheet; warnings: string[] }
export type SupplyApplied = { changeId: number; alreadyApplied: boolean; workspace: SupplyWorkspace }
const count = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
const nullableId = (value: unknown) => value === null || count(value) && value > 0
const nullableText = (value: unknown) => value === null || typeof value === 'string'
const texts = (value: unknown): value is string[] => Array.isArray(value) && value.every(item => typeof item === 'string')
function invalid(): never { throw new Error('공급정보 보정 응답이 올바르지 않습니다.') }
function complex(value: unknown): AnnouncementComplex {
  if (!isRecord(value) || !count(value.id) || value.id <= 0 || typeof value.name !== 'string' || !nullableText(value.roadAddress)
    || !nullableId(value.housingComplexId) || !nullableText(value.housingComplexName)
    || ![null, 'AUTO', 'ADMIN', 'MIGRATED'].includes(value.linkMethod as string | null) || !count(value.rowCount)) invalid()
  return value as AnnouncementComplex
}
function reference(value: unknown): SourceReference {
  if (!isRecord(value) || typeof value.complexLabel !== 'string' || typeof value.typeName !== 'string'
    || !(value.exclusiveArea === null || typeof value.exclusiveArea === 'number' || typeof value.exclusiveArea === 'string')
    || !(value.householdCount === null || count(value.householdCount))) invalid()
  return value as SourceReference
}
export function parseSupplyWorkspace(value: unknown): SupplyWorkspace {
  if (!isRecord(value) || typeof value.snapshotToken !== 'string' || !count(value.version) || !Array.isArray(value.announcementComplexes)
    || !texts(value.applicationTypeNames) || !Array.isArray(value.sourceReference) || !texts(value.warnings)) invalid()
  return { snapshotToken: value.snapshotToken, version: value.version, sheet: parseSupplySheet(value.sheet),
    announcementComplexes: value.announcementComplexes.map(complex), applicationTypeNames: value.applicationTypeNames,
    sourceReference: value.sourceReference.map(reference), warnings: value.warnings }
}
const path = (id: string) => `/api/admin/announcements/${encodeURIComponent(id)}/supply-corrections`
export async function loadSupplyWorkspace(id: string, signal?: AbortSignal): Promise<SupplyWorkspace> {
  return parseSupplyWorkspace(await requestManagementApi(path(id), 'GET', undefined, signal))
}
/** 공고 단지 하나를 관리자가 정한 단지에 연결한다. 화면이 본 뒤 공급정보가 바뀌었으면 409(ADMIN_DATA_STALE)다. */
export async function linkAnnouncementComplex(id: string, announcementComplexId: number, input: { snapshotToken: string; housingComplexId: number }): Promise<SupplyWorkspace> {
  return parseSupplyWorkspace(await requestManagementApi(`${path(id)}/announcement-complexes/${announcementComplexId}/link`, 'POST', input))
}
export async function previewSupply(id: string, input: SupplyCorrectionInput): Promise<SupplyPreview> {
  return parseSupplyPreview(await requestManagementApi(`${path(id)}/preview`, 'POST', input))
}
export function parseSupplyPreview(value: unknown): SupplyPreview {
  if (!isRecord(value) || typeof value.confirmationToken !== 'string' || !texts(value.warnings)) invalid()
  return { confirmationToken: value.confirmationToken, before: parseSupplySheet(value.before), after: parseSupplySheet(value.after), warnings: value.warnings }
}
export async function applySupply(id: string, correction: SupplyCorrectionInput, confirmationToken: string): Promise<SupplyApplied> {
  const value = await requestManagementApi(path(id), 'POST', { correction, confirmationToken })
  if (!isRecord(value) || !count(value.changeId) || typeof value.alreadyApplied !== 'boolean') invalid()
  return { changeId: value.changeId, alreadyApplied: value.alreadyApplied, workspace: parseSupplyWorkspace(value.workspace) }
}

const newType = (value: unknown) => value == null || isRecord(value) && typeof value.name === 'string'
  && typeof value.exclusiveArea === 'number' && (value.supplyArea === null || typeof value.supplyArea === 'number')
/**
 * 보관해 둔 적용 요청을 복원한다. 공급표 입력만 받고, 이전 화면이 남긴 공급행(rows) 요청은 지금 서버가 받지 않으므로 거절한다.
 * 공급표 검사는 응답 검사를 그대로 쓰되 입력에 없는 보호 표시·묶음 키를 채워 넣고 다시 뺀다.
 */
export function parseCorrectionInput(value: unknown): SupplyCorrectionInput {
  if (!isRecord(value) || !['operationId', 'snapshotToken', 'reason', 'sourceUrl'].every(key => typeof value[key] === 'string')
    || !nullableText(value.sourcePage) || !isRecord(value.sheet)) invalid()
  const sheet = value.sheet
  if (![sheet.housingTypes, sheet.targets, sheet.cells, sheet.quotas, sheet.leaseTerms].every(Array.isArray)) invalid()
  // 입력의 id는 생략할 수 있다(서버가 자연 키로 맞춘다).
  const withId = (items: unknown[]) => items.map(item => isRecord(item) ? { ...item, id: item.id ?? null } : item)
  const housingTypes = withId(sheet.housingTypes as unknown[]).map(item => {
    if (!isRecord(item) || !newType(item.newHousingType)) invalid()
    return { ...item, housingComplexName: null, adminModified: false }
  })
  const filled = (items: unknown[]) => items.map((item, index) => isRecord(item) ? { ...item, key: `input:${index}`, id: null, adminModified: false } : item)
  const parsed = parseSupplySheet({ housingTypes, targets: withId(sheet.targets as unknown[]), cells: withId(sheet.cells as unknown[]), quotas: filled(sheet.quotas as unknown[]), leaseTerms: filled(sheet.leaseTerms as unknown[]) })
  return { operationId: value.operationId as string, snapshotToken: value.snapshotToken as string, reason: value.reason as string,
    sourceUrl: value.sourceUrl as string, sourcePage: value.sourcePage as string | null, sheet: toSheetInput(parsed) }
}
