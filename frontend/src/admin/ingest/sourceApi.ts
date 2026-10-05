import { requestManagementApi } from '../management/api'
import { isRecord } from '../management/managementContract'

export const sourceCategories = {
  MYHOME_COMPLEX: '마이홈 단지',
  LH_LEASE_CATALOG: 'LH 임대 단지',
  MYHOME_ANNOUNCEMENT: '마이홈 공고',
  LH_ANNOUNCEMENT_CATALOG: 'LH 공고 목록',
  LH_ANNOUNCEMENT_DETAIL: 'LH 공고 상세',
  LH_ANNOUNCEMENT_SUPPLY: 'LH 공고 공급',
} as const

export type SourceCategory = keyof typeof sourceCategories
export type SourceRow = {
  id: number
  sourceKey: string
  name: string
  sourceUrl: string
  originalUrl: string | null
  collectedAt: string | null
  sourceUpdatedAt: string | null
  raw: Record<string, unknown>
}
export type SourcePage = {
  items: SourceRow[]
  page: number
  totalElements: number
  totalPages: number
  hasNext: boolean
}

export function isSourceCategory(value: string): value is SourceCategory {
  return Object.hasOwn(sourceCategories, value)
}

export async function getSourceData(
  category: SourceCategory, keyword: string, page: number, signal?: AbortSignal,
): Promise<SourcePage> {
  const params = new URLSearchParams({ category, page: String(page), size: '20' })
  if (keyword) params.set('keyword', keyword)
  const value = await requestManagementApi(`/api/admin/ingest/sources?${params}`, 'GET', undefined, signal)
  if (!isRecord(value) || !Array.isArray(value.items) || !value.items.every(isSourceRow)
    || !count(value.page) || !count(value.totalElements) || !count(value.totalPages)
    || typeof value.hasNext !== 'boolean') {
    throw new Error('원천 데이터 목록 응답이 올바르지 않습니다.')
  }
  return { items: value.items, page: value.page, totalElements: value.totalElements,
    totalPages: value.totalPages, hasNext: value.hasNext }
}

function count(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
}
function timestamp(value: unknown): value is string | null {
  return value === null || (typeof value === 'string' && Number.isFinite(Date.parse(value)))
}
function isSourceRow(value: unknown): value is SourceRow {
  return isRecord(value) && count(value.id) && value.id > 0
    && typeof value.sourceKey === 'string' && typeof value.name === 'string'
    && typeof value.sourceUrl === 'string'
    && (value.originalUrl === null || typeof value.originalUrl === 'string')
    && timestamp(value.collectedAt) && timestamp(value.sourceUpdatedAt) && isRecord(value.raw)
}
