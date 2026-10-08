import { requestManagementApi } from '../management/api'
import { isRecord } from '../management/managementContract'

export type IngestDomain = 'complex' | 'announcement'
export const workspaceStatuses = { WAITING: '정제 대기', FAILED: '처리 실패', INCOMPLETE: '보완 필요', READY: '정상' } as const
export type WorkspaceStatus = keyof typeof workspaceStatuses
export type WorkspaceItem = {
  identifier: string; name: string | null; productId: number | null; status: WorkspaceStatus
  detail: string; collectedAt: string | null
}
export type WorkspacePage = {
  items: WorkspaceItem[]; counts: Record<WorkspaceStatus, number>; page: number; totalElements: number; hasNext: boolean
}
export type Scalar = string | number | boolean | null
export type CorrectionRow = { sourceKey: string; original: Record<string, Scalar>; values: Record<string, Scalar> }
export type CorrectionDetail = {
  domain: IngestDomain; identifier: string; token: string; productId: number | null; managementOnly: boolean
  editableFields: string[]; rows: CorrectionRow[]; latitude: number | null; longitude: number | null
  changes: { actor: string; occurredAt: string; beforeValue: string; afterValue: string }[]
}

const root = '/api/admin/ingest/workspace'
function nullableNumber(value: unknown): value is number | null {
  return value === null || (typeof value === 'number' && Number.isFinite(value))
}
function nullableString(value: unknown): value is string | null { return value === null || typeof value === 'string' }
function count(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
}
function scalarMap(value: unknown): value is Record<string, Scalar> {
  return isRecord(value) && Object.values(value).every(item => item === null || typeof item === 'string'
    || typeof item === 'boolean' || typeof item === 'number' && Number.isFinite(item))
}
function item(value: unknown): value is WorkspaceItem {
  return isRecord(value) && typeof value.identifier === 'string' && nullableString(value.name)
    && (value.productId === null || count(value.productId) && value.productId > 0)
    && typeof value.status === 'string' && Object.hasOwn(workspaceStatuses, value.status)
    && typeof value.detail === 'string' && nullableString(value.collectedAt)
}

export async function getWorkspace(domain: IngestDomain, status: string, page: number, signal?: AbortSignal): Promise<WorkspacePage> {
  const query = new URLSearchParams({ status, page: String(page), size: '20' })
  const value = await requestManagementApi(`${root}/${domain}?${query}`, 'GET', undefined, signal)
  if (!isRecord(value) || !Array.isArray(value.items) || !value.items.every(item) || !isRecord(value.counts)
    || !Object.keys(workspaceStatuses).every(key => typeof value.counts === 'object' && value.counts !== null
      && count(Reflect.get(value.counts, key))) || !count(value.page)
    || !count(value.totalElements) || typeof value.hasNext !== 'boolean') {
    throw new Error('보완 목록 응답이 올바르지 않습니다.')
  }
  const counts = value.counts
  return { items: value.items, counts: { WAITING: Number(counts.WAITING), FAILED: Number(counts.FAILED),
    INCOMPLETE: Number(counts.INCOMPLETE), READY: Number(counts.READY) }, page: value.page,
  totalElements: value.totalElements, hasNext: value.hasNext }
}

export async function getCorrection(domain: IngestDomain, identifier: string, signal?: AbortSignal): Promise<CorrectionDetail> {
  const value = await requestManagementApi(`${root}/${domain}/${encodeURIComponent(identifier)}`, 'GET', undefined, signal)
  if (!isRecord(value) || value.domain !== domain || value.identifier !== identifier || typeof value.token !== 'string'
    || !nullableNumber(value.productId) || typeof value.managementOnly !== 'boolean'
    || !nullableNumber(value.latitude) || !nullableNumber(value.longitude) || !Array.isArray(value.editableFields)
    || !value.editableFields.every(field => typeof field === 'string') || !Array.isArray(value.rows)
    || !Array.isArray(value.changes)) throw new Error('보완 정보 응답이 올바르지 않습니다.')
  const rows: CorrectionRow[] = value.rows.map(row => {
    if (!isRecord(row) || typeof row.sourceKey !== 'string' || !scalarMap(row.original) || !scalarMap(row.values)) {
      throw new Error('원천 행 응답이 올바르지 않습니다.')
    }
    return { sourceKey: row.sourceKey, original: row.original, values: row.values }
  })
  const changes = value.changes.map(change => {
    if (!isRecord(change) || typeof change.actor !== 'string' || typeof change.occurredAt !== 'string'
      || typeof change.beforeValue !== 'string' || typeof change.afterValue !== 'string') {
      throw new Error('보완 이력 응답이 올바르지 않습니다.')
    }
    return { actor: change.actor, occurredAt: change.occurredAt, beforeValue: change.beforeValue, afterValue: change.afterValue }
  })
  return { domain, identifier, token: value.token, productId: value.productId, managementOnly: value.managementOnly,
    editableFields: value.editableFields, rows, latitude: value.latitude, longitude: value.longitude, changes }
}

export async function saveCorrection(detail: CorrectionDetail, rows: { sourceKey: string; changes: Record<string, Scalar> }[],
  latitude: number | null, longitude: number | null): Promise<number> {
  const value = await requestManagementApi(`${root}/${detail.domain}/${encodeURIComponent(detail.identifier)}`, 'PUT',
    { token: detail.token, rows, latitude, longitude })
  if (!isRecord(value) || typeof value.productId !== 'number' || !Number.isSafeInteger(value.productId) || value.productId < 1) {
    throw new Error('저장 결과 응답이 올바르지 않습니다.')
  }
  return value.productId
}
