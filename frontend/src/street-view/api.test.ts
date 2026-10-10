import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getStreetViewConfiguration } from './api'

const initialization = {
  searchPosition: { latitude: 37.561443, longitude: 126.962715 },
  lookAtPosition: { latitude: 37.561443, longitude: 126.962715 },
  tilt: 0,
  fov: 90,
}
const configuration = {
  complexId: 2647, provider: 'NAVER', enabled: true, disabledReason: null, policyRevision: 4, initialization,
}

beforeEach(() => { vi.stubEnv('VITE_API_BASE_URL', '') })
afterEach(() => {
  vi.unstubAllGlobals()
  vi.unstubAllEnvs()
  vi.restoreAllMocks()
  vi.useRealTimers()
})

describe('getStreetViewConfiguration', () => {
  it('현재 실행 정책을 캐시 없이 조회하고 alias를 해석한 단지 ID와 초기 입력을 반환한다', async () => {
    vi.stubEnv('VITE_API_BASE_URL', 'https://api.example.com')
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(Response.json({ data: configuration }))
    vi.stubGlobal('fetch', fetcher)

    expect(await getStreetViewConfiguration(17)).toEqual(configuration)
    expect(fetcher).toHaveBeenCalledWith('https://api.example.com/api/v1/complexes/17/street-view', {
      cache: 'no-store', credentials: 'include', signal: expect.any(AbortSignal),
    })
  })

  it.each(['POLICY_DISABLED', 'INVALID_COORDINATES'])(
    '비활성 사유 %s와 null 초기 입력을 보존한다', async disabledReason => {
      const disabled = { ...configuration, enabled: false, disabledReason, initialization: null }
      vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValue(Response.json({ data: disabled })))
      expect(await getStreetViewConfiguration(2647)).toEqual(disabled)
    },
  )

  it.each([
    null,
    configuration,
    { data: null },
    { data: { ...configuration, complexId: '2647' } },
    { data: { ...configuration, complexId: 0 } },
    { data: { ...configuration, complexId: Number.MAX_SAFE_INTEGER + 1 } },
    { data: { ...configuration, policyRevision: -1 } },
    { data: { ...configuration, policyRevision: 1.5 } },
    { data: { ...configuration, provider: 'OTHER' } },
    { data: { ...configuration, disabledReason: 'POLICY_DISABLED' } },
    { data: { ...configuration, initialization: null } },
    { data: { ...configuration, enabled: false } },
    { data: { ...configuration, enabled: false, initialization: null, disabledReason: null } },
    { data: { ...configuration, initialization: { ...initialization, searchPosition: { latitude: 91, longitude: 127 } } } },
    { data: { ...configuration, initialization: { ...initialization, lookAtPosition: { latitude: 37, longitude: -181 } } } },
    { data: { ...configuration, initialization: { ...initialization, lookAtPosition: { latitude: 0, longitude: 0 } } } },
    { data: { ...configuration, initialization: { ...initialization, tilt: 91 } } },
    { data: { ...configuration, initialization: { ...initialization, fov: 0 } } },
  ])('잘못된 외부 응답을 실행 가능한 설정으로 취급하지 않는다: %j', async body => {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValue(Response.json(body)))
    await expect(getStreetViewConfiguration(2647)).rejects.toThrow('거리뷰 이용 정보를 확인할 수 없습니다.')
  })

  it.each([0, -1, 1.5, Number.NaN])('유효하지 않은 요청 ID %s는 네트워크 요청을 하지 않는다', async complexId => {
    const fetcher = vi.fn<typeof fetch>()
    vi.stubGlobal('fetch', fetcher)
    await expect(getStreetViewConfiguration(complexId)).rejects.toThrow('단지 정보를 확인할 수 없습니다.')
    expect(fetcher).not.toHaveBeenCalled()
  })

  it('HTTP 실패의 상태·코드·추적 ID를 보존한다', async () => {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValue(Response.json({
      code: 'STREET_VIEW_POLICY_UNAVAILABLE', message: '정책을 확인할 수 없습니다.', traceId: 'trace-test',
    }, { status: 503 })))
    await expect(getStreetViewConfiguration(2647)).rejects.toMatchObject({
      name: 'StreetViewApiError', status: 503, code: 'STREET_VIEW_POLICY_UNAVAILABLE', traceId: 'trace-test',
    })
  })

  it('잘못된 HTTP 오류 본문에서도 HTTP 상태를 보존한다', async () => {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValue(new Response('not-json', { status: 502 })))
    await expect(getStreetViewConfiguration(2647)).rejects.toMatchObject({ status: 502, code: null, traceId: null })
  })

  it('네트워크 또는 JSON 해석 실패를 화면에서 안내할 수 있는 오류로 반환한다', async () => {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockRejectedValue(new TypeError('offline')))
    await expect(getStreetViewConfiguration(2647)).rejects.toThrow('거리뷰 이용 정보를 불러오지 못했습니다.')
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValue(new Response('not-json')))
    await expect(getStreetViewConfiguration(2647)).rejects.toThrow('거리뷰 이용 정보를 불러오지 못했습니다.')
  })

  it('10초를 초과한 조회를 취소하고 재시도 가능한 오류를 반환한다', async () => {
    vi.useFakeTimers()
    let requestSignal: AbortSignal | null = null
    vi.stubGlobal('fetch', vi.fn<typeof fetch>((_url, options) => new Promise((_resolve, reject) => {
      requestSignal = options?.signal ?? null
      requestSignal?.addEventListener('abort', () => reject(requestSignal?.reason), { once: true })
    })))
    const result = expect(getStreetViewConfiguration(2647)).rejects.toThrow('확인 시간이 초과되었습니다.')
    await vi.advanceTimersByTimeAsync(10_000)
    await result
    expect(requestSignal).toHaveProperty('aborted', true)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('대상 변경의 요청 취소를 조회 오류로 바꾸지 않는다', async () => {
    const controller = new AbortController()
    const fetcher = vi.fn<typeof fetch>((_url, options) => new Promise((_resolve, reject) => {
      options?.signal?.addEventListener('abort', () => reject(options.signal?.reason), { once: true })
    }))
    vi.stubGlobal('fetch', fetcher)
    const result = expect(getStreetViewConfiguration(2647, controller.signal)).rejects.toMatchObject({ name: 'AbortError' })
    controller.abort()
    await result
    await expect(getStreetViewConfiguration(2647, controller.signal)).rejects.toMatchObject({ name: 'AbortError' })
    expect(fetcher).toHaveBeenCalledTimes(1)
  })
})
