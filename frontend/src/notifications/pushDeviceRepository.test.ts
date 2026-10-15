import { expect, it, vi } from 'vitest'
import { createPushDeviceRepository } from './pushDeviceRepository'
it('기기 등록은 예상 사용자와 버전을 CSRF 보호 요청에 포함한다', async () => {
  const response = { deviceId: 'b51ed2b7-1cce-4fe7-8e43-f45f4187ffdd', enabled: true, bindingVersion: '3', resumeAfterLogin: false, knownForUser: true }
  const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(Response.json({ token: 'csrf', headerName: 'X-XSRF-TOKEN' })).mockResolvedValueOnce(Response.json(response))
  expect(await createPushDeviceRepository(fetcher).register('fid', '2', '2', new AbortController().signal)).toEqual(response)
  expect(JSON.parse(String(fetcher.mock.calls[1]?.[1]?.body))).toMatchObject({ fid: 'fid', expectedUserId: '2', expectedBindingVersion: '2', platform: expect.any(String), browser: expect.any(String) })
  expect(fetcher.mock.calls[1]?.[1]).toMatchObject({ method: 'POST', credentials: 'include', body: expect.any(String) })
})
it('깨진 상태와 충돌 응답을 성공으로 취급하지 않는다', async () => {
  await expect(createPushDeviceRepository(async () => Response.json({ enabled: true })).current(new AbortController().signal)).rejects.toThrow()
  await expect(createPushDeviceRepository(async () => new Response(null, { status: 409 })).register('fid', '1', '1', new AbortController().signal)).rejects.toThrow()
})
it('기기 목록은 잘못된 날짜·식별자·현재 기기 여부를 거절한다', async () => {
  const item = { deviceId: 'b51ed2b7-1cce-4fe7-8e43-f45f4187ffdd', bindingVersion: '1', enabled: true, resumeAfterLogin: false, platform: 'MAC', browser: 'CHROME', current: true, lastSeenAt: '2026-10-15T00:00:00Z' }
  expect(await createPushDeviceRepository(async () => Response.json([item])).devices(new AbortController().signal)).toMatchObject([item])
  for (const malformed of [{ ...item, lastSeenAt: 'invalid' }, { ...item, current: 'true' }, { ...item, deviceId: 'invalid' }]) {
    await expect(createPushDeviceRepository(async () => Response.json([malformed])).devices(new AbortController().signal)).rejects.toThrow()
  }
})
it('다른 기기 해제도 사용자·바인딩 버전·CSRF를 포함한다', async () => {
  const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(Response.json({ token: 'csrf', headerName: 'X-XSRF-TOKEN' })).mockResolvedValueOnce(new Response(null, { status: 204 }))
  await createPushDeviceRepository(fetcher).disableDevice('device', '101', '7', new AbortController().signal)
  expect(fetcher.mock.calls[1]?.[0]).toContain('/notification-push-devices/device')
  expect(fetcher.mock.calls[1]?.[1]).toMatchObject({ method: 'DELETE', credentials: 'include', cache: 'no-store', headers: { 'X-XSRF-TOKEN': 'csrf' }, body: JSON.stringify({ expectedUserId: '101', bindingVersion: '7' }) })
})
it('이전 서버 응답은 모르는 기기를 임의로 신규 등록 대상으로 판단하지 않는다', async () => {
  const state = await createPushDeviceRepository(async () => Response.json({ deviceId: 'b51ed2b7-1cce-4fe7-8e43-f45f4187ffdd', bindingVersion: '1', enabled: false })).current(new AbortController().signal)
  expect(state.knownForUser).toBe(true)
})
