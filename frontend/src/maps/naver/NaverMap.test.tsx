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
  addListener: ReturnType<typeof vi.fn>
  autoResizeMap: ReturnType<typeof vi.fn>
  destroyMap: ReturnType<typeof vi.fn>
  emitIdle: () => void
  latLngConstructor: ReturnType<typeof vi.fn>
  mapConstructor: ReturnType<typeof vi.fn>
  markerConstructor: ReturnType<typeof vi.fn>
  markerSetMap: ReturnType<typeof vi.fn>
  maps: typeof naver.maps
  panToMap: ReturnType<typeof vi.fn>
  removeListener: ReturnType<typeof vi.fn>
  setZoomMap: ReturnType<typeof vi.fn>
}

function createFakeSdk(): FakeSdk {
  const destroyMap = vi.fn()
  const autoResizeMap = vi.fn()
  const panToMap = vi.fn()
  const setZoomMap = vi.fn()
  const markerSetMap = vi.fn()
  const removeListener = vi.fn()
  let idleListener: (() => void) | null = null
  const addListener = vi.fn(
    (_map: naver.maps.Map, eventName: string, listener: () => void) => {
      if (eventName === 'idle') {
        idleListener = listener
      }

      return { eventName } as unknown as naver.maps.MapEventListener
    },
  )
  const mapInstance = {
    autoResize: autoResizeMap,
    destroy: destroyMap,
    getBounds: () => ({
      getNE: () => ({ lat: () => 37.7, lng: () => 127.1 }),
      getSW: () => ({ lat: () => 37.5, lng: () => 126.8 }),
    }),
    getZoom: () => 14,
    panTo: panToMap,
    setZoom: setZoomMap,
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
  const markerConstructor = vi.fn(function FakeMarkerConstructor() {
    return { setMap: markerSetMap }
  })
  const pointConstructor = vi.fn(function FakePointConstructor(
    x: number,
    y: number,
  ) {
    return { x, y }
  })
  const sizeConstructor = vi.fn(function FakeSizeConstructor(
    width: number,
    height: number,
  ) {
    return { height, width }
  })

  return {
    addListener,
    autoResizeMap,
    destroyMap,
    emitIdle: () => idleListener?.(),
    latLngConstructor,
    mapConstructor,
    markerConstructor,
    markerSetMap,
    maps: {
      Event: {
        addListener,
        removeListener,
      },
      LatLng: latLngConstructor,
      Map: mapConstructor,
      Marker: markerConstructor,
      Point: pointConstructor,
      Size: sizeConstructor,
    } as unknown as typeof naver.maps,
    panToMap,
    removeListener,
    setZoomMap,
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

  it('camera target에서 실제로 달라진 위치와 zoom만 적용한다', async () => {
    const fakeSdk = createFakeSdk()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)

    const { rerender } = render(
      <NaverMap
        cameraTarget={{ latitude: 37.51, longitude: 127.02, zoom: 15 }}
      />,
    )

    await waitFor(() => expect(fakeSdk.panToMap).toHaveBeenCalledOnce())
    expect(fakeSdk.panToMap).toHaveBeenLastCalledWith({
      latitude: 37.51,
      longitude: 127.02,
    })
    expect(fakeSdk.setZoomMap).toHaveBeenCalledOnce()
    expect(fakeSdk.setZoomMap).toHaveBeenLastCalledWith(15)

    rerender(
      <NaverMap
        cameraTarget={{ latitude: 37.51, longitude: 127.02, zoom: 15 }}
      />,
    )

    expect(fakeSdk.panToMap).toHaveBeenCalledOnce()
    expect(fakeSdk.setZoomMap).toHaveBeenCalledOnce()

    rerender(
      <NaverMap
        cameraTarget={{ latitude: 37.52, longitude: 127.02, zoom: 15 }}
      />,
    )

    expect(fakeSdk.panToMap).toHaveBeenCalledTimes(2)
    expect(fakeSdk.setZoomMap).toHaveBeenCalledOnce()

    rerender(
      <NaverMap
        cameraTarget={{ latitude: 37.52, longitude: 127.02, zoom: 16 }}
      />,
    )

    expect(fakeSdk.panToMap).toHaveBeenCalledTimes(2)
    expect(fakeSdk.setZoomMap).toHaveBeenCalledTimes(2)
    expect(fakeSdk.setZoomMap).toHaveBeenLastCalledWith(16)
    expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce()
  })

  it('잘못된 camera target은 지도에 전달하지 않는다', async () => {
    const fakeSdk = createFakeSdk()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)

    render(
      <NaverMap
        cameraTarget={{ latitude: Number.NaN, longitude: 127, zoom: 15 }}
      />,
    )

    await waitFor(() => expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce())
    expect(fakeSdk.panToMap).not.toHaveBeenCalled()
    expect(fakeSdk.setZoomMap).not.toHaveBeenCalled()
  })

  it('camera 이동과 함께 marker와 idle callback 수명주기를 유지한다', async () => {
    const fakeSdk = createFakeSdk()
    const onMarkerSelect = vi.fn()
    const onViewportChange = vi.fn()
    const markers = [
      {
        id: '101',
        latitude: 37.6,
        longitude: 127,
        name: '테스트 단지',
      },
    ]
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)

    const { rerender, unmount } = render(
      <NaverMap
        markers={markers}
        onMarkerSelect={onMarkerSelect}
        onViewportChange={onViewportChange}
      />,
    )

    await waitFor(() =>
      expect(fakeSdk.markerConstructor).toHaveBeenCalledOnce(),
    )
    expect(fakeSdk.addListener).toHaveBeenCalledWith(
      expect.anything(),
      'idle',
      expect.any(Function),
    )

    fakeSdk.emitIdle()

    expect(onViewportChange).toHaveBeenCalledWith({
      bounds: {
        southWestLat: 37.5,
        southWestLng: 126.8,
        northEastLat: 37.7,
        northEastLng: 127.1,
      },
      zoom: 14,
    })

    const markerOptions = fakeSdk.markerConstructor.mock.calls[0]?.[0]
    const markerButton = markerOptions?.icon?.content
    if (!(markerButton instanceof HTMLButtonElement)) {
      throw new Error('단지 marker 버튼을 찾을 수 없습니다.')
    }
    expect(markerButton).toHaveAttribute('data-complex-id', '101')
    fireEvent.click(markerButton)

    expect(onMarkerSelect).toHaveBeenCalledWith('101')

    rerender(
      <NaverMap
        cameraTarget={{ latitude: 37.61, longitude: 127.01 }}
        markers={markers}
        onMarkerSelect={onMarkerSelect}
        onViewportChange={onViewportChange}
      />,
    )

    expect(fakeSdk.panToMap).toHaveBeenCalledOnce()
    expect(fakeSdk.markerConstructor).toHaveBeenCalledOnce()

    unmount()

    expect(fakeSdk.markerSetMap).toHaveBeenCalledOnce()
    expect(fakeSdk.markerSetMap).toHaveBeenCalledWith(null)
    expect(fakeSdk.removeListener).toHaveBeenCalledOnce()
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

    render(<NaverMap onViewportChange={vi.fn()} />)
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
    expect(fakeSdk.removeListener).toHaveBeenCalledOnce()
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

    render(<NaverMap onViewportChange={vi.fn()} />)

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '지도를 표시하지 못했습니다.',
    )
    expect(fakeSdk.destroyMap).toHaveBeenCalledOnce()
    expect(fakeSdk.removeListener).toHaveBeenCalledOnce()
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
