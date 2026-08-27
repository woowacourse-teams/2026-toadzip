import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const SCRIPT_SELECTOR = 'script[data-toadzip-naver-maps-sdk]'

function createFakeMaps(): typeof naver.maps {
  class FakeMap {}
  class FakeLatLng {}

  return {
    Map: FakeMap,
    LatLng: FakeLatLng,
  } as unknown as typeof naver.maps
}

function installFakeMaps(): typeof naver.maps {
  const maps = createFakeMaps()
  Reflect.set(window, 'naver', { maps })
  return maps
}

function getSdkScript(): HTMLScriptElement {
  const script = document.querySelector<HTMLScriptElement>(SCRIPT_SELECTOR)

  if (!script) {
    throw new Error('NAVER Maps SDK 스크립트를 찾을 수 없습니다.')
  }

  return script
}

beforeEach(() => {
  vi.resetModules()
  document.querySelectorAll(SCRIPT_SELECTOR).forEach((script) => script.remove())
  Reflect.deleteProperty(window, 'naver')
  delete window.__toadzipNaverMapsReady
  delete window.navermap_authFailure
})

afterEach(() => {
  vi.useRealTimers()
  document.querySelectorAll(SCRIPT_SELECTOR).forEach((script) => script.remove())
  Reflect.deleteProperty(window, 'naver')
  delete window.__toadzipNaverMapsReady
  delete window.navermap_authFailure
})

describe('loadNaverMapsSdk', () => {
  it('동시 요청에서 GL SDK 스크립트를 한 번만 추가한다', async () => {
    const { loadNaverMapsSdk } = await import('./loadNaverMapsSdk.ts')

    const firstLoad = loadNaverMapsSdk('sample client id')
    const secondLoad = loadNaverMapsSdk('sample client id')
    const script = getSdkScript()
    const scriptUrl = new URL(script.src)

    expect(firstLoad).toBe(secondLoad)
    expect(document.querySelectorAll(SCRIPT_SELECTOR)).toHaveLength(1)
    expect(scriptUrl.origin).toBe('https://oapi.map.naver.com')
    expect(scriptUrl.pathname).toBe('/openapi/v3/maps.js')
    expect(scriptUrl.searchParams.get('ncpKeyId')).toBe('sample client id')
    expect(scriptUrl.searchParams.get('submodules')).toBe('gl')
    expect(scriptUrl.searchParams.get('callback')).toBe(
      '__toadzipNaverMapsReady',
    )

    const maps = installFakeMaps()
    window.__toadzipNaverMapsReady?.()

    await expect(firstLoad).resolves.toBe(maps)
    await expect(secondLoad).resolves.toBe(maps)
  })

  it('이미 준비된 SDK가 있으면 스크립트를 추가하지 않는다', async () => {
    const maps = installFakeMaps()
    const { loadNaverMapsSdk } = await import('./loadNaverMapsSdk.ts')

    await expect(loadNaverMapsSdk('sample-client-id')).resolves.toBe(maps)
    expect(document.querySelector(SCRIPT_SELECTOR)).not.toBeInTheDocument()
  })

  it('인증 실패를 비재시도 오류로 유지한다', async () => {
    const { loadNaverMapsSdk, subscribeToNaverMapsAuthenticationFailure } =
      await import('./loadNaverMapsSdk.ts')
    const authenticationFailureListener = vi.fn()
    subscribeToNaverMapsAuthenticationFailure(authenticationFailureListener)
    const firstLoad = loadNaverMapsSdk('invalid-client-id')
    installFakeMaps()
    window.__toadzipNaverMapsReady?.()
    delete window.navermap_authFailure
    await Promise.resolve()

    await expect(firstLoad).resolves.toBeDefined()
    const authenticationFailureCallback: unknown = Reflect.get(
      window,
      'navermap_authFailure',
    )
    expect(authenticationFailureCallback).toBeTypeOf('function')

    if (typeof authenticationFailureCallback !== 'function') {
      throw new Error('NAVER Maps 인증 실패 callback을 찾을 수 없습니다.')
    }

    authenticationFailureCallback()

    expect(authenticationFailureListener).toHaveBeenCalledWith(
      expect.objectContaining({ code: 'authentication' }),
    )
    await expect(loadNaverMapsSdk('invalid-client-id')).rejects.toMatchObject({
      code: 'authentication',
    })
    expect(document.querySelectorAll(SCRIPT_SELECTOR)).toHaveLength(1)
  })

  it('script load 이벤트만으로는 GL SDK 준비가 완료되지 않는다', async () => {
    const { loadNaverMapsSdk } = await import('./loadNaverMapsSdk.ts')
    const sdkLoad = loadNaverMapsSdk('sample-client-id')
    const script = getSdkScript()
    const loadSettled = vi.fn()
    sdkLoad.then(loadSettled, loadSettled)
    installFakeMaps()

    script.dispatchEvent(new Event('load'))
    await Promise.resolve()

    expect(loadSettled).not.toHaveBeenCalled()

    window.__toadzipNaverMapsReady?.()

    await expect(sdkLoad).resolves.toBeDefined()
  })

  it('네트워크 실패 스크립트를 제거하고 다음 호출에서 다시 시도한다', async () => {
    const { loadNaverMapsSdk } = await import('./loadNaverMapsSdk.ts')
    const firstLoad = loadNaverMapsSdk('sample-client-id')
    const failedScript = getSdkScript()

    failedScript.dispatchEvent(new Event('error'))

    await expect(firstLoad).rejects.toMatchObject({ code: 'network' })
    expect(failedScript.isConnected).toBe(false)

    const retryLoad = loadNaverMapsSdk('sample-client-id')
    const retryScript = getSdkScript()

    expect(retryScript).not.toBe(failedScript)

    const maps = installFakeMaps()
    window.__toadzipNaverMapsReady?.()

    await expect(retryLoad).resolves.toBe(maps)
  })

  it('준비 callback 이후 SDK가 없으면 재시도 가능한 오류로 처리한다', async () => {
    const { loadNaverMapsSdk } = await import('./loadNaverMapsSdk.ts')
    const sdkLoad = loadNaverMapsSdk('sample-client-id')
    const failedScript = getSdkScript()

    window.__toadzipNaverMapsReady?.()

    await expect(sdkLoad).rejects.toMatchObject({ code: 'invalid-sdk' })
    expect(failedScript.isConnected).toBe(false)
    expect(document.querySelector(SCRIPT_SELECTOR)).not.toBeInTheDocument()
  })

  it('준비 callback이 오지 않으면 대기를 끝내고 재시도를 허용한다', async () => {
    vi.useFakeTimers()
    const { loadNaverMapsSdk } = await import('./loadNaverMapsSdk.ts')
    const sdkLoad = loadNaverMapsSdk('sample-client-id')
    const failedScript = getSdkScript()
    const rejection = expect(sdkLoad).rejects.toMatchObject({ code: 'network' })

    await vi.advanceTimersByTimeAsync(15_000)

    await rejection
    expect(failedScript.isConnected).toBe(false)

    const retryLoad = loadNaverMapsSdk('sample-client-id')

    expect(getSdkScript()).not.toBe(failedScript)

    const maps = installFakeMaps()
    window.__toadzipNaverMapsReady?.()

    await expect(retryLoad).resolves.toBe(maps)
  })
})
