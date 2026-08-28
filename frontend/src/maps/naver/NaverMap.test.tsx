import { StrictMode } from 'react'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import NaverMap, { type NaverMapMarker } from './NaverMap.tsx'
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
  fitBoundsMap: ReturnType<typeof vi.fn>
  fromCoordToOffset: ReturnType<typeof vi.fn>
  getCenterMap: ReturnType<typeof vi.fn>
  getMaxZoomMap: ReturnType<typeof vi.fn>
  getMinZoomMap: ReturnType<typeof vi.fn>
  latLngConstructor: ReturnType<typeof vi.fn>
  mapConstructor: ReturnType<typeof vi.fn>
  markerConstructor: ReturnType<typeof vi.fn>
  markerSetMap: ReturnType<typeof vi.fn>
  maps: typeof naver.maps
  panToMap: ReturnType<typeof vi.fn>
  removeListener: ReturnType<typeof vi.fn>
  setCurrentZoom: (zoom: number) => void
  setZoomMap: ReturnType<typeof vi.fn>
}

function createFakeSdk(): FakeSdk {
  let currentCenter = {
    latitude: 37.5666103,
    longitude: 126.9783882,
  }
  let currentZoom = 14
  const destroyMap = vi.fn()
  const autoResizeMap = vi.fn()
  const panToMap = vi.fn((coordinate: unknown) => {
    currentCenter = coordinate as typeof currentCenter
  })
  const setZoomMap = vi.fn((zoom: number) => {
    currentZoom = zoom
  })
  const fitBoundsMap = vi.fn()
  const getCenterMap = vi.fn(() => ({
    lat: () => currentCenter.latitude,
    lng: () => currentCenter.longitude,
  }))
  const getMaxZoomMap = vi.fn(() => 21)
  const getMinZoomMap = vi.fn(() => 6)
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
    fitBounds: fitBoundsMap,
    getBounds: () => ({
      getNE: () => ({ lat: () => 37.7, lng: () => 127.1 }),
      getSW: () => ({ lat: () => 37.5, lng: () => 126.8 }),
    }),
    getCenter: getCenterMap,
    getMaxZoom: getMaxZoomMap,
    getMinZoom: getMinZoomMap,
    getProjection: () => ({ fromCoordToOffset }),
    getZoom: () => currentZoom,
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
  const fromCoordToOffset = vi.fn((coordinate: unknown) => {
    const { latitude, longitude } = coordinate as {
      latitude: number
      longitude: number
    }
    const scale = 50_000 * 2 ** (currentZoom - 14)
    return {
      x: (longitude - 127) * scale,
      y: (latitude - 37.5) * scale,
    }
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
    fitBoundsMap,
    fromCoordToOffset,
    getCenterMap,
    getMaxZoomMap,
    getMinZoomMap,
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
    setCurrentZoom: (zoom) => {
      currentZoom = zoom
    },
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

function createdMarkerButton(
  fakeSdk: FakeSdk,
  callIndex: number,
): HTMLButtonElement {
  const markerOptions = fakeSdk.markerConstructor.mock.calls[callIndex]?.[0]
  const markerButton = markerOptions?.icon?.content
  if (!(markerButton instanceof HTMLButtonElement)) {
    throw new Error(`${callIndex + 1}번째 marker 버튼을 찾을 수 없습니다.`)
  }

  return markerButton
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

  it('URL 직렬화 정밀도 안의 camera 차이는 무시하고 더 큰 차이만 적용한다', async () => {
    const fakeSdk = createFakeSdk()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)

    const { rerender } = render(
      <NaverMap
        cameraTarget={{
          latitude: 37.510001,
          longitude: 127.020001,
          zoom: 15.001,
        }}
      />,
    )

    await waitFor(() => expect(fakeSdk.panToMap).toHaveBeenCalledOnce())
    expect(fakeSdk.setZoomMap).toHaveBeenCalledOnce()

    rerender(
      <NaverMap
        cameraTarget={{
          latitude: 37.510004,
          longitude: 127.020004,
          zoom: 15.004,
        }}
      />,
    )

    expect(fakeSdk.panToMap).toHaveBeenCalledOnce()
    expect(fakeSdk.setZoomMap).toHaveBeenCalledOnce()

    rerender(
      <NaverMap
        cameraTarget={{
          latitude: 37.51002,
          longitude: 127.020004,
          zoom: 15.004,
        }}
      />,
    )

    expect(fakeSdk.panToMap).toHaveBeenCalledTimes(2)
    expect(fakeSdk.setZoomMap).toHaveBeenCalledOnce()

    rerender(
      <NaverMap
        cameraTarget={{
          latitude: 37.51002,
          longitude: 127.02002,
          zoom: 15.004,
        }}
      />,
    )

    expect(fakeSdk.panToMap).toHaveBeenCalledTimes(3)
    expect(fakeSdk.setZoomMap).toHaveBeenCalledOnce()

    rerender(
      <NaverMap
        cameraTarget={{
          latitude: 37.51002,
          longitude: 127.02002,
          zoom: 15.02,
        }}
      />,
    )

    expect(fakeSdk.panToMap).toHaveBeenCalledTimes(3)
    expect(fakeSdk.setZoomMap).toHaveBeenCalledTimes(2)
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
    ] satisfies NaverMapMarker[]
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

    act(() => fakeSdk.emitIdle())

    expect(onViewportChange).toHaveBeenCalledWith({
      bounds: {
        southWestLat: 37.5,
        southWestLng: 126.8,
        northEastLat: 37.7,
        northEastLng: 127.1,
      },
      center: {
        latitude: 37.5666103,
        longitude: 126.9783882,
      },
      zoom: 14,
    })
    await waitFor(() =>
      expect(fakeSdk.markerConstructor).toHaveBeenCalledTimes(2),
    )

    const markerButton = createdMarkerButton(fakeSdk, 1)
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
    expect(fakeSdk.markerConstructor).toHaveBeenCalledTimes(2)

    unmount()

    expect(fakeSdk.markerSetMap).toHaveBeenCalledTimes(2)
    expect(fakeSdk.markerSetMap).toHaveBeenCalledWith(null)
    expect(fakeSdk.removeListener).toHaveBeenCalledOnce()
  })

  it('화면에서 가까운 단지만 64px cluster로 묶는다', async () => {
    const fakeSdk = createFakeSdk()
    const markers = [
      {
        id: 'near-a',
        latitude: 37.5,
        longitude: 127,
        name: '가까운 첫 단지',
      },
      {
        id: 'near-b',
        latitude: 37.5,
        longitude: 127.001,
        name: '가까운 둘째 단지',
      },
      {
        id: 'far',
        latitude: 37.5,
        longitude: 127.0025,
        name: '먼 단지',
      },
    ] satisfies NaverMapMarker[]
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)

    render(<NaverMap markers={markers} />)

    await waitFor(() =>
      expect(fakeSdk.markerConstructor).toHaveBeenCalledTimes(2),
    )
    const buttons = [
      createdMarkerButton(fakeSdk, 0),
      createdMarkerButton(fakeSdk, 1),
    ]
    const clusterButton = buttons.find((button) =>
      button.classList.contains('housing-map-cluster'),
    )
    const farMarkerButton = buttons.find(
      (button) => button.dataset.complexId === 'far',
    )

    expect(clusterButton).toBeInstanceOf(HTMLButtonElement)
    expect(clusterButton).toHaveAttribute('type', 'button')
    expect(clusterButton).toHaveAttribute(
      'data-complex-ids',
      'near-a,near-b',
    )
    expect(clusterButton).toHaveAccessibleName('2곳 단지 묶음, 확대해서 보기')
    expect(clusterButton).toHaveTextContent('2곳')
    expect(farMarkerButton).toHaveAccessibleName('먼 단지 단지 상세 보기')
    expect(fakeSdk.fromCoordToOffset).toHaveBeenCalledTimes(3)
  })

  it('선택 marker는 cluster에서 제외하고 강조 단지가 든 cluster를 강조한다', async () => {
    const fakeSdk = createFakeSdk()
    const markers = [
      {
        id: 'selected',
        latitude: 37.5,
        longitude: 127,
        name: '선택 단지',
        selected: true,
      },
      {
        id: 'cluster-a',
        latitude: 37.5,
        longitude: 127,
        name: '묶음 첫 단지',
      },
      {
        highlighted: true,
        id: 'cluster-b',
        latitude: 37.5,
        longitude: 127.001,
        name: '묶음 둘째 단지',
      },
    ] satisfies NaverMapMarker[]
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)

    render(<NaverMap markers={markers} />)

    await waitFor(() =>
      expect(fakeSdk.markerConstructor).toHaveBeenCalledTimes(2),
    )
    const buttons = [
      createdMarkerButton(fakeSdk, 0),
      createdMarkerButton(fakeSdk, 1),
    ]
    const clusterButton = buttons.find((button) =>
      button.classList.contains('housing-map-cluster'),
    )
    const selectedButton = buttons.find(
      (button) => button.dataset.complexId === 'selected',
    )

    expect(clusterButton).toHaveClass('is-highlighted')
    expect(clusterButton).toHaveAttribute(
      'data-complex-ids',
      'cluster-a,cluster-b',
    )
    expect(clusterButton).not.toHaveAttribute(
      'data-complex-ids',
      expect.stringContaining('selected'),
    )
    expect(selectedButton).toHaveClass('is-selected')
  })

  it('cluster 선택 시 72px 여백과 현재 zoom 기준 상한으로 bounds를 맞춘다', async () => {
    const fakeSdk = createFakeSdk()
    const markers = [
      {
        id: '101',
        latitude: 37.5,
        longitude: 127,
        name: '첫 단지',
      },
      {
        id: '102',
        latitude: 37.5,
        longitude: 127.001,
        name: '둘째 단지',
      },
    ] satisfies NaverMapMarker[]
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)

    render(<NaverMap markers={markers} />)

    await waitFor(() =>
      expect(fakeSdk.markerConstructor).toHaveBeenCalledOnce(),
    )
    const clusterButton = createdMarkerButton(fakeSdk, 0)

    fireEvent.click(clusterButton)

    expect(fakeSdk.fitBoundsMap).toHaveBeenLastCalledWith(
      [
        { latitude: 37.5, longitude: 127 },
        { latitude: 37.5, longitude: 127.001 },
      ],
      {
        bottom: 72,
        left: 72,
        maxZoom: 16,
        right: 72,
        top: 72,
      },
    )
    expect(await screen.findByRole('status')).toHaveTextContent(
      '2곳 단지 묶음을 확대했습니다.',
    )

    fakeSdk.setCurrentZoom(20)
    fireEvent.click(clusterButton)

    expect(fakeSdk.fitBoundsMap.mock.calls.at(-1)?.[1]).toMatchObject({
      maxZoom: 21,
    })
    expect(fakeSdk.getMaxZoomMap).toHaveBeenCalledTimes(2)
  })

  it('idle에서 zoom이 바뀌면 화면 거리를 다시 계산해 분리하고 재결합한다', async () => {
    const fakeSdk = createFakeSdk()
    const markers = [
      {
        id: '101',
        latitude: 37.5,
        longitude: 127,
        name: '첫 단지',
      },
      {
        id: '102',
        latitude: 37.5,
        longitude: 127.001,
        name: '둘째 단지',
      },
    ] satisfies NaverMapMarker[]
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)

    render(<NaverMap markers={markers} />)

    await waitFor(() =>
      expect(fakeSdk.markerConstructor).toHaveBeenCalledOnce(),
    )
    expect(createdMarkerButton(fakeSdk, 0)).toHaveClass(
      'housing-map-cluster',
    )

    fakeSdk.markerConstructor.mockClear()
    act(() => {
      fakeSdk.setCurrentZoom(15)
      fakeSdk.emitIdle()
    })

    await waitFor(() =>
      expect(fakeSdk.markerConstructor).toHaveBeenCalledTimes(2),
    )
    expect(
      [createdMarkerButton(fakeSdk, 0), createdMarkerButton(fakeSdk, 1)].map(
        (button) => button.dataset.complexId,
      ),
    ).toEqual(['101', '102'])

    fakeSdk.markerConstructor.mockClear()
    act(() => {
      fakeSdk.setCurrentZoom(14)
      fakeSdk.emitIdle()
    })

    await waitFor(() =>
      expect(fakeSdk.markerConstructor).toHaveBeenCalledOnce(),
    )
    expect(createdMarkerButton(fakeSdk, 0)).toHaveClass(
      'housing-map-cluster',
    )
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
