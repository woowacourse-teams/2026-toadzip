import { expect, it, vi } from 'vitest'
import { createNotificationRepository, NotificationApiError } from './notificationRepository'

it('목록은 GET만 보내고 ID 문자열과 snapshot 경계를 보존한다', async () => {
  const body = { items: [{ id: '9007199254740993', type: 'ANNOUNCEMENT_CREATED', announcementId: '3', title: '새 공고', createdAt: '2026-10-13T00:00:00Z', readAt: null, targetAvailable: true }], nextCursor: 'next', readThrough: '9007199254740993', totalCount: 1 }
  const fetcher = vi.fn<typeof fetch>(async () => Response.json(body))
  const repository = createNotificationRepository(fetcher)
  expect(await repository.list(null, new AbortController().signal)).toEqual(body)
  expect(fetcher).toHaveBeenCalledOnce()
  expect(fetcher.mock.calls[0]?.[1]).toMatchObject({ credentials: 'include', cache: 'no-store' })
})

it('모두 읽음은 CSRF 토큰과 읽은 목록의 경계를 보낸다', async () => {
  const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(Response.json({ token: 'csrf', headerName: 'X-XSRF-TOKEN' })).mockResolvedValueOnce(new Response(null, { status: 204 }))
  await createNotificationRepository(fetcher).readAll('123', new AbortController().signal)
  expect(fetcher.mock.calls[1]?.[1]).toMatchObject({ method: 'POST', headers: { 'X-XSRF-TOKEN': 'csrf' }, body: JSON.stringify({ readThrough: '123' }) })
})

it('잘못된 응답과 인증 만료를 구분한다', async () => {
  await expect(createNotificationRepository(async () => Response.json({ unreadCount: -1 })).count(new AbortController().signal)).rejects.toThrow('응답')
  await expect(createNotificationRepository(async () => new Response(null, { status: 401 })).count(new AbortController().signal)).rejects.toBeInstanceOf(NotificationApiError)
})

it('전체 삭제는 CSRF와 최초 경계 및 선택에서 제외한 ID를 서버에 전달한다', async () => {
  const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(Response.json({ token: 'csrf', headerName: 'X-XSRF-TOKEN' })).mockResolvedValueOnce(new Response(null, { status: 204 }))
  await createNotificationRepository(fetcher).delete({ deleteThrough: '123', excludedIds: ['12'] }, new AbortController().signal)
  expect(fetcher.mock.calls[1]?.[0]).toMatch(/notifications\/delete$/)
  expect(fetcher.mock.calls[1]?.[1]).toMatchObject({ method: 'POST', headers: { 'X-XSRF-TOKEN': 'csrf' }, body: '{"deleteThrough":"123","excludedIds":["12"]}' })
})

const schedulePage = (sourceUrl = 'https://example.com/notice') => ({ items: [{ id: '10', type: 'SCHEDULE_REMINDER', announcementId: '23', title: '서울 공고', createdAt: '2026-10-13T00:00:00Z', readAt: null, targetAvailable: true, content: { type: 'SCHEDULE_REMINDER', targetType: 'ANNOUNCEMENT', targetId: '23', targetName: '접수', message: '내일 접수가 시작돼요.', schedule: { kind: 'APPLICATION', name: '접수', startDate: '2026-10-14', endDate: '2026-10-15', startTime: null, endTime: '17:00:00', scope: null, condition: null, sourceUrl, sourcePage: null, phase: 'START' } } }], nextCursor: null, readThrough: '10', totalCount: 1 })
it('일정 알림 응답을 읽고 안전하지 않은 원문 링크는 제거한다', async () => {
  for (const url of ['https://example.com/notice', 'javascript:alert(1)', 'https://user:pass@example.com']) {
    const result = await createNotificationRepository(async () => Response.json(schedulePage(url))).list(null, new AbortController().signal)
    expect(result.items[0]?.content?.schedule?.sourceUrl).toBe(url === 'https://example.com/notice' ? url : null)
  }
})
it('잘못된 일정 날짜와 누락된 일정 정보는 응답 오류로 처리한다', async () => {
  const body = schedulePage()
  body.items[0]!.content.schedule.startDate = '2026-02-30'
  await expect(createNotificationRepository(async () => Response.json(body)).list(null, new AbortController().signal)).rejects.toThrow('응답')
  await expect(createNotificationRepository(async () => Response.json({ ...body, items: [{ ...body.items[0], content: null }] })).list(null, new AbortController().signal)).rejects.toThrow('응답')
})
