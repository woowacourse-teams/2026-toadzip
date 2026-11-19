import { requestManagementApi } from '../management/api'
import { isRecord } from '../management/managementContract'
import { pipelineStatusLabels } from '../ingest/pipelineLabels'
import { processingStages } from '../management/listFilterLabels'

export type ComplexOverview = { total: number; recruiting: number; missingCoordinates: number; reviewRequired: number; agencies: Record<'LH' | 'SH' | 'GH', number>; asOf: string }
export type AnnouncementOverview = { statuses: Record<string, number>; attention: number; reasons: Record<string, number>; asOf: string }
export type UserOverview = { total: number; newUsers: number; previousNewUsers: number; trend: { day: string; count: number }[]; asOf: string }
export type CollectionOverview = { sources: { agency: 'LH' | 'SH' | 'GH'; lastSuccessAt: string | null; latestStartedAt: string | null; latestStatus: keyof typeof pipelineStatusLabels | null }[]; asOf: string }
export type TasksOverview = { stages: Record<string, number>; queue: { waiting: number; running: number; oldestWaitSeconds: number | null; recentFailed: number }; unresolvedFailures: number; asOf: string }
const count = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
const instant = (value: unknown): value is string => typeof value === 'string' && /(?:Z|[+-]\d{2}:\d{2})$/.test(value) && Number.isFinite(Date.parse(value))
const day = (value: unknown): value is string => typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value) && Number.isFinite(Date.parse(value)) && new Date(value).toISOString().slice(0, 10) === value
const nullableInstant = (value: unknown) => value === null || instant(value)
const counts = (value: unknown, keys: string[]) => isRecord(value) && keys.every(key => count(value[key]))
async function read<T>(path: string, valid: (value: unknown) => boolean, signal: AbortSignal): Promise<T> {
  const value = await requestManagementApi(`/api/admin/operations/${path}`, 'GET', undefined, signal)
  if (!isRecord(value) || !instant(value.asOf) || !valid(value)) throw new Error('운영 현황 응답이 올바르지 않습니다.')
  return value as T
}
export const loadComplexOverview = (signal: AbortSignal) => read<ComplexOverview>('complexes', value => isRecord(value)
  && counts(value, ['total', 'recruiting', 'missingCoordinates', 'reviewRequired']) && counts(value.agencies, ['LH', 'SH', 'GH']), signal)
export const loadAnnouncementOverview = (signal: AbortSignal) => read<AnnouncementOverview>('announcements', value => isRecord(value)
  && count(value.attention) && counts(value.statuses, ['BEFORE_APPLICATION', 'APPLYING', 'CLOSED', 'CONDITIONAL', 'CANCELLED', 'UNKNOWN'])
  && counts(value.reasons, ['SCHEDULE_MISSING', 'SUPPLY_MISSING', 'UNCONFIRMED']), signal)
export const loadTasksOverview = (signal: AbortSignal) => read<TasksOverview>('tasks', value => isRecord(value)
  && count(value.unresolvedFailures) && counts(value.stages, Object.keys(processingStages)) && isRecord(value.queue)
  && counts(value.queue, ['waiting', 'running', 'recentFailed'])
  && (value.queue.oldestWaitSeconds === null || count(value.queue.oldestWaitSeconds)), signal)
export const loadUserOverview = (days: number, signal: AbortSignal) => read<UserOverview>(`users?days=${days}`, value => isRecord(value)
  && counts(value, ['total', 'newUsers', 'previousNewUsers']) && Array.isArray(value.trend)
  && value.trend.length === days && value.trend.every(point => isRecord(point) && count(point.count) && typeof point.day === 'string'
    && day(point.day)), signal)
export const loadCollectionOverview = (signal: AbortSignal) => read<CollectionOverview>('collections', value => {
  if (!isRecord(value) || !Array.isArray(value.sources)) return false
  const sources: unknown[] = value.sources
  return sources.length === 3 && ['LH', 'SH', 'GH'].every(agency => sources.filter(source => isRecord(source) && source.agency === agency).length === 1)
    && sources.every(source => isRecord(source) && nullableInstant(source.lastSuccessAt) && nullableInstant(source.latestStartedAt)
      && (source.latestStatus === null || typeof source.latestStatus === 'string' && Object.hasOwn(pipelineStatusLabels, source.latestStatus)))
}, signal)

type PdfReview = { jobId: number; announcementId: number; name: string; reason: string; eligibilityOnly: boolean }
export async function loadPdfReviews(before: number | null, signal: AbortSignal) {
  const suffix = before === null ? '' : `?before=${before}`
  const value = await requestManagementApi(`/api/admin/operations/pdf-publication-reviews${suffix}`, 'GET', undefined, signal)
  if (!isRecord(value) || !Array.isArray(value.items) || !value.items.every(item => isRecord(item)
    && Number.isSafeInteger(item.jobId) && Number.isSafeInteger(item.announcementId) && typeof item.name === 'string'
    && typeof item.reason === 'string' && typeof item.eligibilityOnly === 'boolean')
    || !(value.nextBefore === null || Number.isSafeInteger(value.nextBefore))) throw new Error('PDF 검수 목록을 읽지 못했습니다.')
  return { items: value.items as PdfReview[], nextBefore: value.nextBefore as number | null }
}
