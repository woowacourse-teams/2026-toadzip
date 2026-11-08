import { requestManagementApi } from '../management/api'
import { isRecord, parseManagementDetail, type ManagementDetailData } from '../management/managementContract'
import { parseSupplyPreview, parseSupplyWorkspace, type SupplyCorrectionInput, type SupplyPreview, type SupplyWorkspace } from '../supply/supplyCorrectionApi'

export type JsonObject = Record<string, unknown>
export type ExtractedAnnouncement = { schemaVersion: string; source: JsonObject; announcement: JsonObject; receptionPlaces: JsonObject[]; supplyRows: JsonObject[]; schedules: JsonObject[]; attachments: JsonObject[]; unresolvedFields: JsonObject[] }
export type JsonWorkspace = { snapshotToken: string; detail: ManagementDetailData; supply: SupplyWorkspace; events: { id: number; data: JsonObject; complexName: string | null }[] }
export type Selection = { inputIndex: number; existingId: number | null }
/**
 * 공고문에서 읽은 공급행 주택형의 값. 저장하면 그 행이 고른 주택형에 반영한다. 연결 주소가 없으면 대표 주택형이고,
 * linkedHousingTypeId(기존)·linkedNewHousingTypeArea(새 주택형의 전용면적)는 그 행이 함께 모집하는 주택형 하나를 가리킨다. 같은 면적의 새 주택형이
 * 여럿이면 linkedNewHousingTypeName(새 주택형 이름)으로 그중 하나를 가리킨다.
 */
export type HousingTypeValue = { rowKey: string; name: string | null; supplyArea: number | null; householdCount: number | null; basicDeposit?: number | null; basicMonthlyRent?: number | null
  linkedHousingTypeId?: number; linkedNewHousingTypeArea?: number; linkedNewHousingTypeName?: string }
export type JsonUpdateInput = { snapshotToken: string; document: ExtractedAnnouncement; fields: string[]; schedules: Selection[]; attachments: Selection[]; correction: SupplyCorrectionInput; housingTypeValues?: HousingTypeValue[]; publish?: boolean }
export type TypeValues = { name: string; supplyArea: number | null; householdCount: number | null; basicDeposit?: number | null; basicMonthlyRent?: number | null }
/** housingTypeId가 null이면 이 저장에서 새로 만드는 주택형이다. */
export type HousingTypeChange = { rowKey: string; housingTypeId: number | null; typeName: string; before: TypeValues | null; after: TypeValues }
export type JsonChange = { field: string; before: unknown; after: unknown }
export type ChildChange = { inputIndex: number; existingId: number | null; before: JsonObject | null; after: JsonObject }
export type JsonPreview = { confirmationToken: string; fields: JsonChange[]; supply: SupplyPreview | null; schedules: ChildChange[]; attachments: ChildChange[]; housingTypes: HousingTypeChange[]; housingTypeWarnings: string[]; warnings: string[]; publishes?: boolean }
const path = (id: string) => `/api/admin/announcements/${encodeURIComponent(id)}/json-corrections`
function objectArray(value: unknown): value is JsonObject[] { return Array.isArray(value) && value.every(isRecord) }
function invalid(): never { throw new Error('공고 보정 응답이 올바르지 않습니다.') }
function workspace(value: unknown): JsonWorkspace {
  if (!isRecord(value) || typeof value.snapshotToken !== 'string' || !objectArray(value.events)
    || !value.events.every(item => Number.isSafeInteger(item.id) && Number(item.id) > 0 && isRecord(item.data)
      && (item.complexName === null || typeof item.complexName === 'string'))) invalid()
  return { snapshotToken: value.snapshotToken, detail: parseManagementDetail(value.detail), supply: parseSupplyWorkspace(value.supply), events: value.events as JsonWorkspace['events'] }
}
export async function loadJsonWorkspace(id: string, signal?: AbortSignal) { return workspace(await requestManagementApi(path(id), 'GET', undefined, signal)) }
export async function previewJsonUpdate(id: string, input: JsonUpdateInput): Promise<JsonPreview> {
  const result = await requestManagementApi(`${path(id)}/preview`, 'POST', input)
  if (!isRecord(result) || typeof result.confirmationToken !== 'string' || !objectArray(result.fields)
    || !result.fields.every(item => typeof item.field === 'string' && 'before' in item && 'after' in item)
    || !Array.isArray(result.warnings) || !result.warnings.every(item => typeof item === 'string')) invalid()
  for (const key of ['schedules', 'attachments']) {
    if (!objectArray(result[key]) || !result[key].every(item => Number.isSafeInteger(item.inputIndex)
      && Number(item.inputIndex) >= 0 && (item.existingId === null || Number.isSafeInteger(item.existingId) && Number(item.existingId) > 0)
      && (item.before === null || isRecord(item.before)) && isRecord(item.after))) invalid()
  }
  // 주택형 갱신을 모르는 이전 서버는 두 목록을 주지 않는다.
  const housingTypes = result.housingTypes ?? [], housingTypeWarnings = result.housingTypeWarnings ?? []
  if (!objectArray(housingTypes) || !housingTypes.every(item => typeof item.rowKey === 'string' && typeof item.typeName === 'string'
      && (item.housingTypeId === null || Number.isSafeInteger(item.housingTypeId)) && (item.before === null || isRecord(item.before)) && isRecord(item.after))
    || !Array.isArray(housingTypeWarnings) || !housingTypeWarnings.every(item => typeof item === 'string')) invalid()
  const supply = result.supply === null ? null : parseSupplyPreview(result.supply)
  return { ...result, supply, housingTypes, housingTypeWarnings } as JsonPreview
}
export async function applyJsonUpdate(id: string, request: JsonUpdateInput, confirmationToken: string) {
  return workspace(await requestManagementApi(path(id), 'POST', { request, confirmationToken }))
}
