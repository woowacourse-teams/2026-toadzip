import { afterEach, expect, it, vi } from 'vitest'
import { submitFeedback } from './api'

afterEach(() => vi.unstubAllGlobals())
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { 'Content-Type': 'application/json' },
})

it('공개 CSRF 토큰을 얻고 세션 쿠키와 주관식 내용만 제출한다', async () => {
  const fetchMock = vi.fn().mockResolvedValueOnce(json({ token: 'test-csrf', headerName: 'X-XSRF-TOKEN' }))
    .mockResolvedValueOnce(json({ data: { id: 1 } }, 201))
  vi.stubGlobal('fetch', fetchMock)
  await submitFeedback('개선 의견')
  expect(fetchMock).toHaveBeenNthCalledWith(1, expect.stringContaining('/api/auth/csrf'), { credentials: 'include' })
  expect(fetchMock).toHaveBeenNthCalledWith(2, expect.stringContaining('/api/v1/feedback'), {
    method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': 'test-csrf' },
    body: JSON.stringify({ content: '개선 의견' }),
  })
})

it('CSRF 토큰을 확인하지 못하면 제출하지 않는다', async () => {
  const fetchMock = vi.fn().mockResolvedValue(json({ token: 1 }))
  vi.stubGlobal('fetch', fetchMock)
  await expect(submitFeedback('개선 의견')).rejects.toThrow('전송을 준비하지 못했습니다')
  expect(fetchMock).toHaveBeenCalledTimes(1)
})

it('서버 검증 오류의 내용 필드 안내를 표시한다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(json({ token: 'test-csrf', headerName: 'X-XSRF-TOKEN' }))
    .mockResolvedValueOnce(json({ errors: [{ field: 'content', reason: '2,000자 이하로 입력해 주세요.' }] }, 400)))
  await expect(submitFeedback('개선 의견')).rejects.toThrow('2,000자 이하')
})

it('서버 HTML 오류를 접수 성공으로 취급하지 않는다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(json({ token: 'test-csrf', headerName: 'X-XSRF-TOKEN' }))
    .mockResolvedValueOnce(new Response('<html>error</html>', { status: 502 })))
  await expect(submitFeedback('개선 의견')).rejects.toThrow('의견을 보내지 못했습니다')
})

it('잘못된 성공 응답은 접수 확인 실패로 안내한다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(json({ token: 'test-csrf', headerName: 'X-XSRF-TOKEN' }))
    .mockResolvedValueOnce(json({ data: { id: null } }, 201)))
  await expect(submitFeedback('개선 의견')).rejects.toThrow('접수 결과를 확인하지 못했습니다')
})
