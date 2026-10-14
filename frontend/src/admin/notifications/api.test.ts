import { afterEach, expect, it, vi } from 'vitest'
import { loadNotificationSubscriptions, loadNotificationTargets, loadNotificationTrend } from './api'

afterEach(() => vi.unstubAllGlobals())
const item = { id: '9007199254740993', userId: '9007199254740995', email: null, provider: 'GOOGLE', targetType: 'REGION',
  targetId: '11', targetName: '서울특별시 전체', status: 'ACTIVE', requestedAt: null, expiresAt: '2027-10-13T00:00:00Z' }
const data = { items: [item], page: 0, hasNext: false, totalElements: 1, totalPages: 1,
  summary: { activeMembers: 1, activeSubscriptions: 1, asOf: '2026-10-13T00:00:00Z' } }

it('큰 회원 ID를 문자열로 보존하고 인증·취소 신호를 조회 경계에 전달한다', async () => {
  const fetcher = vi.fn().mockResolvedValue(Response.json({ data }))
  vi.stubGlobal('fetch', fetcher)
  const controller = new AbortController()
  const result = await loadNotificationSubscriptions(new URLSearchParams({ status: 'ACTIVE' }), controller.signal)
  expect(result.items[0]?.userId).toBe('9007199254740995')
  expect(fetcher).toHaveBeenCalledWith(expect.stringContaining('/api/admin/notification-subscriptions?status=ACTIVE'),
    expect.objectContaining({ method: 'GET', credentials: 'include', signal: controller.signal }))
})

it('회원별 조회는 해당 회원 경로로 조회한다', async () => {
  const fetcher = vi.fn().mockResolvedValue(Response.json({ data }))
  vi.stubGlobal('fetch', fetcher)
  await loadNotificationSubscriptions(new URLSearchParams({ status: 'ALL' }), undefined, '7')
  expect(fetcher.mock.calls[0]?.[0]).toContain('/api/admin/users/7/notification-subscriptions?status=ALL')
})

it.each([
  { ...data, summary: { ...data.summary, activeMembers: -1 } },
  { ...data, items: [{ ...item, status: 'ALL' }] },
  { ...data, items: [{ ...item, expiresAt: 'invalid' }] },
  { ...data, items: [{ ...item, userId: 7 }] },
])('잘못된 신청 응답을 성공 목록으로 처리하지 않는다', async value => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ data: value })))
  await expect(loadNotificationSubscriptions(new URLSearchParams())).rejects.toThrow('응답이 올바르지 않습니다.')
})

it('기존 신청에서 허용한 선행 0 대상 ID를 원문 그대로 표시한다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ data: {
    ...data, items: [{ ...item, targetType: 'COMPLEX', targetId: '01' }],
  } })))
  expect((await loadNotificationSubscriptions(new URLSearchParams())).items[0]?.targetId).toBe('01')
})

it('권한 오류의 상태와 설명을 보존한다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ message: '접근 권한이 없습니다.' }, { status: 403 })))
  await expect(loadNotificationSubscriptions(new URLSearchParams())).rejects.toMatchObject({ status: 403, message: '접근 권한이 없습니다.' })
})

it('대상별 현황은 인증 쿠키와 요청 취소 신호를 전달한다', async () => {
  const fetcher = vi.fn().mockResolvedValue(Response.json({ data: { items: [{ targetType: 'COMPLEX', targetId: '1',
    targetName: null, activeMembers: 2 }], page: 0, totalElements: 1, totalPages: 1, asOf: '2026-10-14T00:00:00Z' } }))
  vi.stubGlobal('fetch', fetcher)
  const controller = new AbortController()
  expect((await loadNotificationTargets('page=0', controller.signal)).items[0]?.activeMembers).toBe(2)
  expect(fetcher).toHaveBeenCalledWith(expect.stringContaining('/targets?page=0'),
    expect.objectContaining({ credentials: 'include', signal: controller.signal }))
})

it.each([
  { day: '2026-02-31', activations: 1, cancellations: 0 },
  { day: '2026-10-14', activations: -1, cancellations: 0 },
  { day: '2026-10-14', activations: null, cancellations: 0 },
])('잘못된 날짜나 집계값을 정상 추이로 표시하지 않는다', async point => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ data: {
    collectionStartedAt: null, asOf: '2026-10-14T00:00:00Z', days: [point],
  } })))
  await expect(loadNotificationTrend('days=7', new AbortController().signal)).rejects.toThrow('응답이 올바르지 않습니다.')
})
