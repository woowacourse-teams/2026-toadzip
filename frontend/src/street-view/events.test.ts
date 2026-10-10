import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createStreetViewAttempt, reportStreetViewEvent } from './events'
import type { StreetViewEvent, StreetViewFailureReason, StreetViewPhase } from './types'

const started: StreetViewEvent = {
  attemptId: 'abcdefab-cdef-4abc-8def-abcdefabcdef', complexId: 2647, policyRevision: 4,
  type: 'STARTED', phase: 'DOCUMENT', reasonCode: null, durationMs: 0,
}

beforeEach(() => { vi.stubEnv('VITE_API_BASE_URL', 'https://api.example.com') })
afterEach(() => {
  vi.unstubAllGlobals()
  vi.unstubAllEnvs()
  vi.restoreAllMocks()
  vi.useRealTimers()
})

describe('reportStreetViewEvent', () => {
  it('서버가 제공한 CSRF 헤더와 쿠키를 사용하고 빈 204 응답을 JSON으로 읽지 않는다', async () => {
    const accepted = new Response(null, { status: 204 })
    const parse = vi.spyOn(accepted, 'json')
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(Response.json({ token: 'test-csrf', headerName: 'X-TEST-CSRF' }))
      .mockResolvedValueOnce(accepted)
    vi.stubGlobal('fetch', fetcher)
    await expect(reportStreetViewEvent(started)).resolves.toBeUndefined()
    expect(fetcher.mock.calls[0]).toMatchObject([
      'https://api.example.com/api/auth/csrf', { credentials: 'include', cache: 'no-store' },
    ])
    expect(fetcher.mock.calls[1]).toMatchObject([
      'https://api.example.com/api/v1/street-view/events', {
        method: 'POST', credentials: 'include', cache: 'no-store',
        headers: { 'Content-Type': 'application/json', 'X-TEST-CSRF': 'test-csrf' },
        body: JSON.stringify(started),
      },
    ])
    expect(parse).not.toHaveBeenCalled()
  })

  it('동시 보고는 준비 중인 CSRF 요청만 공유하고 이후에는 토큰을 다시 준비한다', async () => {
    const fetcher = vi.fn<typeof fetch>(async (_url, options) => {
      if (options?.method === 'POST') return new Response(null, { status: 204 })
      return Response.json({ token: 'test-csrf', headerName: 'X-XSRF-TOKEN' })
    })
    vi.stubGlobal('fetch', fetcher)
    const ready: StreetViewEvent = { ...started, type: 'READY', phase: 'PANORAMA', durationMs: 20 }
    await Promise.all([reportStreetViewEvent(started), reportStreetViewEvent(ready)])
    expect(fetcher.mock.calls.filter(([, options]) => options?.method !== 'POST')).toHaveLength(1)
    expect(fetcher.mock.calls.filter(([, options]) => options?.method === 'POST')).toHaveLength(2)
    await reportStreetViewEvent(started)
    expect(fetcher.mock.calls.filter(([, options]) => options?.method !== 'POST')).toHaveLength(2)
  })

  it.each([
    null,
    { data: { token: 'test-csrf', headerName: 'X-XSRF-TOKEN' } },
    { token: '', headerName: 'X-XSRF-TOKEN' },
    { token: 'test-csrf', headerName: '' },
    { token: 'test-csrf', headerName: 'bad\nheader' },
    { token: 'test-csrf', headerName: 12 },
  ])('잘못된 CSRF 응답에는 이벤트를 보내지 않는다: %j', async body => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(Response.json(body))
    vi.stubGlobal('fetch', fetcher)
    await expect(reportStreetViewEvent(started)).resolves.toBeUndefined()
    expect(fetcher).toHaveBeenCalledTimes(1)
  })

  it.each([401, 403, 429, 503])('CSRF HTTP %s 실패는 재전송하거나 화면에 전파하지 않는다', async status => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(null, { status }))
    vi.stubGlobal('fetch', fetcher)
    await expect(reportStreetViewEvent(started)).resolves.toBeUndefined()
    expect(fetcher).toHaveBeenCalledTimes(1)
  })

  it.each([200, 403, 409, 429, 503])('이벤트 HTTP %s 응답은 본문 파싱·재전송 없이 종료한다', async status => {
    const response = new Response(null, { status })
    const parse = vi.spyOn(response, 'json')
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(Response.json({ token: 'test-csrf', headerName: 'X-XSRF-TOKEN' }))
      .mockResolvedValueOnce(response)
    vi.stubGlobal('fetch', fetcher)
    await expect(reportStreetViewEvent(started)).resolves.toBeUndefined()
    expect(fetcher).toHaveBeenCalledTimes(2)
    expect(parse).not.toHaveBeenCalled()
  })

  it('네트워크 실패를 호출자에게 전파하지 않는다', async () => {
    const fetcher = vi.fn<typeof fetch>().mockRejectedValue(new TypeError('offline'))
    vi.stubGlobal('fetch', fetcher)
    await expect(reportStreetViewEvent(started)).resolves.toBeUndefined()
    expect(fetcher).toHaveBeenCalledTimes(1)
  })

  it('CSRF 준비를 포함한 전송 전체를 3초 안으로 제한한다', async () => {
    vi.useFakeTimers()
    const fetcher = vi.fn<typeof fetch>((_url, options) => new Promise((resolve, reject) => {
      options?.signal?.addEventListener('abort', () => reject(options.signal?.reason), { once: true })
      if (options?.method !== 'POST') {
        window.setTimeout(() => resolve(Response.json({ token: 'test-csrf', headerName: 'X-XSRF-TOKEN' })), 2000)
      }
    }))
    vi.stubGlobal('fetch', fetcher)
    const result = reportStreetViewEvent(started)
    await vi.advanceTimersByTimeAsync(2_000)
    expect(fetcher).toHaveBeenCalledTimes(2)
    const postSignal = fetcher.mock.calls[1][1]?.signal
    expect(postSignal?.aborted).toBe(false)
    await vi.advanceTimersByTimeAsync(1_000)
    await expect(result).resolves.toBeUndefined()
    expect(postSignal?.aborted).toBe(true)
    expect(fetcher).toHaveBeenCalledTimes(2)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('CSRF 준비 자체가 지연되면 취소하고 이벤트 요청을 만들지 않는다', async () => {
    vi.useFakeTimers()
    const fetcher = vi.fn<typeof fetch>((_url, options) => new Promise((_resolve, reject) => {
      options?.signal?.addEventListener('abort', () => reject(options.signal?.reason), { once: true })
    }))
    vi.stubGlobal('fetch', fetcher)
    const results = [reportStreetViewEvent(started), reportStreetViewEvent(started)]
    await vi.advanceTimersByTimeAsync(3_000)
    await Promise.all(results)
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(vi.getTimerCount()).toBe(0)
  })
})

describe('createStreetViewAttempt', () => {
  it('명시적인 시작을 한 번 보고하고 재등록이나 중복 종료가 최초 결과를 바꾸지 않는다', () => {
    const report = vi.fn<(event: StreetViewEvent) => void>()
    const attempt = createStreetViewAttempt({ complexId: 2647, policyRevision: 4 }, report)
    expect(report).not.toHaveBeenCalled()
    attempt.start()
    attempt.start()
    attempt.setPhase('SDK')
    attempt.setPhase('PANORAMA')
    attempt.ready()
    attempt.ready()
    attempt.fail('PANORAMA_QUERY_FAILED')
    attempt.cancel('USER_CLOSED')
    expect(report).toHaveBeenCalledTimes(2)
    expect(report.mock.calls[0][0]).toEqual({ ...started, attemptId: attempt.id })
    expect(report.mock.calls[1][0]).toMatchObject({
      attemptId: attempt.id, complexId: 2647, policyRevision: 4, type: 'READY', phase: 'PANORAMA', reasonCode: null,
    })
    expect(attempt.terminal).toBe(true)
  })

  it('시작 전의 종료 호출이나 등록 해제 자체를 사용자 취소로 보고하지 않는다', () => {
    const report = vi.fn<(event: StreetViewEvent) => void>()
    const attempt = createStreetViewAttempt({ complexId: 2647, policyRevision: 4 }, report)
    attempt.cancel('USER_CLOSED')
    expect(report).not.toHaveBeenCalled()
    expect(attempt.terminal).toBe(false)
    attempt.start()
    expect(report).toHaveBeenCalledTimes(1)
    expect(attempt.terminal).toBe(false)
  })

  it('단계가 이전으로 돌아가거나 종료 이후 바뀌지 않는다', () => {
    const attempt = createStreetViewAttempt({ complexId: 2647, policyRevision: 4 }, vi.fn())
    attempt.start()
    attempt.setPhase('SDK')
    attempt.setPhase('DOCUMENT')
    expect(attempt.phase).toBe('SDK')
    attempt.cancel('TARGET_CHANGED')
    attempt.setPhase('PANORAMA')
    expect(attempt.phase).toBe('SDK')
  })

  it.each(['USER_CLOSED', 'TARGET_CHANGED'] as const)('초기화 중 %s만 현재 단계의 취소로 보고한다', reasonCode => {
    const report = vi.fn<(event: StreetViewEvent) => void>()
    const attempt = createStreetViewAttempt({ complexId: 2647, policyRevision: 4 }, report)
    attempt.start()
    attempt.setPhase('SDK')
    attempt.cancel(reasonCode)
    expect(report.mock.calls[1][0]).toMatchObject({ type: 'CANCELLED', phase: 'SDK', reasonCode })
  })

  it.each<[StreetViewFailureReason, StreetViewPhase]>([
    ['DOCUMENT_TIMEOUT', 'DOCUMENT'], ['SDK_LOAD_FAILED', 'SDK'], ['SDK_AUTH_FAILED', 'SDK'],
    ['SDK_UNAVAILABLE', 'SDK'], ['PANORAMA_QUERY_FAILED', 'PANORAMA'], ['INITIALIZATION_TIMEOUT', 'SDK'],
  ])('실패 사유 %s를 BE 계약의 %s 단계로 보고한다', (reasonCode, phase) => {
    const report = vi.fn<(event: StreetViewEvent) => void>()
    const attempt = createStreetViewAttempt({ complexId: 2647, policyRevision: 4 }, report)
    attempt.start()
    attempt.setPhase('SDK')
    attempt.fail(reasonCode)
    expect(report.mock.calls[1][0]).toMatchObject({ type: 'FAILED', phase, reasonCode })
  })

  it.each([[-10, 0], [12.9, 12], [900_000, 600_000]])('경과 시간 %s는 BE 계약 범위의 정수 %s로 보고한다', (duration, expected) => {
    const clock = vi.spyOn(performance, 'now').mockReturnValue(1_000)
    const report = vi.fn<(event: StreetViewEvent) => void>()
    const attempt = createStreetViewAttempt({ complexId: 2647, policyRevision: 4 }, report)
    attempt.start()
    clock.mockReturnValue(1_000 + duration)
    attempt.cancel('USER_CLOSED')
    expect(report.mock.calls[1][0].durationMs).toBe(expected)
  })

  it('재시도는 새 ID로 시작하며 이미 만든 시도의 단지와 정책 버전은 변하지 않는다', () => {
    const report = vi.fn<(event: StreetViewEvent) => void>()
    const configuration = { complexId: 2647, policyRevision: 4 }
    const first = createStreetViewAttempt(configuration, report)
    configuration.complexId = 33
    configuration.policyRevision = 5
    first.start()
    first.cancel('TARGET_CHANGED')
    const retry = createStreetViewAttempt(configuration, report)
    retry.start()
    expect(retry.id).not.toBe(first.id)
    expect(report.mock.calls[1][0]).toMatchObject({ complexId: 2647, policyRevision: 4 })
    expect(report.mock.calls[2][0]).toMatchObject({ complexId: 33, policyRevision: 5 })
  })
})
