import { describe, expect, it, vi } from 'vitest'
import { createNotificationInterestRepository, type NotificationInterestEvent } from './notificationInterestRepository'

const event: NotificationInterestEvent = {
  eventId: '00000000-0000-4000-8000-000000000001',
  sessionId: '00000000-0000-4000-8000-000000000002',
  eventType: 'CLICKED', source: 'REGION_SEARCH', targetType: 'REGION', targetId: '11',
}

describe('알림 수요 저장 경계', () => {
  it('CSRF 대기 중 해제가 중단되면 변경 요청을 보내지 않는다', async () => {
    let resolveCsrf!: (response: Response) => void
    const fetcher = vi.fn().mockReturnValueOnce(new Promise<Response>((resolve) => { resolveCsrf = resolve }))
      .mockResolvedValue(new Response(null, { status: 204 }))
    const controller = new AbortController()
    const pending = createNotificationInterestRepository(fetcher).record({ ...event, eventType: 'CANCELLED' }, controller.signal)
    controller.abort()
    resolveCsrf(new Response(JSON.stringify({ token: 'csrf', headerName: 'X-XSRF-TOKEN' })))
    await expect(pending).rejects.toThrow()
    expect(fetcher).toHaveBeenCalledOnce()
  })

  it('회원 설정을 조회하고 비회원의 이전 브라우저 설정은 읽지 않는다', async () => {
    const fetcher = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ emailConfirmed: true, targets: [
        { targetType: 'REGION', targetId: '11' },
      ] })))
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ emailConfirmed: false, targets: [] })))
    const repository = createNotificationInterestRepository(fetcher)
    expect(await repository.loadStatus?.('guest-client')).toEqual({ emailConfirmed: true, targets: [
      { targetType: 'REGION', targetId: '11' },
    ] })
    expect(await repository.loadStatus?.('guest-client')).toEqual({ guest: true, emailConfirmed: false, targets: [] })
    expect(fetcher.mock.calls[0]?.[0]).toMatch(/\/api\/v1\/notification-subscriptions\/me$/)
    expect(fetcher).toHaveBeenCalledTimes(2)
  })

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

  it('성공 응답의 결과와 요청 식별자를 검증한다', async () => {
    const result = { eventId: event.eventId, targetType: event.targetType, targetId: event.targetId,
      outcome: 'ACTIVATED', occurredAt: '2026-10-08T00:00:00Z' }
    const fetcher = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ token: 'test-token', headerName: 'X-CSRF-TOKEN' })))
      .mockResolvedValueOnce(new Response(JSON.stringify(result)))
    expect(await createNotificationInterestRepository(fetcher).record(event)).toEqual(result)
  })

  it.each([{ eventId: 'another-event' }, { targetId: 'other' }, { outcome: 'SAVED' }, { occurredAt: 'invalid' }])(
    '요청과 맞지 않거나 알 수 없는 처리 결과를 성공으로 추측하지 않는다: %j', async (invalid) => {
      const fetcher = vi.fn()
        .mockResolvedValueOnce(new Response(JSON.stringify({ token: 'test-token', headerName: 'X-CSRF-TOKEN' })))
        .mockResolvedValueOnce(new Response(JSON.stringify({ eventId: event.eventId, targetType: event.targetType,
          targetId: event.targetId, outcome: 'ACTIVATED', occurredAt: '2026-10-08T00:00:00Z', ...invalid })))
      await expect(createNotificationInterestRepository(fetcher).record(event)).rejects.toThrow('알림 처리 결과')
    },
  )

  it('DB 저장 실패를 성공으로 처리하지 않는다', async () => {
    const fetcher = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ token: 'test-token', headerName: 'X-CSRF-TOKEN' })))
      .mockResolvedValueOnce(new Response(null, { status: 500 }))
    await expect(createNotificationInterestRepository(fetcher).record(event)).rejects.toThrow('관심을 기록하지 못했습니다.')
  })
})

it('회원 설정 변경은 인증 필수 경로로 전송한다', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ token: 'csrf', headerName: 'X-XSRF-TOKEN' })))
    .mockResolvedValueOnce(new Response(null, { status: 204 }))
  await createNotificationInterestRepository(fetcher).record({ ...event, eventType: 'CONFIRMED' })
  expect(fetcher.mock.calls[1]?.[0]).toMatch(/\/notification-subscriptions\/me$/)
})

it.each([
  { emailConfirmed: false, targets: [{ targetType: 'INVALID', targetId: '1' }] },
  { emailConfirmed: false, targets: [{ targetType: 'COMPLEX', targetId: '1', targetName: {} }] },
  { emailConfirmed: false, targets: null },
])('잘못된 설정 응답을 화면에 전달하지 않는다: %j', async (body) => {
  const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify(body)))
  await expect(createNotificationInterestRepository(fetcher).loadStatus?.('client')).rejects.toThrow()
})

it.each([
  { eventType: 'CONFIRMED' as const, outcome: 'ACTIVATED' },
  { eventType: 'CANCELLED' as const, outcome: 'CANCELLED' },
])('회원 $eventType 요청도 서버 결과를 검증하고 중단 신호를 전달한다', async ({ eventType, outcome }) => {
  const request = { ...event, eventType }
  const result = { eventId: request.eventId, targetType: request.targetType, targetId: request.targetId,
    outcome, occurredAt: '2026-10-08T00:00:00Z' }
  const fetcher = vi.fn()
    .mockResolvedValueOnce(Response.json({ token: 'csrf', headerName: 'X-XSRF-TOKEN' }))
    .mockResolvedValueOnce(Response.json(result))
  const controller = new AbortController()
  expect(await createNotificationInterestRepository(fetcher).record(request, controller.signal)).toEqual(result)
  expect(fetcher.mock.calls[1]?.[0]).toMatch(/\/notification-subscriptions\/me$/)
  expect(fetcher.mock.calls[1]?.[1]).toMatchObject({ body: JSON.stringify(request), signal: controller.signal })
})
