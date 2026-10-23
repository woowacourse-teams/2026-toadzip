import { requestManagementApi } from '../management/api'
import { isRecord, parseManagementDetail, type ManagementDetailData } from '../management/managementContract'
import { parseSupplyPreview, parseSupplyWorkspace, type SupplyCorrectionInput, type SupplyPreview, type SupplyWorkspace } from '../supply/supplyCorrectionApi'

export type JsonObject = Record<string, unknown>
export type ExtractedAnnouncement = { schemaVersion: string; source: JsonObject; announcement: JsonObject; receptionPlace: JsonObject | null; supplyRows: JsonObject[]; schedules: JsonObject[]; attachments: JsonObject[]; unresolvedFields: JsonObject[] }
export type JsonWorkspace = { snapshotToken: string; detail: ManagementDetailData; supply: SupplyWorkspace; events: { id: number; data: JsonObject; complexName: string | null }[] }
export type Selection = { inputIndex: number; existingId: number | null }
export type JsonUpdateInput = { snapshotToken: string; document: ExtractedAnnouncement; fields: string[]; schedules: Selection[]; attachments: Selection[]; correction: SupplyCorrectionInput }
export type JsonChange = { field: string; before: unknown; after: unknown }
export type ChildChange = { inputIndex: number; existingId: number | null; before: JsonObject | null; after: JsonObject }
export type JsonPreview = { confirmationToken: string; fields: JsonChange[]; supply: SupplyPreview | null; schedules: ChildChange[]; attachments: ChildChange[]; warnings: string[] }
const path = (id: string) => `/api/admin/announcements/${encodeURIComponent(id)}/json-corrections`
function objectArray(value: unknown): value is JsonObject[] { return Array.isArray(value) && value.every(isRecord) }
function invalid(): never { throw new Error('공고 JSON 갱신 응답이 올바르지 않습니다.') }
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
  return { ...result, supply: result.supply === null ? null : parseSupplyPreview(result.supply) } as JsonPreview
}
export async function applyJsonUpdate(id: string, request: JsonUpdateInput, confirmationToken: string) {
  return workspace(await requestManagementApi(path(id), 'POST', { request, confirmationToken }))
}
