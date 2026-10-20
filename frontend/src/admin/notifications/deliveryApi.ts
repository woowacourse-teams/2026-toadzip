import { requestManagementApi } from '../management/api'
import { isRecord } from '../management/managementContract'

export const deliveryStates = {
  PENDING: '발송 대기', SENDING: '발송 처리 중', RETRY: '자동 재시도 대기', SENT: '푸시 제공자 접수',
  SKIPPED: '발송 제외', FAILED: '발송 실패', NO_DELIVERY: '발송 기록 없음',
} as const
export type Delivery = {
  notificationId: number; userId: number; announcementId: number; title: string;
  deliveryStatus: keyof typeof deliveryStates; deliveryId: number | null; attempts: number; errorCode: string | null;
  createdAt: string; nextAttemptAt: string | null; finishedAt: string | null; readAt: string | null;
  deleted: boolean; expiresAt: string;
}
export type DeliveryPage = { items: Delivery[]; page: number; totalElements: number; totalPages: number }
const count = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
const time = (value: unknown) => typeof value === 'string' && Number.isFinite(Date.parse(value))
export async function listDeliveries(params: URLSearchParams, signal?: AbortSignal): Promise<DeliveryPage> {
  const value = await requestManagementApi(`/api/admin/notification-deliveries?${params}`, 'GET', undefined, signal)
  if (!isRecord(value) || !Array.isArray(value.items) || !count(value.page) || !count(value.totalElements) || !count(value.totalPages)) {
    throw new Error('알림 발송 목록 응답이 올바르지 않습니다.')
  }
  const items = value.items.map(item => {
    if (!isRecord(item) || !count(item.notificationId) || !count(item.userId) || !count(item.announcementId)
      || typeof item.title !== 'string' || typeof item.deliveryStatus !== 'string' || !Object.hasOwn(deliveryStates, item.deliveryStatus)
      || !(item.deliveryId === null || count(item.deliveryId)) || !count(item.attempts)
      || !(item.errorCode === null || typeof item.errorCode === 'string') || !time(item.createdAt) || !time(item.expiresAt)
      || ![item.nextAttemptAt, item.finishedAt, item.readAt].every(value => value === null || time(value))
      || typeof item.deleted !== 'boolean') throw new Error('알림 발송 기록 응답이 올바르지 않습니다.')
    return item as Delivery
  })
  return { items, page: value.page, totalElements: value.totalElements, totalPages: value.totalPages }
}
