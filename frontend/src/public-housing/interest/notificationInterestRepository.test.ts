import { describe, expect, it, vi } from 'vitest'
import { createNotificationInterestRepository, type NotificationInterestEvent } from './notificationInterestRepository'
const event: NotificationInterestEvent = { eventId: '00000000-0000-4000-8000-000000000001', expectedUserId: '7', expectedSettingsRevision: 0, noticeVersion: 'notification-2026-10-09-v1', eventType: 'CONFIRMED', source: 'REGION_SEARCH', targetType: 'REGION', targetId: '11' }
const target = { targetType: 'REGION', targetId: '11', noticeVersion: 'notification-2026-10-09-v1', requestedAt: '2026-10-09T00:00:00Z', expiresAt: '2027-10-09T00:00:00Z' }
const result = { eventId: event.eventId, targetType: event.targetType, targetId: event.targetId, outcome: 'ACTIVATED', occurredAt: '2026-10-09T00:00:00Z', settingsRevision: 1, currentTarget: { active: true, expiresAt: target.expiresAt, noticeVersion: target.noticeVersion, requestedAt: target.requestedAt } }
const csrf = () => Response.json({ token: 'csrf', headerName: 'X-XSRF-TOKEN' })
describe('회원 알림 설정 API', () => {
  it('동의 없는 노출·클릭은 CSRF나 자체 분석 요청조차 만들지 않는다', async () => {
    const fetcher = vi.fn(); const repository = createNotificationInterestRepository(fetcher)
    await repository.record({ ...event, eventType: 'EXPOSED' }); await repository.record({ ...event, eventType: 'CLICKED' })
    expect(fetcher).not.toHaveBeenCalled()
  })
  it('CSRF 대기 중 해제가 중단되면 변경을 보내지 않는다', async () => {
    let resolve!: (response: Response) => void
    const fetcher = vi.fn().mockReturnValueOnce(new Promise<Response>(yes => { resolve = yes }))
    const controller = new AbortController(); const pending = createNotificationInterestRepository(fetcher).record({ ...event, eventType: 'CANCELLED' }, controller.signal)
    controller.abort(); resolve(csrf()); await expect(pending).rejects.toThrow(); expect(fetcher).toHaveBeenCalledOnce()
  })
  it('계정 ID·revision·고지 기간을 조회하고 401만 비회원으로 처리한다', async () => {
    const snapshot = { userId: '7', settingsRevision: 3, targets: [target] }
    const fetcher = vi.fn().mockResolvedValueOnce(Response.json(snapshot)).mockResolvedValueOnce(new Response(null, { status: 401 })).mockResolvedValueOnce(new Response(null, { status: 403 }))
    const repository = createNotificationInterestRepository(fetcher)
    expect(await repository.loadStatus?.()).toEqual(snapshot)
    expect(await repository.loadStatus?.()).toEqual({ guest: true, targets: [] })
    await expect(repository.loadStatus?.()).rejects.toThrow(); expect(fetcher.mock.calls[0]?.[0]).toMatch(/notification-subscriptions\/me$/)
  })
  it('기존 알림 설정의 확인되지 않은 고지 버전과 신청 시각은 null 그대로 조회한다', async () => {
    const legacyTarget = { ...target, noticeVersion: null, requestedAt: null }
    const snapshot = { userId: '7', settingsRevision: 0, targets: [legacyTarget, { ...target, targetId: '26' }] }
    const fetcher = vi.fn().mockResolvedValue(Response.json(snapshot))
    expect(await createNotificationInterestRepository(fetcher).loadStatus?.()).toEqual(snapshot)
  })
  it.each([{ noticeVersion: 1 }, { requestedAt: false }, { requestedAt: undefined }, { expiresAt: null }])('기존 설정도 누락되거나 잘못된 값은 거부한다: %j', async invalid => {
    const snapshot = { userId: '7', settingsRevision: 0, targets: [{ ...target, ...invalid }] }
    const fetcher = vi.fn().mockResolvedValue(Response.json(snapshot))
    await expect(createNotificationInterestRepository(fetcher).loadStatus?.()).rejects.toThrow('알림 대상 응답')
  })
  it('동시 업무 요청은 CSRF 초기화를 공유한다', async () => {
    const fetcher = vi.fn<typeof fetch>(async (url, options) => String(url).endsWith('/csrf') ? csrf() : Response.json({ ...result, eventId: JSON.parse(String(options?.body)).eventId }))
    const repository = createNotificationInterestRepository(fetcher)
    await Promise.all([repository.record(event), repository.record({ ...event, eventId: crypto.randomUUID() })])
    expect(fetcher.mock.calls.filter(([url]) => String(url).endsWith('/csrf'))).toHaveLength(1)
  })
  it('거부 상태에서도 회원 업무는 계정·revision·고지만 전송한다', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(csrf()).mockResolvedValueOnce(Response.json(result))
    expect(await createNotificationInterestRepository(fetcher).record(event)).toEqual(result)
    expect(fetcher.mock.calls[1]?.[0]).toMatch(/notification-subscriptions\/me$/)
    expect(fetcher.mock.calls[1]?.[1]).toMatchObject({ credentials: 'include', cache: 'no-store', body: JSON.stringify(event), headers: { 'X-XSRF-TOKEN': 'csrf' } })
    expect(JSON.parse(fetcher.mock.calls[1]?.[1].body)).not.toHaveProperty('sessionId')
  })
  it('잘못된 CSRF 헤더는 전송하지 않는다', async () => {
    const fetcher = vi.fn().mockResolvedValue(Response.json({ token: 'csrf', headerName: 'Cookie' }))
    await expect(createNotificationInterestRepository(fetcher).record(event)).rejects.toThrow(); expect(fetcher).toHaveBeenCalledOnce()
  })
  it.each([{ eventId: 'another' }, { targetId: 'other' }, { outcome: 'SAVED' }, { occurredAt: 'invalid' }, { settingsRevision: -1 }, { currentTarget: null }])('잘못된 성공 응답을 사용하지 않는다: %j', async invalid => {
    const fetcher = vi.fn().mockResolvedValueOnce(csrf()).mockResolvedValueOnce(Response.json({ ...result, ...invalid }))
    await expect(createNotificationInterestRepository(fetcher).record(event)).rejects.toThrow('알림 처리 결과')
  })
  it.each([204, 409, 500])('저장 결과를 확인할 수 없는 %s 응답은 성공이 아니다', async status => {
    const fetcher = vi.fn().mockResolvedValueOnce(csrf()).mockResolvedValueOnce(new Response(null, { status }))
    await expect(createNotificationInterestRepository(fetcher).record(event)).rejects.toThrow()
  })
  it.each([{ userId: '7', settingsRevision: 0, targets: [{ ...target, targetType: 'INVALID' }] }, { userId: '7', settingsRevision: 0, targets: [{ ...target, targetName: {} }] }, { userId: '7', targets: [] }])('잘못된 목록 응답은 거부한다: %j', async snapshot => {
    const fetcher = vi.fn().mockResolvedValue(Response.json(snapshot)); await expect(createNotificationInterestRepository(fetcher).loadStatus?.()).rejects.toThrow()
  })
  it('취소 요청과 AbortSignal을 회원 API로 전달한다', async () => {
    const request = { ...event, eventType: 'CANCELLED' as const, noticeVersion: undefined }
    const cancelled = { ...result, outcome: 'CANCELLED', currentTarget: { active: false, noticeVersion: null, requestedAt: null, expiresAt: null } }
    const fetcher = vi.fn().mockResolvedValueOnce(csrf()).mockResolvedValueOnce(Response.json(cancelled)); const controller = new AbortController()
    expect(await createNotificationInterestRepository(fetcher).record(request, controller.signal)).toEqual(cancelled)
    expect(fetcher.mock.calls[1]?.[1]).toMatchObject({ body: JSON.stringify(request) })
    controller.abort()
    expect(fetcher.mock.calls[1]?.[1].signal.aborted).toBe(true)
  })
})
