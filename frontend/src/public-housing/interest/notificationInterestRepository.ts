import { getApiBaseUrl } from '../../api/apiBaseUrl'
export type NotificationTargetType = 'REGION' | 'COMPLEX' | 'ANNOUNCEMENT'
export type NotificationEventSource = 'SETTING' | 'REGION_SEARCH' | 'COMPLEX_DETAIL' | 'ANNOUNCEMENT_DETAIL'
export type NotificationEventType = 'EXPOSED' | 'CLICKED' | 'CONFIRMED' | 'DECLINED' | 'CANCELLED'

export interface NotificationTarget {
  readonly type: NotificationTargetType
  readonly id: string
  readonly name: string
}

export interface NotificationInterestEvent {
  readonly eventId: string
  readonly sessionId: string
  readonly eventType: NotificationEventType
  readonly source: NotificationEventSource
  readonly targetType: NotificationTargetType
  readonly targetId: string
  readonly email?: string
  readonly clientId?: string
}

export type NotificationOutcome = 'ACTIVATED' | 'ALREADY_ACTIVE' | 'NOT_ACTIVATED' | 'CANCELLED' | 'UNCHANGED' | 'OBSERVED' | 'UNKNOWN'

export interface NotificationInterestResult {
  readonly eventId: string
  readonly targetType: NotificationTargetType
  readonly targetId: string
  readonly outcome: NotificationOutcome
  readonly occurredAt: string
}

function parseResult(value: unknown, event: NotificationInterestEvent): NotificationInterestResult {
  if (typeof value !== 'object' || value === null
    || !('eventId' in value) || value.eventId !== event.eventId
    || !('targetType' in value) || value.targetType !== event.targetType
    || !('targetId' in value) || value.targetId !== event.targetId
    || !('outcome' in value) || typeof value.outcome !== 'string'
    || !['ACTIVATED', 'ALREADY_ACTIVE', 'NOT_ACTIVATED', 'CANCELLED', 'UNCHANGED', 'OBSERVED', 'UNKNOWN'].includes(value.outcome)
    || !('occurredAt' in value) || typeof value.occurredAt !== 'string' || !Number.isFinite(Date.parse(value.occurredAt))) {
    throw new Error('알림 처리 결과를 확인하지 못했습니다.')
  }
  return { eventId: event.eventId, targetType: event.targetType, targetId: event.targetId,
    outcome: value.outcome as NotificationOutcome, occurredAt: value.occurredAt }
}

export interface NotificationInterestRepository {
  record(event: NotificationInterestEvent, signal?: AbortSignal): Promise<NotificationInterestResult | void>
  loadStatus?(clientId: string): Promise<NotificationSubscriptionStatus | null>
}

export interface NotificationSubscriptionStatus {
  readonly guest?: boolean
  readonly emailConfirmed: boolean
  readonly targets: ReadonlyArray<{ readonly targetType: NotificationTargetType; readonly targetId: string; readonly targetName?: string | null }>
}

function parseStatus(value: unknown): NotificationSubscriptionStatus {
  if (typeof value !== 'object' || value === null || !('emailConfirmed' in value)
    || typeof value.emailConfirmed !== 'boolean' || !('targets' in value) || !Array.isArray(value.targets)) {
    throw new Error('알림 상태 응답이 올바르지 않습니다.')
  }
  const targets = value.targets.map((item: unknown): NotificationSubscriptionStatus['targets'][number] => {
    if (typeof item !== 'object' || item === null || !('targetType' in item)
      || (item.targetType !== 'COMPLEX' && item.targetType !== 'ANNOUNCEMENT' && item.targetType !== 'REGION')
      || !('targetId' in item) || typeof item.targetId !== 'string' || !/^[0-9]{1,19}$/.test(item.targetId)
      || ('targetName' in item && item.targetName !== null && typeof item.targetName !== 'string')) {
      throw new Error('알림 대상 응답이 올바르지 않습니다.')
    }
    return { targetType: item.targetType, targetId: item.targetId,
      ...('targetName' in item ? { targetName: item.targetName as string | null } : {}) }
  })
  return { emailConfirmed: value.emailConfirmed, targets }
}

export function createNotificationInterestRepository(
  fetcher: typeof globalThis.fetch = globalThis.fetch,
): NotificationInterestRepository {
  const baseUrl = getApiBaseUrl()
  let csrfRequest: Promise<{ token: string; headerName: string }> | null = null

  async function loadCsrf() {
    const csrfResponse = await fetcher(`${baseUrl}/api/auth/csrf`, { credentials: 'include' })
    if (!csrfResponse.ok) throw new Error('관심을 기록할 준비를 하지 못했습니다.')
    const csrf: unknown = await csrfResponse.json()
    if (typeof csrf !== 'object' || csrf === null
      || !('token' in csrf) || typeof csrf.token !== 'string' || !csrf.token
      || !('headerName' in csrf) || typeof csrf.headerName !== 'string'
      || !['X-CSRF-TOKEN', 'X-XSRF-TOKEN'].includes(csrf.headerName)) {
      throw new Error('관심을 기록할 준비를 하지 못했습니다.')
    }
    return { token: csrf.token, headerName: csrf.headerName }
  }

  return {
    async loadStatus() {
      const response = await fetcher(`${baseUrl}/api/v1/notification-subscriptions/me`, { credentials: 'include' })
      if (response.status === 401 || response.status === 403) {
        return { guest: true, emailConfirmed: false, targets: [] }
      }
      if (!response.ok) throw new Error('알림 상태를 불러오지 못했습니다.')
      return parseStatus(await response.json())
    },
    async record(event, signal) {
      signal?.throwIfAborted()
      // Concurrent first exposures must share the same CSRF cookie initialization.
      csrfRequest ??= loadCsrf().finally(() => { csrfRequest = null })
      const csrf = await csrfRequest
      signal?.throwIfAborted()
      const response = await fetcher(`${baseUrl}/api/v1/${event.eventType === 'CONFIRMED' || event.eventType === 'CANCELLED' ? 'notification-subscriptions/me' : 'notification-interest-events'}`, {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
        body: JSON.stringify(event),
        ...(signal ? { signal } : {}),
      })
      if (!response.ok) throw new Error('관심을 기록하지 못했습니다.')
      // Older deployments acknowledged requests without their business outcome.
      if (response.status === 204) return
      return parseResult(await response.json(), event)
    },
  }
}

export const notificationInterestRepository = createNotificationInterestRepository()
