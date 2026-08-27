import { StrictMode } from 'react'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import NaverMap from './NaverMap.tsx'
import {
  loadNaverMapsSdk,
  NaverMapsSdkError,
  subscribeToNaverMapsAuthenticationFailure,
} from './loadNaverMapsSdk.ts'

vi.mock('./loadNaverMapsSdk.ts', async () => {
  const actual = await vi.importActual<
    typeof import('./loadNaverMapsSdk.ts')
  >('./loadNaverMapsSdk.ts')

  return {
    ...actual,
    loadNaverMapsSdk: vi.fn(),
    subscribeToNaverMapsAuthenticationFailure: vi.fn(),
  }
})

interface FakeSdk {
  autoResizeMap: ReturnType<typeof vi.fn>
  destroyMap: ReturnType<typeof vi.fn>
  latLngConstructor: ReturnType<typeof vi.fn>
  mapConstructor: ReturnType<typeof vi.fn>
  maps: typeof naver.maps
}

function createFakeSdk(): FakeSdk {
  const destroyMap = vi.fn()
  const autoResizeMap = vi.fn()
  const mapInstance = {
    autoResize: autoResizeMap,
    destroy: destroyMap,
  }
  const mapConstructor = vi.fn(function FakeMapConstructor(
    _element: string | HTMLElement,
    _options?: naver.maps.MapOptions,
  ) {
    return mapInstance
  })
  const latLngConstructor = vi.fn(function FakeLatLngConstructor(
    latitude: number,
    longitude: number,
  ) {
    return { latitude, longitude }
  })

  return {
    autoResizeMap,
    destroyMap,
    latLngConstructor,
    mapConstructor,
    maps: {
      LatLng: latLngConstructor,
      Map: mapConstructor,
    } as unknown as typeof naver.maps,
  }
}

function createDeferred<T>() {
  let resolvePromise: (value: T) => void = () => {
    throw new Error('Promise resolve 함수가 준비되지 않았습니다.')
  }

  const promise = new Promise<T>((resolve) => {
    resolvePromise = resolve
  })

  return { promise, resolve: resolvePromise }
}

const loadNaverMapsSdkMock = vi.mocked(loadNaverMapsSdk)
const subscribeToAuthenticationFailureMock = vi.mocked(
  subscribeToNaverMapsAuthenticationFailure,
)
let authenticationFailureListener:
  | ((error: NaverMapsSdkError) => void)
  | null = null
const unsubscribeAuthenticationFailure = vi.fn()

beforeEach(() => {
  loadNaverMapsSdkMock.mockReset()
  subscribeToAuthenticationFailureMock.mockReset()
  unsubscribeAuthenticationFailure.mockReset()
  authenticationFailureListener = null
  subscribeToAuthenticationFailureMock.mockImplementation((listener) => {
    authenticationFailureListener = listener
    return unsubscribeAuthenticationFailure
  })
  vi.stubEnv('VITE_NAVER_MAPS_CLIENT_ID', 'sample-client-id')
})

afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
})

describe('NaverMap', () => {
  it('Client ID가 없으면 SDK를 요청하지 않고 설정 안내를 표시한다', async () => {
    vi.stubEnv('VITE_NAVER_MAPS_CLIENT_ID', '   ')

    render(<NaverMap />)

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '지도 설정이 준비되지 않았습니다.',
    )
    expect(loadNaverMapsSdkMock).not.toHaveBeenCalled()
    expect(
      screen.queryByRole('button', { name: '다시 시도' }),
    ).not.toBeInTheDocument()
  })

  it('SDK가 준비되는 동안 로딩 상태를 표시한다', () => {
    const deferred = createDeferred<typeof naver.maps>()
    loadNaverMapsSdkMock.mockReturnValue(deferred.promise)

    render(<NaverMap />)

    expect(screen.getByRole('status')).toHaveTextContent(
      '지도를 불러오고 있습니다.',
    )
    expect(
      screen.getByRole('region', { name: '공공임대주택 지도' }),
    ).toHaveAttribute('aria-busy', 'true')
  })

  it('GL 지도 옵션으로 초기화하고 unmount에서 지도 인스턴스를 해제한다', async () => {
    const fakeSdk = createFakeSdk()
    const observe = vi.fn()
    const disconnect = vi.fn()
    let resizeCallback: ResizeObserverCallback = () => undefined

    class FakeResizeObserver {
      constructor(callback: ResizeObserverCallback) {
        resizeCallback = callback
      }

      observe = observe
      disconnect = disconnect
    }

    vi.stubGlobal('ResizeObserver', FakeResizeObserver)
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)

    const { unmount } = render(<NaverMap />)

    await waitFor(() => expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce())

    expect(fakeSdk.latLngConstructor).toHaveBeenCalledWith(
      37.5666103,
      126.9783882,
    )
    expect(fakeSdk.mapConstructor.mock.calls[0]?.[1]).toEqual(
      expect.objectContaining({
        gl: true,
        keyboardShortcuts: true,
        zoom: 14,
        zoomControl: true,
      }),
    )
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(
      screen.getByRole('region', { name: '공공임대주택 지도' }),
    ).toHaveAttribute('aria-busy', 'false')
    expect(observe).toHaveBeenCalledOnce()

    resizeCallback([], {} as ResizeObserver)

    expect(fakeSdk.autoResizeMap).toHaveBeenCalledOnce()

    unmount()

    expect(disconnect).toHaveBeenCalledOnce()
    expect(fakeSdk.destroyMap).toHaveBeenCalledOnce()
  })

  it('인증 실패에는 재시도 없이 설정 확인을 안내한다', async () => {
    loadNaverMapsSdkMock.mockRejectedValue(
      new NaverMapsSdkError(
        'authentication',
        'NAVER Maps SDK 인증에 실패했습니다.',
      ),
    )

    render(<NaverMap />)

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '지도 인증에 실패했습니다.',
    )
    expect(
      screen.queryByRole('button', { name: '다시 시도' }),
    ).not.toBeInTheDocument()
  })

  it('지도 생성 뒤 인증이 실패하면 지도를 해제하고 오류를 표시한다', async () => {
    const fakeSdk = createFakeSdk()
    fakeSdk.destroyMap.mockImplementationOnce(() => {
      throw new Error('NAVER SDK가 이미 지도를 해제했습니다.')
    })
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)

    render(<NaverMap />)
    await waitFor(() => expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce())

    act(() => {
      authenticationFailureListener?.(
        new NaverMapsSdkError(
          'authentication',
          'NAVER Maps SDK 인증에 실패했습니다.',
        ),
      )
    })

    expect(screen.getByRole('alert')).toHaveTextContent(
      '지도 인증에 실패했습니다.',
    )
    expect(fakeSdk.destroyMap).toHaveBeenCalledOnce()
  })

  it('네트워크 실패 후 다시 시도하면 지도를 표시한다', async () => {
    const fakeSdk = createFakeSdk()
    loadNaverMapsSdkMock
      .mockRejectedValueOnce(
        new NaverMapsSdkError(
          'network',
          'NAVER Maps SDK를 내려받지 못했습니다.',
        ),
      )
      .mockResolvedValueOnce(fakeSdk.maps)

    render(<NaverMap />)

    const retryButton = await screen.findByRole('button', {
      name: '다시 시도',
    })
    fireEvent.click(retryButton)

    expect(screen.getByRole('status')).toHaveTextContent(
      '지도를 불러오고 있습니다.',
    )
    await waitFor(() => expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce())
    expect(loadNaverMapsSdkMock).toHaveBeenCalledTimes(2)
  })

  it('지도 생성 실패 후 SDK를 재사용해 다시 초기화한다', async () => {
    const fakeSdk = createFakeSdk()
    fakeSdk.mapConstructor.mockImplementationOnce(() => {
      throw new Error('지도 생성 실패')
    })
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)

    render(<NaverMap />)

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('지도를 표시하지 못했습니다.')

    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))

    await waitFor(() =>
      expect(fakeSdk.mapConstructor).toHaveBeenCalledTimes(2),
    )
    expect(loadNaverMapsSdkMock).toHaveBeenCalledTimes(2)
  })

  it('반응형 관찰자 생성 실패 시 만들어진 지도를 즉시 해제한다', async () => {
    const fakeSdk = createFakeSdk()

    class FailingResizeObserver {
      constructor() {
        throw new Error('ResizeObserver 생성 실패')
      }
    }

    vi.stubGlobal('ResizeObserver', FailingResizeObserver)
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)

    render(<NaverMap />)

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '지도를 표시하지 못했습니다.',
    )
    expect(fakeSdk.destroyMap).toHaveBeenCalledOnce()
  })

  it('준비되기 전에 unmount되면 늦은 응답으로 지도를 만들지 않는다', async () => {
    const fakeSdk = createFakeSdk()
    const deferred = createDeferred<typeof naver.maps>()
    loadNaverMapsSdkMock.mockReturnValue(deferred.promise)
    const { unmount } = render(<NaverMap />)

    unmount()
    await act(async () => deferred.resolve(fakeSdk.maps))

    expect(fakeSdk.mapConstructor).not.toHaveBeenCalled()
  })

  it('StrictMode 재실행에서도 생성한 지도를 한 번 해제한다', async () => {
    const fakeSdk = createFakeSdk()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)

    const { unmount } = render(
      <StrictMode>
        <NaverMap />
      </StrictMode>,
    )

    await waitFor(() => expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce())
    unmount()

    expect(fakeSdk.destroyMap).toHaveBeenCalledOnce()
  })
})
