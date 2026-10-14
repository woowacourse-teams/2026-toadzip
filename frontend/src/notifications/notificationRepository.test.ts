import { expect, it, vi } from 'vitest'
import { createNotificationRepository, NotificationApiError } from './notificationRepository'

it('목록은 GET만 보내고 ID 문자열과 snapshot 경계를 보존한다', async () => {
  const body = { items: [{ id: '9007199254740993', type: 'ANNOUNCEMENT_CREATED', announcementId: '3', title: '새 공고', createdAt: '2026-10-13T00:00:00Z', readAt: null, targetAvailable: true }], nextCursor: 'next', readThrough: '9007199254740993' }
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
