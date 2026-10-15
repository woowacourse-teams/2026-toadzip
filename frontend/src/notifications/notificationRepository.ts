import { getApiBaseUrl } from '../api/apiBaseUrl'
import { record } from '../privacy/api'
export interface NotificationItem {
  readonly id: string; readonly type: 'ANNOUNCEMENT_CREATED' | 'SCHEDULE_REMINDER'; readonly announcementId: string
  readonly title: string; readonly createdAt: string; readonly readAt: string | null; readonly targetAvailable: boolean
  readonly content?: NotificationContent | null
}
export interface NotificationSchedule {
  readonly kind: 'APPLICATION' | 'DOCUMENT_SUBMISSION' | 'WINNER_ANNOUNCEMENT' | 'CONTRACT'
  readonly name: string; readonly startDate: string; readonly endDate: string
  readonly startTime: string | null; readonly endTime: string | null
  readonly scope: string | null; readonly condition: string | null
  readonly sourceUrl: string | null; readonly sourcePage: number | null
  readonly phase: 'START' | 'END' | 'START_END'
}
export interface NotificationContent {
  readonly type: string; readonly targetType: string; readonly targetId: string; readonly targetName: string
  readonly message: string; readonly schedule: NotificationSchedule | null
}
export interface NotificationPageResult { readonly items: readonly NotificationItem[]; readonly nextCursor: string | null; readonly readThrough: string; readonly totalCount: number }
export type NotificationDeleteSelection = { readonly ids: readonly string[] } | { readonly deleteThrough: string; readonly excludedIds: readonly string[] }
export interface NotificationRepository {
  delete(selection: NotificationDeleteSelection, signal: AbortSignal): Promise<void>
  count(signal: AbortSignal): Promise<number>
  list(cursor: string | null, signal: AbortSignal): Promise<NotificationPageResult>
  read(id: string, signal: AbortSignal): Promise<void>
  readAll(readThrough: string, signal: AbortSignal): Promise<void>
}
export class NotificationApiError extends Error { readonly status: number; constructor(status: number) { super('알림 요청에 실패했어요.'); this.status = status } }
function id(value: unknown, allowZero = false): value is string {
  return typeof value === 'string' && /^(0|[1-9][0-9]{0,18})$/.test(value)
    && BigInt(value) <= 9223372036854775807n && (allowZero || value !== '0')
}
function timestamp(value: unknown): value is string { return typeof value === 'string' && Number.isFinite(Date.parse(value)) }
function parsePage(value: unknown): NotificationPageResult {
  if (!record(value) || !Array.isArray(value.items) || !id(value.readThrough, true)
    || !Number.isSafeInteger(value.totalCount) || Number(value.totalCount) < 0
    || !(value.nextCursor === null || typeof value.nextCursor === 'string')) throw new Error('알림 응답을 확인하지 못했어요.')
  const items = value.items.map((item: unknown): NotificationItem => {
    if (!record(item) || !id(item.id) || !id(item.announcementId) || !['ANNOUNCEMENT_CREATED', 'SCHEDULE_REMINDER'].includes(String(item.type))
      || typeof item.title !== 'string' || !timestamp(item.createdAt) || !(item.readAt === null || timestamp(item.readAt))
      || typeof item.targetAvailable !== 'boolean') throw new Error('알림 응답을 확인하지 못했어요.')
    const content = parseContent(item.content)
    if (item.type === 'SCHEDULE_REMINDER' && !content?.schedule) throw new Error('알림 응답을 확인하지 못했어요.')
    return { ...item, ...(item.content === undefined ? {} : { content }) } as unknown as NotificationItem
  })
  return { items, nextCursor: value.nextCursor, readThrough: value.readThrough, totalCount: Number(value.totalCount) }
}
function calendarDate(value: unknown): value is string {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const date = new Date(`${value}T00:00:00Z`)
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value
}
function nullableText(value: unknown) { return value === null || typeof value === 'string' }
function clockTime(value: unknown) { return value === null || typeof value === 'string' && /^(?:[01]\d|2[0-3]):[0-5]\d(?::[0-5]\d(?:\.\d+)?)?$/.test(value) }
function sourceUrl(value: unknown): string | null {
  if (typeof value !== 'string') return null
  try {
    const url = new URL(value)
    return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password ? url.href : null
  } catch { return null }
}
function parseContent(value: unknown): NotificationContent | null {
  if (value === null || value === undefined) return null
  if (!record(value) || !['type', 'targetType', 'targetId', 'targetName', 'message'].every(key => typeof value[key] === 'string')) throw new Error('알림 응답을 확인하지 못했어요.')
  if (value.schedule === null) return value as unknown as NotificationContent
  const schedule = value.schedule
  if (!record(schedule) || !['APPLICATION', 'DOCUMENT_SUBMISSION', 'WINNER_ANNOUNCEMENT', 'CONTRACT'].includes(String(schedule.kind))
    || typeof schedule.name !== 'string' || !calendarDate(schedule.startDate) || !calendarDate(schedule.endDate) || schedule.startDate > schedule.endDate
    || !clockTime(schedule.startTime) || !clockTime(schedule.endTime) || !nullableText(schedule.scope) || !nullableText(schedule.condition)
    || !(schedule.sourcePage === null || Number.isSafeInteger(schedule.sourcePage) && Number(schedule.sourcePage) > 0)
    || !['START', 'END', 'START_END'].includes(String(schedule.phase))) throw new Error('알림 응답을 확인하지 못했어요.')
  return { ...value, schedule: { ...schedule, sourceUrl: sourceUrl(schedule.sourceUrl) } } as unknown as NotificationContent
}
export function createNotificationRepository(fetcher: typeof fetch = (...args) => globalThis.fetch(...args)): NotificationRepository {
  const base = `${getApiBaseUrl()}/api/v1/notifications`
  async function request(url: string, signal: AbortSignal, body?: object) {
    signal.throwIfAborted()
    const bounded = AbortSignal.any([signal, AbortSignal.timeout(15_000)])
    let headers: Record<string, string> | undefined
    if (body) {
      const csrfResponse = await fetcher(`${getApiBaseUrl()}/api/auth/csrf`, { credentials: 'include', cache: 'no-store', signal: bounded })
      if (!csrfResponse.ok) throw new NotificationApiError(csrfResponse.status)
      const csrf: unknown = await csrfResponse.json()
      if (!record(csrf) || csrf.headerName !== 'X-XSRF-TOKEN' || typeof csrf.token !== 'string' || !csrf.token) throw new Error('알림 요청을 준비하지 못했어요.')
      headers = { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }
    }
    signal.throwIfAborted()
    const response = await fetcher(url, { method: body ? 'POST' : 'GET', credentials: 'include', cache: 'no-store', headers,
      body: body ? JSON.stringify(body) : undefined, signal: bounded })
    if (!response.ok) throw new NotificationApiError(response.status)
    return response
  }
  return {
    async delete(selection, signal) {
      const ids = 'ids' in selection ? selection.ids : selection.excludedIds
      if (ids.length > 1000 || ids.some(value => !id(value))
        || ('ids' in selection ? ids.length === 0 : !id(selection.deleteThrough))) throw new Error('삭제할 알림 범위를 확인해 주세요.')
      await request(`${base}/delete`, signal, selection)
    },
    async count(signal) {
      const value: unknown = await (await request(`${base}/unread-count`, signal)).json()
      if (!record(value) || !Number.isSafeInteger(value.unreadCount) || Number(value.unreadCount) < 0) throw new Error('알림 개수 응답을 확인하지 못했어요.')
      return Number(value.unreadCount)
    },
    async list(cursor, signal) { return parsePage(await (await request(`${base}?size=20${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`, signal)).json()) },
    async read(notificationId, signal) {
      if (!id(notificationId)) throw new Error('알림 ID가 올바르지 않아요.')
      await request(`${base}/${notificationId}/read`, signal, {})
    },
    async readAll(readThrough, signal) {
      if (!id(readThrough, true)) throw new Error('알림 범위가 올바르지 않아요.')
      await request(`${base}/read-all`, signal, { readThrough })
    },
  }
}
export const notificationRepository = createNotificationRepository()
