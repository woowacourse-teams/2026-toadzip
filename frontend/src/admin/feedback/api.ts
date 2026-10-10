import { requestManagementApi } from '../management/api'
import { isRecord } from '../management/managementContract'

export type FeedbackEntry = { id: number; content: string; createdAt: string }
export type FeedbackPage = { items: FeedbackEntry[]; page: number; hasNext: boolean; totalElements: number; totalPages: number }

function isCount(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
}

export async function listFeedback(params: URLSearchParams, signal?: AbortSignal): Promise<FeedbackPage> {
  const value = await requestManagementApi(`/api/admin/feedback?${params}`, 'GET', undefined, signal)
  if (!isRecord(value) || !Array.isArray(value.items) || !isCount(value.page) || typeof value.hasNext !== 'boolean'
    || !isCount(value.totalElements) || !isCount(value.totalPages)) {
    throw new Error('사용자 의견 목록 응답이 올바르지 않습니다.')
  }
  return { items: value.items.map(parseEntry), page: value.page, hasNext: value.hasNext,
    totalElements: value.totalElements, totalPages: value.totalPages }
}

function parseEntry(value: unknown): FeedbackEntry {
  if (!isRecord(value) || !isCount(value.id) || value.id === 0 || typeof value.content !== 'string'
    || typeof value.createdAt !== 'string' || !value.createdAt.endsWith('Z') || !Number.isFinite(Date.parse(value.createdAt))) {
    throw new Error('사용자 의견 응답이 올바르지 않습니다.')
  }
  return { id: value.id, content: value.content, createdAt: value.createdAt }
}
