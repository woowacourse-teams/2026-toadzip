import { afterEach, expect, it, vi } from 'vitest'
import { requestGuestCancellation, verifyGuestCancellation } from './guestCancellationApi'

afterEach(() => vi.unstubAllGlobals())
function respond(status: number) {
  vi.stubGlobal('fetch', vi.fn()
    .mockResolvedValueOnce(new Response(JSON.stringify({ token: 'test', headerName: 'X-CSRF-TOKEN' })))
    .mockResolvedValueOnce(new Response(null, { status })))
}
it('요청 접수는 202만 인정하고 발송 완료로 해석하지 않는다', async () => {
  respond(202)
  await expect(requestGuestCancellation('sample@example.com')).resolves.toBeUndefined()
  respond(200)
  await expect(requestGuestCancellation('sample@example.com')).rejects.toThrow('취소 요청')
})
it('일괄 취소의 실제 완료는 204일 때만 인정한다', async () => {
  respond(204)
  await expect(verifyGuestCancellation('sample@example.com', 'code')).resolves.toBeUndefined()
  respond(202)
  await expect(verifyGuestCancellation('sample@example.com', 'code')).rejects.toThrow('취소를 완료')
})
