import { requestManagementApi } from '../management/api'
import { isRecord } from '../management/managementContract'

export const feedbackStates = { OPEN: '접수', IN_PROGRESS: '처리 중', ON_HOLD: '보류', DONE: '처리 완료' } as const
export type FeedbackState = keyof typeof feedbackStates
export type FeedbackEntry = { id: number; content: string; createdAt: string; version: number; status: FeedbackState;
  assignee: string; note: string; relatedResource: '' | 'COMPLEX' | 'ANNOUNCEMENT'; relatedId: number | null;
  actor: string | null; processedAt: string | null }
export type FeedbackInput = { version: number; status: FeedbackState; assignee: string; note: string;
  relatedResource: '' | 'COMPLEX' | 'ANNOUNCEMENT'; relatedId: number | null }

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
  if (!isCount(value.version) || typeof value.status !== 'string' || !Object.hasOwn(feedbackStates, value.status)
    || typeof value.assignee !== 'string' || typeof value.note !== 'string'
    || !['', 'COMPLEX', 'ANNOUNCEMENT'].includes(String(value.relatedResource))
    || !(value.relatedId === null || isCount(value.relatedId) && value.relatedId > 0)
    || !(value.actor === null || typeof value.actor === 'string')
    || !(value.processedAt === null || typeof value.processedAt === 'string' && Number.isFinite(Date.parse(value.processedAt)))) {
    throw new Error('의견 처리 응답이 올바르지 않습니다.')
  }
  return value as FeedbackEntry
}

export async function saveFeedback(id: number, input: FeedbackInput): Promise<FeedbackEntry> {
  return parseEntry(await requestManagementApi(`/api/admin/feedback/${id}`, 'PUT', input))
}
export async function getFeedback(id: number): Promise<FeedbackEntry> {
  return parseEntry(await requestManagementApi(`/api/admin/feedback/${id}`, 'GET'))
}
