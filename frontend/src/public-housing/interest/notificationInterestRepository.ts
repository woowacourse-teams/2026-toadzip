import { getApiBaseUrl } from '../../api/apiBaseUrl'
import { analyticsCollectionAllowed } from '../../privacy/consentStore'
import { record } from '../../privacy/api'
export type NotificationTargetType = 'REGION' | 'COMPLEX' | 'ANNOUNCEMENT'
export type NotificationEventSource = 'SETTING' | 'REGION_SEARCH' | 'COMPLEX_DETAIL' | 'ANNOUNCEMENT_DETAIL'
export type NotificationEventType = 'EXPOSED' | 'CLICKED' | 'CONFIRMED' | 'DECLINED' | 'CANCELLED'
export interface NotificationTarget { readonly type: NotificationTargetType; readonly id: string; readonly name: string }
export interface NotificationInterestEvent {
  readonly eventId: string
  readonly eventType: NotificationEventType
  readonly source: NotificationEventSource
  readonly targetType: NotificationTargetType
  readonly targetId: string
  readonly expectedUserId?: string
  readonly expectedSettingsRevision?: number
  readonly noticeVersion?: string
  readonly sessionId?: string
}
export type NotificationOutcome = 'ACTIVATED' | 'ALREADY_ACTIVE' | 'NOT_ACTIVATED' | 'CANCELLED' | 'UNCHANGED' | 'OBSERVED' | 'UNKNOWN'
export interface CurrentTarget { readonly active: boolean; readonly expiresAt: string | null; readonly noticeVersion: string | null; readonly requestedAt: string | null }
export interface NotificationInterestResult {
  readonly eventId: string; readonly targetType: NotificationTargetType; readonly targetId: string
  readonly outcome: NotificationOutcome; readonly occurredAt: string
  readonly settingsRevision?: number; readonly currentTarget?: CurrentTarget
}
export class NotificationRequestError extends Error { readonly status: number; constructor(status: number) { super('알림 설정을 저장하지 못했습니다.'); this.status = status } }
function parseResult(value: unknown, event: NotificationInterestEvent): NotificationInterestResult {
  if (!record(value) || value.eventId !== event.eventId || value.targetType !== event.targetType || value.targetId !== event.targetId
    || typeof value.outcome !== 'string' || !['ACTIVATED', 'ALREADY_ACTIVE', 'NOT_ACTIVATED', 'CANCELLED', 'UNCHANGED', 'OBSERVED', 'UNKNOWN'].includes(value.outcome)
    || typeof value.occurredAt !== 'string' || !Number.isFinite(Date.parse(value.occurredAt))) throw new Error('알림 처리 결과를 확인하지 못했습니다.')
  if (event.eventType === 'CONFIRMED' || event.eventType === 'CANCELLED') {
    if (!Number.isSafeInteger(value.settingsRevision) || Number(value.settingsRevision) < 0 || !record(value.currentTarget)
      || typeof value.currentTarget.active !== 'boolean'
      || !['expiresAt', 'noticeVersion', 'requestedAt'].every(key => value.currentTarget && record(value.currentTarget) && (value.currentTarget[key] === null || typeof value.currentTarget[key] === 'string'))) throw new Error('알림 처리 결과를 확인하지 못했습니다.')
  }
  return value as unknown as NotificationInterestResult
}
export interface NotificationInterestRepository {
  record(event: NotificationInterestEvent, signal?: AbortSignal): Promise<NotificationInterestResult | void>
  loadStatus?(clientId?: string): Promise<NotificationSubscriptionStatus | null>
}
export interface NotificationSubscriptionStatus {
  readonly guest?: boolean; readonly userId?: string; readonly settingsRevision?: number
  readonly targets: ReadonlyArray<{ readonly targetType: NotificationTargetType; readonly targetId: string; readonly targetName?: string | null; readonly noticeVersion?: string | null; readonly requestedAt?: string | null; readonly expiresAt?: string }>
}
function parseStatus(value: unknown): NotificationSubscriptionStatus {
  if (!record(value) || typeof value.userId !== 'string' || !/^\d+$/.test(value.userId) || !Number.isSafeInteger(value.settingsRevision) || Number(value.settingsRevision) < 0 || !Array.isArray(value.targets)) throw new Error('알림 상태 응답이 올바르지 않습니다.')
  const targets = value.targets.map((item: unknown): NotificationSubscriptionStatus['targets'][number] => {
    if (!record(item) || !['COMPLEX', 'ANNOUNCEMENT', 'REGION'].includes(String(item.targetType))
      || typeof item.targetId !== 'string' || !/^[0-9]{1,19}$/.test(item.targetId)
      || (item.targetName !== null && item.targetName !== undefined && typeof item.targetName !== 'string')
      || !['noticeVersion', 'requestedAt'].every(key => item[key] === null || typeof item[key] === 'string')
      || typeof item.expiresAt !== 'string') throw new Error('알림 대상 응답이 올바르지 않습니다.')
    return item as unknown as NotificationSubscriptionStatus['targets'][number]
  })
  return { userId: value.userId, settingsRevision: Number(value.settingsRevision), targets }
}
export function createNotificationInterestRepository(fetcher: typeof globalThis.fetch = (...args) => globalThis.fetch(...args)): NotificationInterestRepository {
  const baseUrl = getApiBaseUrl()
  let csrfRequest: Promise<{ token: string; headerName: string }> | null = null
  async function loadCsrf() {
    const response = await fetcher(`${baseUrl}/api/auth/csrf`, { credentials: 'include', cache: 'no-store', signal: AbortSignal.timeout(15_000) })
    if (!response.ok) throw new Error('알림 저장을 준비하지 못했습니다.')
    const csrf: unknown = await response.json()
    if (!record(csrf) || typeof csrf.token !== 'string' || !csrf.token || csrf.headerName !== 'X-XSRF-TOKEN') throw new Error('알림 저장을 준비하지 못했습니다.')
    return { token: csrf.token, headerName: csrf.headerName }
  }
  return {
    async loadStatus() {
      const response = await fetcher(`${baseUrl}/api/v1/notification-subscriptions/me`, { credentials: 'include', cache: 'no-store', signal: AbortSignal.timeout(15_000) })
      if (response.status === 401) return { guest: true, targets: [] }
      if (!response.ok) throw new Error('알림 상태를 불러오지 못했습니다.')
      return parseStatus(await response.json())
    },
    async record(event, signal) {
      const business = event.eventType === 'CONFIRMED' || event.eventType === 'CANCELLED'
      if (!business && !analyticsCollectionAllowed()) return
      signal?.throwIfAborted()
      csrfRequest ??= loadCsrf().finally(() => { csrfRequest = null })
      const csrf = await csrfRequest
      signal?.throwIfAborted()
      if (!business && !analyticsCollectionAllowed()) return
      const response = await fetcher(`${baseUrl}/api/v1/${business ? 'notification-subscriptions/me' : 'notification-interest-events'}`, {
        method: 'POST', credentials: 'include', cache: 'no-store',
        headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }, body: JSON.stringify(event), signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(15_000)]) : AbortSignal.timeout(15_000),
      })
      if (!response.ok) throw new NotificationRequestError(response.status)
      return parseResult(await response.json(), event)
    },
  }
}
export const notificationInterestRepository = createNotificationInterestRepository()
