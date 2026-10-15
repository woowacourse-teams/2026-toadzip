import { requestManagementApi } from './api'
import { complexSections, rentals } from './fields'
import { isRecord, type JsonValue, type ManagementValues } from './managementContract'

export type ComparisonCategory = 'MYHOME_COMPLEX' | 'LH_LEASE_CATALOG'
export type ComparisonRow = { id: number; sourceKey: string; name: string; collectedAt: string | null; raw: ManagementValues }
export type ComparisonSource = { category: ComparisonCategory; sourceKey: string; automatic: boolean; evidenceNote: string; actor: string | null; linkedAt: string | null; data: ComparisonRow | null }
export type Comparison = { snapshotToken: string; current: ManagementValues; sources: ComparisonSource[] }
export type ComparisonLinkInput = { snapshotToken: string; category: ComparisonCategory; sourceKey: string; evidenceNote: string }
export type ComparisonUpdate = { snapshotToken: string; current: ManagementValues; evidenceNote: string; addressConfirmed?: boolean }
const root = (id: string) => `/api/admin/housing-complexes/${encodeURIComponent(id)}/verification/comparison`

export async function getComparison(id: string, signal: AbortSignal) {
  return parseComparison(await requestManagementApi(root(id), 'GET', undefined, signal))
}
export async function saveComparison(id: string, body: ComparisonUpdate, signal: AbortSignal) {
  return parseComparison(await requestManagementApi(root(id), 'PUT', body, signal))
}
export async function linkComparisonSource(id: string, body: ComparisonLinkInput, signal: AbortSignal) {
  return parseComparison(await requestManagementApi(`${root(id)}/sources`, 'POST', body, signal))
}
export async function unlinkComparisonSource(id: string, body: ComparisonLinkInput, signal: AbortSignal) {
  return parseComparison(await requestManagementApi(`${root(id)}/sources`, 'DELETE', body, signal))
}
function isJson(value: unknown): value is JsonValue {
  return value === null || typeof value === 'string' || typeof value === 'boolean'
    || typeof value === 'number' && Number.isFinite(value)
    || Array.isArray(value) && value.every(isJson) || isRecord(value) && Object.values(value).every(isJson)
}
function timestamp(value: unknown): value is string | null {
  return value === null || typeof value === 'string' && Number.isFinite(Date.parse(value))
}
function isRow(value: unknown): value is ComparisonRow {
  return isRecord(value) && Number.isSafeInteger(value.id) && Number(value.id) > 0 && typeof value.sourceKey === 'string'
    && typeof value.name === 'string' && timestamp(value.collectedAt) && isRecord(value.raw) && isJson(value.raw)
}
function isSource(value: unknown): value is ComparisonSource {
  return isRecord(value) && ['MYHOME_COMPLEX', 'LH_LEASE_CATALOG'].includes(String(value.category))
    && typeof value.sourceKey === 'string' && typeof value.automatic === 'boolean' && typeof value.evidenceNote === 'string'
    && (value.actor === null || typeof value.actor === 'string') && timestamp(value.linkedAt)
    && (value.data === null || isRow(value.data))
}
export function parseComparison(value: unknown): Comparison {
  if (!isRecord(value) || typeof value.snapshotToken !== 'string' || !/^[a-f0-9]{64}$/.test(value.snapshotToken)
    || !isRecord(value.current) || !isJson(value.current) || !Number.isSafeInteger(value.current.version)
    || Number(value.current.version) < 0 || !Array.isArray(value.sources) || !value.sources.every(isSource)) {
    throw new Error('원천 비교 응답이 올바르지 않습니다.')
  }
  const current = value.current
  if (!rentals.includes(String(current.rentalType)) || !['LH', 'SH', 'GH', 'ETC'].includes(String(current.agencyCode))
    || !isRecord(current.address) || !complexSections.flatMap(section => section.fields).every(field => {
      const [parent, child] = field.name.split('.')
      const candidate = child && isRecord(current[parent]) ? current[parent][child] : current[parent]
      if (candidate === null && !field.required) return true
      if (field.boolean) return typeof candidate === 'boolean'
      if (field.type === 'number') return typeof candidate === 'number' && Number.isFinite(candidate)
      return typeof candidate === 'string'
    })) throw new Error('비교할 현재 등록값이 올바르지 않습니다.')
  return { snapshotToken: value.snapshotToken, current, sources: value.sources }
}
