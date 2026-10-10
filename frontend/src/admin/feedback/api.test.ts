import { afterEach, expect, it, vi } from 'vitest'
import { listFeedback } from './api'

afterEach(() => vi.unstubAllGlobals())
const valid = { items: [{ id: 1, content: '개선 의견', createdAt: '2026-10-07T01:00:00Z' }], page: 0, hasNext: false, totalElements: 1, totalPages: 1 }

it('인증된 관리자 목록을 요청하며 검색과 취소 신호를 전달한다', async () => {
  const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ data: valid })))
  vi.stubGlobal('fetch', fetchMock)
  const signal = new AbortController().signal
  const params = new URLSearchParams({ keyword: '개선', page: '0', size: '20' })
  expect(await listFeedback(params, signal)).toEqual(valid)
  expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining(`/api/admin/feedback?${params}`),
    expect.objectContaining({ credentials: 'include', signal }))
})

it.each([{ ...valid, items: [{ id: 1, content: null, createdAt: '2026-10-07T01:00:00Z' }] },
  { ...valid, items: [{ id: 1, content: '의견', createdAt: 'invalid' }] }, { ...valid, totalPages: -1 }])
('잘못된 외부 응답을 거절한다', async value => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ data: value }))))
  await expect(listFeedback(new URLSearchParams())).rejects.toThrow('올바르지 않습니다')
})
