import { afterEach, expect, it, vi } from 'vitest'
import { formatLongTime, formatShortTime, getFeedbackChanges, getFeedbackCounts, listFeedback } from './api'

afterEach(() => vi.unstubAllGlobals())
const valid = { items: [{ version: 0, status: 'OPEN', note: '', relatedResource: '', relatedId: null, relatedName: null, actor: null, processedAt: null, id: 1, content: '개선 의견', createdAt: '2026-10-07T01:00:00Z' }], page: 0, hasNext: false, totalElements: 1, totalPages: 1 }
const respond = (data: unknown) => vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ data }))))

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
  respond(value)
  await expect(listFeedback(new URLSearchParams())).rejects.toThrow('올바르지 않습니다')
})

it('이전 서버가 보낸 설문 답변은 읽지 않고 의견 항목만 남긴다', async () => {
  respond({ ...valid, items: [{ ...valid.items[0], surveyAnswer: 'NOT_DISAPPOINTED' }] })
  const result = await listFeedback(new URLSearchParams())
  expect(result.items[0]).toEqual(valid.items[0])
  expect(result.items[0]).not.toHaveProperty('surveyAnswer')
})

it('대상 이름을 내려주지 않는 이전 서버 응답도 이름 없음으로 읽는다', async () => {
  const { relatedName: _omitted, ...legacy } = { ...valid.items[0], relatedResource: 'COMPLEX', relatedId: 3 }
  respond({ ...valid, items: [legacy] })
  expect((await listFeedback(new URLSearchParams())).items[0]).toMatchObject({ relatedId: 3, relatedName: null })
})

it.each([1, {}])('대상 이름이 문자열이 아니면 거절한다: %j', async relatedName => {
  respond({ ...valid, items: [{ ...valid.items[0], relatedName }] })
  await expect(listFeedback(new URLSearchParams())).rejects.toThrow('올바르지 않습니다')
})

it('상태별 건수를 검색어와 함께 요청해 읽는다', async () => {
  const fetchMock = vi.fn(async (_url: string) => new Response(JSON.stringify({ data: { OPEN: 3, IN_PROGRESS: 2, ON_HOLD: 1, DONE: 0, total: 6, extra: 1 } })))
  vi.stubGlobal('fetch', fetchMock)
  expect(await getFeedbackCounts('개선')).toEqual({ OPEN: 3, IN_PROGRESS: 2, ON_HOLD: 1, DONE: 0, total: 6 })
  expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('/api/admin/feedback/counts?keyword=%EA%B0%9C%EC%84%A0'), expect.anything())
  await getFeedbackCounts('')
  expect(String(fetchMock.mock.lastCall![0])).toMatch(/\/api\/admin\/feedback\/counts\?$/)
})

it.each([{ OPEN: 1, IN_PROGRESS: 1, ON_HOLD: 1, DONE: 1 }, { OPEN: -1, IN_PROGRESS: 0, ON_HOLD: 0, DONE: 0, total: 0 },
  { OPEN: '3', IN_PROGRESS: 0, ON_HOLD: 0, DONE: 0, total: 3 }, { OPEN: 1.5, IN_PROGRESS: 0, ON_HOLD: 0, DONE: 0, total: 2 },
  { OPEN: 0, IN_PROGRESS: 0, ON_HOLD: 0, DONE: 0, total: null }, [], null])
('잘못된 건수 응답을 거절한다', async value => {
  respond(value)
  await expect(getFeedbackCounts('')).rejects.toThrow('올바르지 않습니다')
})

it('처리 이력을 읽고 형식이 틀리면 거절한다', async () => {
  const change = { id: 5, action: 'PROCESS_FEEDBACK', actor: 'operator', occurredAt: '2026-10-19T01:00:00Z', beforeValue: '{}', afterValue: '{}' }
  respond([change])
  expect(await getFeedbackChanges(1)).toEqual([change])
  respond([{ ...change, actor: null }])
  await expect(getFeedbackChanges(1)).rejects.toThrow('올바르지 않습니다')
  respond({ items: [] })
  await expect(getFeedbackChanges(1)).rejects.toThrow('올바르지 않습니다')
})

it('시각은 한국 시각으로 쓴다', () => {
  expect(formatShortTime('2026-12-31T15:05:00Z')).toBe('01.01 00:05')
  expect(formatLongTime('2026-12-31T15:05:00Z')).toBe('2027.01.01 00:05')
})
