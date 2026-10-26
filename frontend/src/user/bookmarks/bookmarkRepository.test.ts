import { expect, it, vi } from 'vitest'
import { createBookmarkRepository } from './bookmarkRepository'
it('북마크 응답을 검증하고 세션 만료를 구분한다', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(Response.json({ items: [{ type: 'REGION' }] }))
    .mockResolvedValueOnce(new Response(null, { status: 401 }))
  const repository = createBookmarkRepository(fetcher)
  await expect(repository.list()).rejects.toThrow('응답')
  await expect(repository.list()).rejects.toThrow('로그인')
})
it('쿠키와 CSRF를 포함해 저장과 해제를 요청한다', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(Response.json({ token: 'csrf', headerName: 'X-XSRF-TOKEN' }))
    .mockResolvedValueOnce(new Response(null, { status: 204 }))
  await createBookmarkRepository(fetcher).save('COMPLEX', '15')
  expect(fetcher).toHaveBeenLastCalledWith(expect.stringContaining('/bookmarks/me/COMPLEX/15'), expect.objectContaining({ method: 'PUT', credentials: 'include', headers: { 'X-XSRF-TOKEN': 'csrf' } }))
})
