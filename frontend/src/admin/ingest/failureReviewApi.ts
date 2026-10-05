import { requestManagementApi } from '../management/api'
import { failureCategories, type FailureCategory, type IngestFailure } from './api'

export type FailureDomain = 'complex' | 'announcement'
export type FailureReviewStatus = 'PENDING' | 'RESOLVED' | 'ALL'
export type FailureProduct = {
  id: number; name: string; resourceType: 'complexes' | 'announcements'; deleted: boolean
  hasCoordinates: boolean | null; housingTypeCount: number | null; linkedComplexCount: number | null
  supplyRowCount: number | null; applicationScheduleCount: number | null; attachmentCount: number | null
  applicationStartDate: string | null; applicationEndDate: string | null; applicationScheduleReviewed: boolean | null
  publicDetailAvailable: boolean; publicListEligible: boolean; listExclusionReasons: string[]
}
export type FailureReview = IngestFailure & {
  id: number; category: FailureCategory; recurrenceCount: number; lastResolvedAt: string | null
  productLinkStatus: 'EXISTS' | 'NOT_FOUND' | 'UNKNOWN'; product: FailureProduct | null
}
export type FailureReviewPageData = {
  items: FailureReview[]; page: number; totalElements: number; totalPages: number; hasNext: boolean
}

export async function getFailureReviews(
  domain: FailureDomain, category: FailureCategory | 'all', status: FailureReviewStatus,
  page: number, signal?: AbortSignal,
): Promise<FailureReviewPageData> {
  const params = new URLSearchParams({ domain, category, status, page: String(page), size: '20' })
  const value = await requestManagementApi(`/api/admin/ingest/failure-reviews?${params}`, 'GET', undefined, signal)
  if (!record(value) || !Array.isArray(value.items) || !count(value.page) || !count(value.totalElements)
    || !count(value.totalPages) || typeof value.hasNext !== 'boolean') throw invalid()
  return { items: value.items.map(parseReview), page: value.page, totalElements: value.totalElements,
    totalPages: value.totalPages, hasNext: value.hasNext }
}

function parseReview(value: unknown): FailureReview {
  if (!record(value) || !count(value.id) || value.id === 0 || typeof value.category !== 'string'
    || !Object.hasOwn(failureCategories, value.category) || typeof value.sourceKey !== 'string'
    || typeof value.reason !== 'string' || typeof value.detail !== 'string'
    || !['PENDING', 'RESOLVED', 'SKIPPED'].includes(String(value.status))
    || typeof value.lastOccurredAt !== 'string' || !count(value.occurrenceCount) || !count(value.recurrenceCount)
    || !nullableText(value.lastResolvedAt) || !nullableText(value.lastExecutionId) || !nullableText(value.source)
    || !nullableText(value.sourceComplexIdentifier) || !nullableText(value.sourceAnnouncementIdentifier)
    || !nullableText(value.targetName) || !record(value.metadata)
    || !['EXISTS', 'NOT_FOUND', 'UNKNOWN'].includes(String(value.productLinkStatus))) throw invalid()
  const product = value.product === null ? null : parseProduct(value.product)
  if ((value.productLinkStatus === 'EXISTS') !== (product !== null)) throw invalid()
  const { product: _product, metadata, ...failure } = value
  const raw = { ...failure, ...metadata, requestDescription: value.sourceKey }
  const serial = typeof value.metadata.sourceHouseSerialNumber === 'number'
    ? ` · 공급행 ${value.metadata.sourceHouseSerialNumber}` : ''
  const name = product?.name || value.targetName || value.sourceComplexIdentifier
    || value.sourceAnnouncementIdentifier || value.sourceKey
  return {
    id: value.id, category: value.category as FailureCategory, target: name + serial, sourceKey: value.sourceKey,
    reason: value.reason, detail: value.detail, status: String(value.status), occurredAt: value.lastOccurredAt,
    occurrenceCount: value.occurrenceCount, recurrenceCount: value.recurrenceCount,
    lastResolvedAt: value.lastResolvedAt, executionId: value.lastExecutionId, source: value.source,
    raw, productLinkStatus: value.productLinkStatus as FailureReview['productLinkStatus'], product,
  }
}

function parseProduct(value: unknown): FailureProduct {
  if (!record(value) || !count(value.id) || value.id === 0 || typeof value.name !== 'string'
    || !['complexes', 'announcements'].includes(String(value.resourceType)) || typeof value.deleted !== 'boolean'
    || !nullableBoolean(value.hasCoordinates) || !nullableBoolean(value.applicationScheduleReviewed)
    || !nullableCount(value.housingTypeCount) || !nullableCount(value.linkedComplexCount)
    || !nullableCount(value.supplyRowCount) || !nullableCount(value.applicationScheduleCount)
    || !nullableCount(value.attachmentCount) || !nullableText(value.applicationStartDate)
    || !nullableText(value.applicationEndDate) || typeof value.publicDetailAvailable !== 'boolean'
    || typeof value.publicListEligible !== 'boolean' || !Array.isArray(value.listExclusionReasons)
    || !value.listExclusionReasons.every((reason: unknown) => typeof reason === 'string')) throw invalid()
  return value as FailureProduct
}

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
function count(value: unknown): value is number { return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 }
function nullableCount(value: unknown): value is number | null { return value === null || count(value) }
function nullableText(value: unknown): value is string | null { return value === null || typeof value === 'string' }
function nullableBoolean(value: unknown): value is boolean | null { return value === null || typeof value === 'boolean' }
function invalid() { return new Error('오류 검토 응답 형식이 올바르지 않습니다.') }
