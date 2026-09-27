export type NotificationTargetType = 'REGION' | 'COMPLEX' | 'ANNOUNCEMENT'
export type NotificationEventSource = 'SETTING' | 'REGION_SEARCH' | 'COMPLEX_DETAIL' | 'ANNOUNCEMENT_DETAIL'
export type NotificationEventType = 'EXPOSED' | 'CLICKED' | 'CONFIRMED' | 'DECLINED'

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
}

export interface NotificationInterestRepository {
  record(event: NotificationInterestEvent): Promise<void>
}

export function createNotificationInterestRepository(
  fetcher: typeof globalThis.fetch = globalThis.fetch,
): NotificationInterestRepository {
  const baseUrl = import.meta.env.VITE_API_BASE_URL || (import.meta.env.DEV ? 'http://localhost:8080' : '')
  return {
    async record(event) {
      const csrfResponse = await fetcher(`${baseUrl}/api/auth/csrf`, { credentials: 'include' })
      if (!csrfResponse.ok) throw new Error('관심을 기록할 준비를 하지 못했습니다.')
      const csrf: unknown = await csrfResponse.json()
      if (typeof csrf !== 'object' || csrf === null
        || !('token' in csrf) || typeof csrf.token !== 'string' || !csrf.token
        || !('headerName' in csrf) || typeof csrf.headerName !== 'string'
        || !['X-CSRF-TOKEN', 'X-XSRF-TOKEN'].includes(csrf.headerName)) {
        throw new Error('관심을 기록할 준비를 하지 못했습니다.')
      }
      const response = await fetcher(`${baseUrl}/api/v1/notification-interest-events`, {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
        body: JSON.stringify(event),
      })
      if (!response.ok) throw new Error('관심을 기록하지 못했습니다.')
    },
  }
}

export const notificationInterestRepository = createNotificationInterestRepository()
