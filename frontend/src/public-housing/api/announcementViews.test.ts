import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { recordAnnouncementView } from './announcementViews.ts'

const VIEWER = 'abcdefab-cdef-4abc-8def-abcdefabcdef'
const STORAGE_KEY = 'toadzip.announcement-viewer'

beforeEach(() => {
  consentGate.allowed = true
  consentGate.stops = []
  localStorage.clear()
  // The browser boundary serializes same-origin tabs; exercise real storage and requests inside it.
  let queue: Promise<unknown> = Promise.resolve()
  vi.stubGlobal('navigator', {
    locks: {
      request: (_name: string, _options: unknown, callback: () => unknown) => {
        const result = queue.then(callback)
        queue = result.catch(() => undefined)
        return result
      },
    },
  })
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  localStorage.clear()
})

function requests() {
  return vi.fn<typeof fetch>(async (_url, init) => {
    if (init?.method === 'POST') {
      return Response.json({ data: { viewCount: 8 } })
    }
    return Response.json({ headerName: 'X-XSRF-TOKEN', token: 'test-csrf' })
  })
}

describe('recordAnnouncementView', () => {
  it('재방문과 동시에 열린 탭에 같은 영구 식별자를 보내고 서버 조회수를 반환한다', async () => {
    const fetcher = requests()
    const values = await Promise.all(Array.from({ length: 3 }, () => (
      recordAnnouncementView('', fetcher, '12', new AbortController().signal)
    )))
    const stored = localStorage.getItem(STORAGE_KEY)
    expect(stored).toMatch(/^[a-f0-9-]{36}$/)
    expect(values).toEqual([8, 8, 8])
    const posts = fetcher.mock.calls.filter(([, init]) => init?.method === 'POST')
    expect(posts).toHaveLength(3)
    for (const [url, init] of posts) {
      expect(url).toBe('/api/v1/announcements/12/views')
      expect(init).toMatchObject({
        credentials: 'include',
        headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': 'test-csrf' },
        body: JSON.stringify({ viewerId: stored }),
      })
    }
    await recordAnnouncementView('', fetcher, '12', new AbortController().signal)
    expect(localStorage.getItem(STORAGE_KEY)).toBe(stored)
  })

  it('저장된 브라우저 식별자는 다른 공고에서도 유지한다', async () => {
    localStorage.setItem(STORAGE_KEY, VIEWER)
    const fetcher = requests()
    await recordAnnouncementView('https://api.example.com', fetcher, '15', new AbortController().signal)
    expect(fetcher.mock.calls.at(-1)).toMatchObject([
      'https://api.example.com/api/v1/announcements/15/views',
      { body: JSON.stringify({ viewerId: VIEWER }) },
    ])
  })

  it('저장소가 차단되면 임시 식별자로 집계하지 않는다', async () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked') })
    const fetcher = requests()
    expect(await recordAnnouncementView('', fetcher, '12', new AbortController().signal)).toBeNull()
    expect(fetcher).not.toHaveBeenCalled()
  })

  it('탭 간 잠금을 지원하지 않으면 새 식별자를 만들어 집계하지 않는다', async () => {
    vi.stubGlobal('navigator', {})
    const fetcher = requests()
    expect(await recordAnnouncementView('', fetcher, '12', new AbortController().signal)).toBeNull()
    expect(fetcher).not.toHaveBeenCalled()
  })

  it('취소된 상세 요청은 집계 요청을 보내지 않는다', async () => {
    const controller = new AbortController()
    controller.abort()
    const fetcher = requests()
    await expect(recordAnnouncementView('', fetcher, '12', controller.signal)).rejects.toMatchObject({ name: 'AbortError' })
    expect(fetcher).not.toHaveBeenCalled()
  })

  it('CSRF 준비가 실패하면 기록 요청을 보내지 않는다', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(null, { status: 503 }))
    expect(await recordAnnouncementView('', fetcher, '12', new AbortController().signal)).toBeNull()
    expect(fetcher).toHaveBeenCalledTimes(1)
  })

  it.each([null, { data: { viewCount: -1 } }, { data: { viewCount: '8' } }, { data: { viewCount: 1.2 } }])(
    '잘못된 기록 응답은 숫자로 추정하지 않는다: %j', async (body) => {
      const fetcher = vi.fn<typeof fetch>()
        .mockResolvedValueOnce(Response.json({ headerName: 'X-XSRF-TOKEN', token: 'test-csrf' }))
        .mockResolvedValueOnce(Response.json(body))
      expect(await recordAnnouncementView('', fetcher, '12', new AbortController().signal)).toBeNull()
    },
  )

  it('기록 요청 실패가 상세 데이터 조회까지 실패시키지 않도록 null을 반환한다', async () => {
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(Response.json({ headerName: 'X-XSRF-TOKEN', token: 'test-csrf' }))
      .mockRejectedValueOnce(new TypeError('offline'))
    expect(await recordAnnouncementView('', fetcher, '12', new AbortController().signal)).toBeNull()
  })
})


const consentGate = vi.hoisted(() => ({ allowed: true, stops: [] as Array<() => void> }))
vi.mock('../../privacy/consentStore', () => ({ analyticsCollectionAllowed: () => consentGate.allowed, consentStore: { onStop: (callback: () => void) => { consentGate.stops.push(callback); return () => {} } } }))

it('동의 없이는 viewer ID나 CSRF 요청을 만들지 않는다', async () => {
  consentGate.allowed = false
  const fetcher = requests()
  expect(await recordAnnouncementView('', fetcher, '12', new AbortController().signal)).toBeNull()
  expect(localStorage.getItem(STORAGE_KEY)).toBeNull()
  expect(fetcher).not.toHaveBeenCalled()
})
