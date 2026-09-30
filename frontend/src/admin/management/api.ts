export type Resource = 'complexes' | 'announcements'
export type Summary = {
  id: number; name: string; subtitle: string; provider: string; rental: string;
  deleted: boolean; modified: boolean; reviewRequired: boolean; updatedAt: string | null
}
export type JsonValue = string | number | boolean | null | { [key: string]: JsonValue } | JsonValue[]
export type Values = { [key: string]: JsonValue }
export type HousingType = { id: number; name: string; exclusiveArea: number; householdCount: number | null }
export type SupplyRow = { id: number; housingComplexId: number | null; housingComplexName: string | null;
  housingTypeId: number | null; modified: boolean; data: Values }
export type Detail = { summary: Summary; sourceIdentifier: string; data: Values;
  housingTypes: HousingType[]; announcements: Summary[]; supplyRows: SupplyRow[]; scheduleReviewed: boolean; schedules: Values[] }
export type Page = { items: Summary[]; page: number; hasNext: boolean; totalElements: number; totalPages: number }
export type Change = { id: number; action: string; actor: string; occurredAt: string; beforeValue: string; afterValue: string }

const base = import.meta.env.VITE_API_BASE_URL || (import.meta.env.DEV ? 'http://localhost:8080' : '')
export function resourcePath(resource: Resource) {
  return `/api/admin/${resource === 'complexes' ? 'housing-complexes' : 'announcements'}`
}
export class ManagementError extends Error {
  readonly status: number
  readonly fields: Record<string,string>
  constructor(message: string, status: number, fields: Record<string, string> = {}) { super(message); this.status = status; this.fields = fields }
}
export async function request(path: string, method = 'GET', body?: unknown, signal?: AbortSignal): Promise<unknown> {
  const headers: Record<string, string> = {}
  if (method !== 'GET') {
    const response = await fetch(`${base}/api/admin/auth/csrf`, { credentials: 'include', signal })
    if (!response.ok) throw new ManagementError('로그인 상태를 확인해 주세요.', response.status)
    const csrf: unknown = await response.json()
    if (!record(csrf) || typeof csrf.headerName !== 'string' || typeof csrf.token !== 'string') throw new Error('인증 응답이 올바르지 않습니다.')
    headers[csrf.headerName] = csrf.token
    if (body !== undefined) headers['Content-Type'] = 'application/json'
  }
  const response = await fetch(`${base}${path}`, {
    method, credentials: 'include', headers, signal, body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (response.status === 204) return null
  const result: unknown = await response.json().catch(() => null)
  if (!response.ok) {
    const fields: Record<string, string> = {}
    if (record(result) && Array.isArray(result.errors)) for (const error of result.errors) {
      if (record(error) && typeof error.field === 'string' && typeof error.reason === 'string') fields[error.field] = error.reason
    }
    throw new ManagementError(record(result) && typeof result.message === 'string'
      ? result.message : '요청을 처리하지 못했습니다. 다시 시도해 주세요.', response.status, fields)
  }
  if (record(result) && 'data' in result) return result.data
  return result
}
export async function list(resource: Resource, params: URLSearchParams, signal?: AbortSignal): Promise<Page> {
  const value = await request(`${resourcePath(resource)}?${params}`, 'GET', undefined, signal)
  if (!record(value) || !Array.isArray(value.items) || !value.items.every(isSummary)
    || typeof value.page !== 'number' || typeof value.hasNext !== 'boolean'
    || typeof value.totalElements !== 'number' || !Number.isSafeInteger(value.totalElements) || value.totalElements < 0
    || typeof value.totalPages !== 'number' || !Number.isSafeInteger(value.totalPages) || value.totalPages < 0) throw new Error('목록 응답이 올바르지 않습니다.')
  return { items: value.items, page: value.page, hasNext: value.hasNext, totalElements: value.totalElements, totalPages: value.totalPages }
}
export async function detail(resource: Resource, id: string, signal?: AbortSignal): Promise<Detail> {
  return parseDetail(await request(`${resourcePath(resource)}/${encodeURIComponent(id)}`, 'GET', undefined, signal))
}
export function parseDetail(value: unknown): Detail {
  if (!record(value) || !isSummary(value.summary) || typeof value.sourceIdentifier !== 'string'
    || !isValues(value.data) || typeof value.data.version !== 'number') throw new Error('상세 응답이 올바르지 않습니다.')
  const housingTypes: HousingType[] = []
  if (Array.isArray(value.housingTypes)) for (const item of value.housingTypes) {
    if (!record(item) || typeof item.id !== 'number' || typeof item.name !== 'string' || typeof item.exclusiveArea !== 'number'
      || (item.householdCount !== null && typeof item.householdCount !== 'number')) throw new Error('주택형 응답이 올바르지 않습니다.')
    housingTypes.push({ id: item.id, name: item.name, exclusiveArea: item.exclusiveArea, householdCount: item.householdCount })
  }
  const supplyRows: SupplyRow[] = []
  if (Array.isArray(value.supplyRows)) for (const item of value.supplyRows) {
    if (!record(item) || typeof item.id !== 'number' || !isValues(item.data) || typeof item.modified !== 'boolean'
      || !nullableNumber(item.housingComplexId) || !nullableNumber(item.housingTypeId)
      || (item.housingComplexName !== null && typeof item.housingComplexName !== 'string')) throw new Error('공급정보 응답이 올바르지 않습니다.')
    supplyRows.push({ id: item.id, data: item.data, modified: item.modified, housingComplexId: item.housingComplexId,
      housingTypeId: item.housingTypeId, housingComplexName: item.housingComplexName })
  }
  if (value.announcements !== undefined && (!Array.isArray(value.announcements) || !value.announcements.every(isSummary))) throw new Error('연결 공고 응답이 올바르지 않습니다.')
  if (value.schedules !== undefined && (!Array.isArray(value.schedules) || !value.schedules.every(isValues))) throw new Error('접수 일정 응답이 올바르지 않습니다.')
  return { summary: value.summary, data: value.data, sourceIdentifier: value.sourceIdentifier, housingTypes,
    announcements: Array.isArray(value.announcements) ? value.announcements.filter(isSummary) : [], supplyRows,
    scheduleReviewed: value.scheduleReviewed === true, schedules: Array.isArray(value.schedules) && value.schedules.every(isValues) ? value.schedules : [] }
}
export async function history(resource: Resource, id: string, page: number): Promise<Change[]> {
  const value = await request(`${resourcePath(resource)}/${encodeURIComponent(id)}/changes?page=${page}`)
  if (!Array.isArray(value)) throw new Error('수정 이력 응답이 올바르지 않습니다.')
  return value.map((item) => {
    if (!record(item) || typeof item.id !== 'number' || typeof item.action !== 'string' || typeof item.actor !== 'string'
      || typeof item.occurredAt !== 'string' || typeof item.beforeValue !== 'string' || typeof item.afterValue !== 'string') throw new Error('수정 이력 응답이 올바르지 않습니다.')
    return { id: item.id, action: item.action, actor: item.actor, occurredAt: item.occurredAt, beforeValue: item.beforeValue, afterValue: item.afterValue }
  })
}
export function record(value: unknown): value is Record<string, unknown> { return typeof value === 'object' && value !== null && !Array.isArray(value) }
function nullableNumber(value: unknown): value is number | null { return value === null || typeof value === 'number' }
function isSummary(value: unknown): value is Summary {
  return record(value) && typeof value.id === 'number' && typeof value.name === 'string' && typeof value.subtitle === 'string'
    && typeof value.provider === 'string' && typeof value.rental === 'string' && typeof value.deleted === 'boolean'
    && typeof value.modified === 'boolean' && typeof value.reviewRequired === 'boolean'
    && (value.updatedAt === null || typeof value.updatedAt === 'string')
}
function isJson(value: unknown): value is JsonValue {
  return value === null || typeof value === 'string' || typeof value === 'boolean' || typeof value === 'number'
    || (Array.isArray(value) && value.every(isJson)) || (record(value) && Object.values(value).every(isJson))
}
export function isValues(value: unknown): value is Values { return record(value) && Object.values(value).every(isJson) }
