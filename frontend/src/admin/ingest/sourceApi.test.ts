import { afterEach, expect, it, vi } from 'vitest'
import { getSourceData } from './sourceApi'

afterEach(() => vi.unstubAllGlobals())

function response(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
}
const row = { id: 1, name: '공고', sourceKey: 'PAN-1',
  sourceUrl: 'https://apis.data.go.kr/catalog', originalUrl: null,
  collectedAt: null, sourceUpdatedAt: null, raw: { PAN_ID: 'PAN-1' } }
const page = { items: [row], page: 0, hasNext: false, totalElements: 1, totalPages: 1 }

it('관리자 세션으로 분류·검색·페이지를 보내고 래핑된 원천 목록을 읽는다', async () => {
  const fetch = vi.fn().mockResolvedValue(response({ data: page }))
  vi.stubGlobal('fetch', fetch)
  const controller = new AbortController()
  expect(await getSourceData('LH_ANNOUNCEMENT_CATALOG', '서울 & 공고', 0, controller.signal)).toEqual(page)
  const [url, options] = fetch.mock.calls[0]
  const params = new URL(String(url)).searchParams
  expect(params.get('category')).toBe('LH_ANNOUNCEMENT_CATALOG')
  expect(params.get('keyword')).toBe('서울 & 공고')
  expect(params.get('size')).toBe('20')
  expect(options).toMatchObject({ credentials: 'include', signal: controller.signal })
})

it.each([
  { ...page, items: [{ ...row, raw: '문자열' }] },
  { ...page, items: [{ ...row, collectedAt: '잘못된 날짜' }] },
  { ...page, totalElements: -1 },
])('잘못된 응답을 빈 목록으로 위장하지 않는다', async value => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ data: value })))
  await expect(getSourceData('MYHOME_COMPLEX', '', 0)).rejects.toThrow('목록 응답이 올바르지 않습니다.')
})

it('권한 오류 메시지를 그대로 전달한다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ message: '관리자 로그인이 필요합니다.' }, 401)))
  await expect(getSourceData('MYHOME_COMPLEX', '', 0)).rejects.toThrow('관리자 로그인이 필요합니다.')
})
