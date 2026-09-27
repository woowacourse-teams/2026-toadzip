import { describe, expect, it, vi } from 'vitest'
import { createNotificationInterestRepository, type NotificationInterestEvent } from './notificationInterestRepository'

const event: NotificationInterestEvent = {
  eventId: '00000000-0000-4000-8000-000000000001',
  sessionId: '00000000-0000-4000-8000-000000000002',
  eventType: 'CLICKED', source: 'REGION_SEARCH', targetType: 'REGION', targetId: '11',
}

describe('알림 수요 저장 경계', () => {
  it('동시 노출 요청은 CSRF 초기화를 공유한다', async () => {
    const fetcher = vi.fn().mockImplementation(async (url: string) => url.endsWith('/csrf')
      ? new Response(JSON.stringify({ token: 'test-token', headerName: 'X-XSRF-TOKEN' }))
      : new Response(null, { status: 204 }))
    const repository = createNotificationInterestRepository(fetcher)
    await Promise.all([repository.record(event), repository.record({ ...event, eventId: '00000000-0000-4000-8000-000000000003' })])
    expect(fetcher.mock.calls.filter(([url]) => String(url).endsWith('/csrf'))).toHaveLength(1)
    expect(fetcher).toHaveBeenCalledTimes(3)
  })

  it('공개 CSRF 토큰과 쿠키를 사용하여 이벤트만 전송한다', async () => {
    const fetcher = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ token: 'test-token', headerName: 'X-XSRF-TOKEN' })))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
    await createNotificationInterestRepository(fetcher).record(event)
    expect(fetcher.mock.calls[0]?.[0]).toMatch(/\/api\/auth\/csrf$/)
    expect(fetcher.mock.calls[1]?.[0]).toMatch(/\/api\/v1\/notification-interest-events$/)
    expect(fetcher.mock.calls[1]?.[1]).toEqual({
      method: 'POST', credentials: 'include',
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': 'test-token' },
      body: JSON.stringify(event),
    })
  })

  it('잘못된 CSRF 응답이면 저장 요청을 보내지 않는다', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({ token: 'test-token', headerName: 'Cookie' })))
    await expect(createNotificationInterestRepository(fetcher).record(event)).rejects.toThrow()
    expect(fetcher).toHaveBeenCalledOnce()
  })

  it('DB 저장 실패를 성공으로 처리하지 않는다', async () => {
    const fetcher = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ token: 'test-token', headerName: 'X-CSRF-TOKEN' })))
      .mockResolvedValueOnce(new Response(null, { status: 500 }))
    await expect(createNotificationInterestRepository(fetcher).record(event)).rejects.toThrow('관심을 기록하지 못했습니다.')
  })
})
