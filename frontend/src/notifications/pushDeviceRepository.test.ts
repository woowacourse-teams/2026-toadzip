import { expect, it, vi } from 'vitest'
import { createPushDeviceRepository } from './pushDeviceRepository'
it('기기 등록은 예상 사용자와 버전을 CSRF 보호 요청에 포함한다', async () => {
  const response = { deviceId: 'b51ed2b7-1cce-4fe7-8e43-f45f4187ffdd', enabled: true, bindingVersion: '3' }
  const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(Response.json({ token: 'csrf', headerName: 'X-XSRF-TOKEN' })).mockResolvedValueOnce(Response.json(response))
  expect(await createPushDeviceRepository(fetcher).register('fid', '2', '2', new AbortController().signal)).toEqual(response)
  expect(fetcher.mock.calls[1]?.[1]).toMatchObject({ method: 'POST', credentials: 'include', body: JSON.stringify({ fid: 'fid', expectedUserId: '2', expectedBindingVersion: '2' }) })
})
it('깨진 상태와 충돌 응답을 성공으로 취급하지 않는다', async () => {
  await expect(createPushDeviceRepository(async () => Response.json({ enabled: true })).current(new AbortController().signal)).rejects.toThrow()
  await expect(createPushDeviceRepository(async () => new Response(null, { status: 409 })).register('fid', '1', '1', new AbortController().signal)).rejects.toThrow()
})
