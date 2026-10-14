import { getApiBaseUrl } from '../api/apiBaseUrl'
import { record } from '../privacy/api'
export interface NotificationItem {
  readonly id: string; readonly type: 'ANNOUNCEMENT_CREATED'; readonly announcementId: string
  readonly title: string; readonly createdAt: string; readonly readAt: string | null; readonly targetAvailable: boolean
}
export interface NotificationPageResult { readonly items: readonly NotificationItem[]; readonly nextCursor: string | null; readonly readThrough: string }
export interface NotificationRepository {
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
    || !(value.nextCursor === null || typeof value.nextCursor === 'string')) throw new Error('알림 응답을 확인하지 못했어요.')
  const items = value.items.map((item: unknown): NotificationItem => {
    if (!record(item) || !id(item.id) || !id(item.announcementId) || item.type !== 'ANNOUNCEMENT_CREATED'
      || typeof item.title !== 'string' || !timestamp(item.createdAt) || !(item.readAt === null || timestamp(item.readAt))
      || typeof item.targetAvailable !== 'boolean') throw new Error('알림 응답을 확인하지 못했어요.')
    return item as unknown as NotificationItem
  })
  return { items, nextCursor: value.nextCursor, readThrough: value.readThrough }
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
      if (!record(csrf) || csrf.headerName !== 'X-XSRF-TOKEN' || typeof csrf.token !== 'string' || !csrf.token) throw new Error('읽음 처리를 준비하지 못했어요.')
      headers = { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }
    }
    signal.throwIfAborted()
    const response = await fetcher(url, { method: body ? 'POST' : 'GET', credentials: 'include', cache: 'no-store', headers,
      body: body ? JSON.stringify(body) : undefined, signal: bounded })
    if (!response.ok) throw new NotificationApiError(response.status)
    return response
  }
  return {
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
