import { StrictMode, useState } from 'react'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MapMarkerPresentation } from '../../public-housing/presentation/mapMarkerPresentation.ts'
import type { RegionBoundary } from '../../public-housing/regions/regionBoundary.ts'
import NaverMap, {
  type NaverMapAggregateMarker,
  type NaverMapMarker,
} from './NaverMap.tsx'
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
  emitDragStart: () => void
  emitIdle: () => void
  emitInit: () => void
  fitBoundsMap: ReturnType<typeof vi.fn>
  fromCoordToOffset: ReturnType<typeof vi.fn>
  fromOffsetToCoord: ReturnType<typeof vi.fn>
  getCenterMap: ReturnType<typeof vi.fn>
  getMaxZoomMap: ReturnType<typeof vi.fn>
  getMinZoomMap: ReturnType<typeof vi.fn>
  latLngConstructor: ReturnType<typeof vi.fn>
  mapConstructor: ReturnType<typeof vi.fn>
  markerConstructor: ReturnType<typeof vi.fn>
  markerInstances: Array<{
    setMap: ReturnType<typeof vi.fn>
    setZIndex: ReturnType<typeof vi.fn>
  }>
  markerSetMap: ReturnType<typeof vi.fn>
  markerSetZIndex: ReturnType<typeof vi.fn>
  maps: typeof naver.maps
  morphMap: ReturnType<typeof vi.fn>
  panToMap: ReturnType<typeof vi.fn>
  overlayConstructor: ReturnType<typeof vi.fn>
  overlayInstances: Array<{ setMap: ReturnType<typeof vi.fn> }>
  once: ReturnType<typeof vi.fn>
  removeListener: ReturnType<typeof vi.fn>
  setCurrentCenter: (latitude: number, longitude: number) => void
  setCurrentZoom: (zoom: number) => void
  setZoomMap: ReturnType<typeof vi.fn>
  stopMap: ReturnType<typeof vi.fn>
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
  const morphMap = vi.fn((coordinate: unknown, zoom: number) => {
    currentCenter = coordinate as typeof currentCenter
    currentZoom = zoom
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
  const markerSetZIndex = vi.fn()
  const markerInstances: FakeSdk['markerInstances'] = []
  const overlayInstances: FakeSdk['overlayInstances'] = []
  const overlayConstructor = vi.fn()
  class FakeOverlayView {
    map: naver.maps.Map | null = null
    constructor() { overlayConstructor(); overlayInstances.push(this) }
    onAdd() {}
    onRemove() {}
    draw() {}
    getMap() { return this.map }
    getPanes() { return { overlayLayer: document.body } }
    getProjection() { return mapInstance.getProjection() }
    setMap = vi.fn((map: naver.maps.Map | null) => {
      this.map = map
      if (map) { this.onAdd(); this.draw() } else this.onRemove()
    })
  }
  const stopMap = vi.fn()
  let dragStartListener: (() => void) | null = null
  let idleListener: (() => void) | null = null
  let initListener: (() => void) | null = null
  const removeListener = vi.fn((listener: naver.maps.MapEventListener) => {
    const eventName = (listener as unknown as { eventName?: string }).eventName
    if (eventName === 'idle') {
      idleListener = null
    }
    if (eventName === 'dragstart') {
      dragStartListener = null
    }
    if (eventName === 'init') {
      initListener = null
    }
  })
  const addListener = vi.fn(
    (_map: naver.maps.Map, eventName: string, listener: () => void) => {
      if (eventName === 'idle') {
        idleListener = listener
      }
      if (eventName === 'dragstart') {
        dragStartListener = listener
      }

      return { eventName } as unknown as naver.maps.MapEventListener
    },
  )
  const once = vi.fn(
    (_map: naver.maps.Map, eventName: string, listener: () => void) => {
      if (eventName === 'init') {
        initListener = listener
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
    getSize: () => ({ width: 1024, height: 768 }),
    getMaxZoom: getMaxZoomMap,
    getMinZoom: getMinZoomMap,
    getProjection: () => ({ factor: (zoom: number) => 2 ** zoom, fromCoordToOffset, fromOffsetToCoord }),
    getZoom: () => currentZoom,
    morph: morphMap,
    panTo: panToMap,
    setZoom: setZoomMap,
    stop: stopMap,
  }
  const mapConstructor = vi.fn(function FakeMapConstructor(
    _element: string | HTMLElement,
    _options?: naver.maps.MapOptions,
  ) {
    if (_options?.center) {
      currentCenter = _options.center as unknown as typeof currentCenter
    }
    if (typeof _options?.zoom === 'number') {
      currentZoom = _options.zoom
    }
    return mapInstance
  })
  const latLngConstructor = vi.fn(function FakeLatLngConstructor(
    latitude: number,
    longitude: number,
  ) {
    return { latitude, longitude }
  })
  const fromCoordToOffset = vi.fn((coordinate: unknown) => {
    const value = coordinate as { latitude?: number; longitude?: number; lat?: () => number; lng?: () => number }
    const latitude = value.latitude ?? value.lat!()
    const longitude = value.longitude ?? value.lng!()
    const scale = 50_000 * 2 ** (currentZoom - 14)
    return {
      x: (longitude - 127) * scale,
      y: (latitude - 37.5) * scale,
    }
  })
  const fromOffsetToCoord = vi.fn(({ x, y }: { x: number; y: number }) => {
    const scale = 50_000 * 2 ** (currentZoom - 14)
    return {
      lat: () => 37.5 + y / scale,
      lng: () => 127 + x / scale,
    }
  })
  const markerConstructor = vi.fn(function FakeMarkerConstructor(
    options?: naver.maps.MarkerOptions,
  ) {
    const icon = options?.icon
    const content = typeof icon === 'object' && icon !== null
      && 'content' in icon
      ? icon.content
      : null
    if (content instanceof HTMLElement) {
      document.body.append(content)
    }
    const instance = {
      setMap: vi.fn((map: naver.maps.Map | null) => {
        markerSetMap(map)
        if (map === null && content instanceof HTMLElement) {
          content.remove()
        }
      }),
      setZIndex: vi.fn((zIndex: number) => markerSetZIndex(zIndex)),
    }
    markerInstances.push(instance)
    return instance
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
    emitDragStart: () => dragStartListener?.(),
    emitIdle: () => idleListener?.(),
    emitInit: () => {
      const listener = initListener
      initListener = null
      listener?.()
    },
    fitBoundsMap,
    fromCoordToOffset,
    fromOffsetToCoord,
    getCenterMap,
    getMaxZoomMap,
    getMinZoomMap,
    latLngConstructor,
    mapConstructor,
    markerConstructor,
    markerInstances,
    markerSetMap,
    markerSetZIndex,
    morphMap,
    maps: {
      Event: {
        addListener,
        once,
        removeListener,
      },
      LatLng: latLngConstructor,
      Map: mapConstructor,
      Marker: markerConstructor,
      Point: pointConstructor,
      OverlayView: FakeOverlayView,
      Position: { BOTTOM_LEFT: 10, RIGHT_BOTTOM: 9 },
      Size: sizeConstructor,
    } as unknown as typeof naver.maps,
    panToMap,
    overlayConstructor,
    overlayInstances,
    once,
    removeListener,
    setCurrentCenter: (latitude, longitude) => {
      currentCenter = { latitude, longitude }
    },
    setCurrentZoom: (zoom) => {
      currentZoom = zoom
    },
    setZoomMap,
    stopMap,
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
  const content = markerOptions?.icon?.content
  const markerButton = content instanceof HTMLButtonElement
    ? content
    : content instanceof HTMLElement ? content.querySelector('button') : null
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
const markerPresentation = {
  agencyLabel: 'LH',
  agencyName: '한국토지주택공사',
  deposit: { digits: '1000', unit: '만', exactLabel: '10,000,000원' },
  monthlyRent: { digits: '23.4', unit: '만', exactLabel: '234,000원' },
  rentalTypeLabel: '국민',
  rentalTypeName: '국민임대',
} satisfies MapMarkerPresentation
const aggregateMarker = {
  expansionZoom: 11,
  groupKey: 'METROPOLITAN:41',
  groupLabel: '경기',
  latitude: 37.4138,
  longitude: 127.5183,
  nextStage: 3,
  uniqueComplexCount: 42,
} satisfies NaverMapAggregateMarker

// Hand-authored geometry: two islands, with one hole in the first island.
const regionBoundary = {
  regionCode: '11110',
  version: 'test-v1',
  polygons: [
    [
      [[126.8, 37.5], [127, 37.5], [127, 37.7], [126.8, 37.5]],
      [[126.9, 37.55], [126.92, 37.6], [126.94, 37.55], [126.9, 37.55]],
    ],
    [[[127.1, 37.6], [127.2, 37.6], [127.2, 37.7], [127.1, 37.6]]],
  ],
} satisfies RegionBoundary

const regionCameraTarget = {
  latitude: 37.6,
  longitude: 127,
  bounds: {
    southWestLat: 37.5,
    southWestLng: 126.8,
    northEastLat: 37.7,
    northEastLng: 127.2,
  },
  boundsPadding: { top: 60, right: 24, bottom: 32, left: 400 },
  zoom: 16,
}

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

  it('지도 데이터 갱신 중에도 지도 영역을 busy로 표시한다', async () => {
    const fakeSdk = createFakeSdk()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)

    render(<NaverMap dataBusy />)

    await waitFor(() => expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce())
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
        logoControlOptions: {
          position: fakeSdk.maps.Position.BOTTOM_LEFT,
        },
        scaleControlOptions: {
          position: fakeSdk.maps.Position.BOTTOM_LEFT,
        },
        zoom: 14,
        zoomControl: true,
        zoomControlOptions: {
          position: fakeSdk.maps.Position.RIGHT_BOTTOM,
        },
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

  it('init 전에 unmount하면 초기화 리스너를 제거하고 bounds를 적용하지 않는다', async () => {
    const fakeSdk = createFakeSdk()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    const { unmount } = render(
      <NaverMap cameraRequestId={1} cameraTarget={regionCameraTarget} />,
    )
    await waitFor(() => expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce())

    unmount()

    expect(fakeSdk.removeListener).toHaveBeenCalledWith(
      expect.objectContaining({ eventName: 'init' }),
    )
    act(() => fakeSdk.emitInit())
    expect(fakeSdk.fitBoundsMap).not.toHaveBeenCalled()
  })

  it('초기 camera target으로 지도를 만들고 이후 달라진 값만 적용한다', async () => {
    const fakeSdk = createFakeSdk()
    const onViewportChange = vi.fn()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)

    const { rerender } = render(
      <NaverMap
        cameraTarget={{ latitude: 37.51, longitude: 127.02, zoom: 15 }}
        onViewportChange={onViewportChange}
      />,
    )

    await waitFor(() => expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce())
    expect(fakeSdk.latLngConstructor).toHaveBeenCalledWith(37.51, 127.02)
    expect(fakeSdk.mapConstructor.mock.calls[0]?.[1]).toEqual(
      expect.objectContaining({ zoom: 15 }),
    )
    expect(fakeSdk.panToMap).not.toHaveBeenCalled()
    expect(fakeSdk.setZoomMap).not.toHaveBeenCalled()
    act(() => fakeSdk.emitIdle())
    expect(onViewportChange).toHaveBeenLastCalledWith(expect.objectContaining({
      center: { latitude: 37.51, longitude: 127.02 },
      zoom: 15,
    }))

    rerender(
      <NaverMap
        cameraTarget={{ latitude: 37.51, longitude: 127.02, zoom: 15 }}
        onViewportChange={onViewportChange}
      />,
    )

    expect(fakeSdk.panToMap).not.toHaveBeenCalled()
    expect(fakeSdk.setZoomMap).not.toHaveBeenCalled()

    rerender(
      <NaverMap
        cameraTarget={{ latitude: 37.52, longitude: 127.02, zoom: 15 }}
        onViewportChange={onViewportChange}
      />,
    )

    expect(fakeSdk.panToMap).toHaveBeenCalledOnce()
    expect(fakeSdk.setZoomMap).not.toHaveBeenCalled()

    rerender(
      <NaverMap
        cameraTarget={{ latitude: 37.52, longitude: 127.02, zoom: 16 }}
        onViewportChange={onViewportChange}
      />,
    )

    expect(fakeSdk.panToMap).toHaveBeenCalledOnce()
    expect(fakeSdk.setZoomMap).toHaveBeenCalledOnce()
    expect(fakeSdk.setZoomMap).toHaveBeenLastCalledWith(16)
    expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce()
  })

  it('좌표와 확대 수준이 함께 바뀌면 한 번의 카메라 이동으로 적용한다', async () => {
    const fakeSdk = createFakeSdk()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)

    const { rerender } = render(
      <NaverMap
        cameraTarget={{ latitude: 37.51, longitude: 127.02, zoom: 14 }}
      />,
    )

    await waitFor(() => expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce())

    rerender(
      <NaverMap
        cameraTarget={{ latitude: 35.18, longitude: 129.08, zoom: 16 }}
      />,
    )

    expect(fakeSdk.morphMap).toHaveBeenCalledOnce()
    expect(fakeSdk.panToMap).not.toHaveBeenCalled()
    expect(fakeSdk.setZoomMap).not.toHaveBeenCalled()
  })

  it('초기 지역 bounds와 패딩을 한 번 맞추고 지정된 중심·줌 이동을 추가하지 않는다', async () => {
    const fakeSdk = createFakeSdk()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)

    render(<NaverMap cameraRequestId={1} cameraTarget={regionCameraTarget} />)

    await waitFor(() => expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce())
    expect(fakeSdk.fitBoundsMap).not.toHaveBeenCalled()
    expect(fakeSdk.once).toHaveBeenCalledWith(
      fakeSdk.mapConstructor.mock.results[0]?.value,
      'init',
      expect.any(Function),
    )

    act(() => fakeSdk.emitInit())

    expect(fakeSdk.fitBoundsMap).toHaveBeenCalledOnce()
    expect(fakeSdk.fitBoundsMap).toHaveBeenCalledWith([
      { latitude: 37.5, longitude: 126.8 },
      { latitude: 37.7, longitude: 127.2 },
    ], { top: 60, right: 24, bottom: 32, left: 400 })
    expect(fakeSdk.fitBoundsMap.mock.invocationCallOrder[0])
      .toBeGreaterThan(fakeSdk.mapConstructor.mock.invocationCallOrder[0])
    expect(fakeSdk.panToMap).not.toHaveBeenCalled()
    expect(fakeSdk.morphMap).not.toHaveBeenCalled()
    expect(fakeSdk.setZoomMap).not.toHaveBeenCalled()
  })

  it('지역 bounds의 패딩을 생략하면 undefined 필드로 SDK 기본 여백을 덮어쓰지 않는다', async () => {
    const fakeSdk = createFakeSdk()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)

    render(<NaverMap cameraRequestId={1}
      cameraTarget={{ ...regionCameraTarget, boundsPadding: undefined }} />)

    await waitFor(() => expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce())
    expect(fakeSdk.fitBoundsMap).not.toHaveBeenCalled()
    act(() => fakeSdk.emitInit())

    expect(fakeSdk.fitBoundsMap).toHaveBeenCalledOnce()
    expect(fakeSdk.fitBoundsMap).toHaveBeenCalledWith([
      { latitude: 37.5, longitude: 126.8 },
      { latitude: 37.7, longitude: 127.2 },
    ])
  })

  it('새 지역 요청은 bounds를 우선 적용하며 같은 요청 ID와 늦은 경계 응답은 재이동하지 않는다', async () => {
    const fakeSdk = createFakeSdk()
    const onViewportChange = vi.fn()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    const { rerender } = render(<NaverMap cameraRequestId={1} onViewportChange={onViewportChange} />)
    await waitFor(() => expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce())
    act(() => fakeSdk.emitInit())

    rerender(<NaverMap cameraRequestId={2} cameraTarget={regionCameraTarget} onViewportChange={onViewportChange} />)
    expect(fakeSdk.fitBoundsMap).toHaveBeenCalledOnce()

    act(() => {
      fakeSdk.setCurrentCenter(35, 129)
      fakeSdk.setCurrentZoom(9)
      fakeSdk.emitIdle()
    })
    rerender(<NaverMap cameraRequestId={2}
      cameraTarget={{ ...regionCameraTarget, boundsPadding: { top: 10, right: 10, bottom: 10, left: 10 } }}
      regionBoundary={regionBoundary} />)
    expect(fakeSdk.fitBoundsMap).toHaveBeenCalledOnce()
    expect(onViewportChange).toHaveBeenLastCalledWith(expect.objectContaining({
      center: { latitude: 35, longitude: 129 }, zoom: 9,
    }))
    expect(fakeSdk.panToMap).not.toHaveBeenCalled()
    expect(fakeSdk.morphMap).not.toHaveBeenCalled()
    expect(fakeSdk.setZoomMap).not.toHaveBeenCalled()

    rerender(<NaverMap cameraRequestId={3} cameraTarget={regionCameraTarget}
      regionBoundary={regionBoundary} />)
    expect(fakeSdk.fitBoundsMap).toHaveBeenCalledTimes(2)
  })

  it('요청 ID 없이 동일한 bounds 객체가 재생성되어도 다시 맞추지 않는다', async () => {
    const fakeSdk = createFakeSdk()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    const { rerender } = render(<NaverMap cameraTarget={regionCameraTarget} />)
    await waitFor(() => expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce())
    act(() => fakeSdk.emitInit())
    expect(fakeSdk.fitBoundsMap).toHaveBeenCalledOnce()

    act(() => fakeSdk.emitIdle())
    rerender(<NaverMap cameraTarget={{ ...regionCameraTarget,
      bounds: { ...regionCameraTarget.bounds }, boundsPadding: { ...regionCameraTarget.boundsPadding } }} />)

    expect(fakeSdk.fitBoundsMap).toHaveBeenCalledOnce()
  })

  it('init 전에 바뀐 마지막 bounds와 요청 ID만 초기 카메라에 적용한다', async () => {
    const fakeSdk = createFakeSdk()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    const { rerender } = render(
      <NaverMap cameraRequestId={1} cameraTarget={regionCameraTarget} />,
    )
    await waitFor(() => expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce())

    const latestTarget = {
      ...regionCameraTarget,
      bounds: {
        southWestLat: 37.55,
        southWestLng: 126.9,
        northEastLat: 37.65,
        northEastLng: 127.1,
      },
      boundsPadding: { top: 80, right: 20, bottom: 30, left: 320 },
    }
    rerender(<NaverMap cameraRequestId={2} cameraTarget={latestTarget} />)

    expect(fakeSdk.fitBoundsMap).not.toHaveBeenCalled()
    act(() => fakeSdk.emitInit())

    expect(fakeSdk.fitBoundsMap).toHaveBeenCalledExactlyOnceWith([
      { latitude: 37.55, longitude: 126.9 },
      { latitude: 37.65, longitude: 127.1 },
    ], { top: 80, right: 20, bottom: 30, left: 320 })

    rerender(<NaverMap cameraRequestId={2} cameraTarget={regionCameraTarget} />)
    expect(fakeSdk.fitBoundsMap).toHaveBeenCalledOnce()
  })

  it('경계의 섬과 내부 구멍을 각각 보존하고 마커 아래에 클릭을 받지 않는 도형을 표시한다', async () => {
    const fakeSdk = createFakeSdk()
    const onMarkerSelect = vi.fn()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    render(<NaverMap regionBoundary={regionBoundary} onMarkerSelect={onMarkerSelect}
      markers={[{ ...markerPresentation, id: '101', name: '경계 안 단지', latitude: 37.6, longitude: 127 }]} />)

    await waitFor(() => expect(fakeSdk.overlayConstructor).toHaveBeenCalledTimes(1))
    const path = document.querySelector('svg path')
    expect(path?.getAttribute('d')?.match(/M/g)).toHaveLength(3)
    expect(path?.getAttribute('d')).not.toContain('NaN')
    expect(path?.getAttribute('fill-rule')).toBe('evenodd')
    expect(path?.getAttribute('fill')).toBe('#D34F3E')
    expect(document.querySelector('svg')?.style.pointerEvents).toBe('none')
    fireEvent.click(createdMarkerButton(fakeSdk, 0))
    expect(onMarkerSelect).toHaveBeenCalledWith('101')
    expect(fakeSdk.fitBoundsMap).not.toHaveBeenCalled()
  })

  it('지도 이동과 marker 갱신에서는 경계를 유지하고 다른 지역·해제·unmount에서 모두 제거한다', async () => {
    const fakeSdk = createFakeSdk()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    const { rerender, unmount } = render(<NaverMap regionBoundary={regionBoundary} />)
    await waitFor(() => expect(fakeSdk.overlayConstructor).toHaveBeenCalledTimes(1))

    act(() => fakeSdk.emitIdle())
    rerender(<NaverMap regionBoundary={regionBoundary}
      markers={[{ ...markerPresentation, id: '101', name: '갱신 단지', latitude: 37.6, longitude: 127 }]} />)
    expect(fakeSdk.overlayConstructor).toHaveBeenCalledTimes(1)
    expect(fakeSdk.overlayInstances[0]?.setMap).toHaveBeenCalledTimes(1)

    rerender(<NaverMap regionBoundary={{ ...regionBoundary, regionCode: '11140' }} />)
    expect(fakeSdk.overlayConstructor).toHaveBeenCalledTimes(2)
    expect(fakeSdk.overlayInstances[0]?.setMap).toHaveBeenLastCalledWith(null)

    rerender(<NaverMap regionBoundary={null} />)
    expect(fakeSdk.overlayInstances[1]?.setMap).toHaveBeenCalledWith(null)

    rerender(<NaverMap regionBoundary={regionBoundary} />)
    unmount()
    expect(fakeSdk.overlayInstances).toHaveLength(3)
    for (const polygon of fakeSdk.overlayInstances) {
      expect(polygon.setMap).toHaveBeenCalledTimes(2)
      expect(polygon.setMap).toHaveBeenLastCalledWith(null)
    }
  })

  it('지도 인증 실패 시 표시 중인 경계 도형을 모두 정리한다', async () => {
    const fakeSdk = createFakeSdk()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    const { unmount } = render(<NaverMap regionBoundary={regionBoundary} />)
    await waitFor(() => expect(fakeSdk.overlayConstructor).toHaveBeenCalledTimes(1))

    act(() => authenticationFailureListener?.(new NaverMapsSdkError('authentication', '인증 실패')))

    expect(await screen.findByRole('alert')).toHaveTextContent('지도 인증에 실패했습니다.')
    for (const polygon of fakeSdk.overlayInstances) {
      expect(polygon.setMap).toHaveBeenCalledTimes(2)
      expect(polygon.setMap).toHaveBeenLastCalledWith(null)
      expect(polygon.setMap.mock.invocationCallOrder[1])
        .toBeLessThan(fakeSdk.destroyMap.mock.invocationCallOrder[0])
    }
    unmount()
    for (const polygon of fakeSdk.overlayInstances) {
      expect(polygon.setMap).toHaveBeenCalledTimes(2)
    }
  })

  it('idle로 반영한 현재 카메라는 다시 지도 이동 명령으로 적용하지 않는다', async () => {
    const fakeSdk = createFakeSdk()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)

    function ReflectingCameraTargetMap() {
      const [cameraTarget, setCameraTarget] = useState<{
        latitude: number
        longitude: number
        zoom: number
      }>()

      return (
        <NaverMap
          cameraTarget={cameraTarget}
          onViewportChange={(nextViewport) => {
            setCameraTarget({
              latitude: nextViewport.center.latitude,
              longitude: nextViewport.center.longitude,
              zoom: nextViewport.zoom,
            })
          }}
        />
      )
    }

    render(<ReflectingCameraTargetMap />)

    await waitFor(() => expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce())

    act(() => {
      fakeSdk.setCurrentCenter(37.61, 127.01)
      fakeSdk.setCurrentZoom(15)
      fakeSdk.emitIdle()
    })

    expect(fakeSdk.panToMap).not.toHaveBeenCalled()
    expect(fakeSdk.setZoomMap).not.toHaveBeenCalled()
  })

  it('같은 검색 결과를 다시 선택하면 동일한 위치로 카메라를 다시 이동한다', async () => {
    const fakeSdk = createFakeSdk()
    const onViewportChange = vi.fn()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    const cameraTarget = { latitude: 37.51, longitude: 127.02, zoom: 16 }

    const { rerender } = render(
      <NaverMap
        cameraRequestId={1}
        cameraTarget={cameraTarget}
        onViewportChange={onViewportChange}
      />,
    )
    await waitFor(() => expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce())

    rerender(
      <NaverMap
        cameraRequestId={2}
        cameraTarget={cameraTarget}
        onViewportChange={onViewportChange}
      />,
    )

    expect(fakeSdk.morphMap).toHaveBeenCalledOnce()
    expect(fakeSdk.morphMap).toHaveBeenCalledWith(
      expect.objectContaining({ latitude: 37.51, longitude: 127.02 }),
      16,
    )
    expect(onViewportChange).toHaveBeenCalledWith(expect.objectContaining({
      center: { latitude: 37.51, longitude: 127.02 },
      zoom: 16,
    }))
  })

  it('같은 요청 ID에서 target이 바뀌거나 사용자가 지도를 움직여도 카메라를 되돌리지 않는다', async () => {
    const fakeSdk = createFakeSdk()
    const onViewportChange = vi.fn()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    const { rerender } = render(
      <NaverMap
        cameraRequestId={1}
        cameraTarget={{ latitude: 37.51, longitude: 127.02, zoom: 14 }}
        onViewportChange={onViewportChange}
      />,
    )
    await waitFor(() => expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce())

    act(() => {
      fakeSdk.setCurrentCenter(37.6, 127.1)
      fakeSdk.setCurrentZoom(15)
      fakeSdk.emitIdle()
    })
    rerender(
      <NaverMap
        cameraRequestId={1}
        cameraTarget={{ latitude: 37.52, longitude: 127.03, screenOffset: { x: 200, y: -60 }, zoom: 14 }}
        onViewportChange={onViewportChange}
      />,
    )

    expect(fakeSdk.panToMap).not.toHaveBeenCalled()
    expect(fakeSdk.morphMap).not.toHaveBeenCalled()
    expect(fakeSdk.setZoomMap).not.toHaveBeenCalled()
    expect(onViewportChange).toHaveBeenLastCalledWith(expect.objectContaining({
      center: { latitude: 37.6, longitude: 127.1 }, zoom: 15,
    }))
  })

  it('줌 유지 이동은 화면 offset만큼 보정한 중심으로 한 번 이동한다', async () => {
    const fakeSdk = createFakeSdk()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    const { rerender } = render(<NaverMap cameraRequestId={1} />)
    await waitFor(() => expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce())

    rerender(
      <NaverMap
        cameraRequestId={2}
        cameraTarget={{ latitude: 37.51, longitude: 127.02, screenOffset: { x: 200, y: -60 } }}
      />,
    )

    expect(fakeSdk.panToMap).toHaveBeenCalledOnce()
    expect(fakeSdk.panToMap.mock.calls[0]?.[0]).toMatchObject({
      latitude: expect.closeTo(37.5112), longitude: expect.closeTo(127.016),
    })
    expect(fakeSdk.morphMap).not.toHaveBeenCalled()
    expect(fakeSdk.setZoomMap).not.toHaveBeenCalled()
  })

  it('줌 변경 이동은 도착 줌의 픽셀 크기로 offset을 보정해 한 번 이동한다', async () => {
    const fakeSdk = createFakeSdk()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    const { rerender } = render(<NaverMap cameraRequestId={1} />)
    await waitFor(() => expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce())

    rerender(
      <NaverMap
        cameraRequestId={2}
        cameraTarget={{ latitude: 37.51, longitude: 127.02, screenOffset: { x: 200, y: -60 }, zoom: 16 }}
      />,
    )

    expect(fakeSdk.morphMap).toHaveBeenCalledExactlyOnceWith({
      latitude: expect.closeTo(37.5103), longitude: expect.closeTo(127.019),
    }, 16)
    expect(fakeSdk.panToMap).not.toHaveBeenCalled()
    expect(fakeSdk.setZoomMap).not.toHaveBeenCalled()
  })

  it('초기 offset을 한 번 적용하고 같은 요청 재렌더링과 사용자 idle에서는 반복하지 않는다', async () => {
    const fakeSdk = createFakeSdk()
    const onViewportChange = vi.fn()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    const cameraTarget = {
      latitude: 37.51, longitude: 127.02, screenOffset: { x: 200, y: -60 }, zoom: 14,
    }
    const { rerender } = render(
      <NaverMap cameraRequestId={1} cameraTarget={cameraTarget} onViewportChange={onViewportChange} />,
    )
    await waitFor(() => expect(fakeSdk.morphMap).toHaveBeenCalledOnce())
    expect(fakeSdk.morphMap.mock.calls[0]?.[0]).toMatchObject({
      latitude: expect.closeTo(37.5112), longitude: expect.closeTo(127.016),
    })
    act(() => {
      fakeSdk.setCurrentCenter(37.6, 127.1)
      fakeSdk.emitIdle()
    })
    rerender(
      <NaverMap cameraRequestId={1} cameraTarget={{ ...cameraTarget }} onViewportChange={onViewportChange} />,
    )
    expect(fakeSdk.morphMap).toHaveBeenCalledOnce()

    rerender(
      <NaverMap cameraRequestId={2} cameraTarget={cameraTarget} onViewportChange={onViewportChange} />,
    )
    expect(fakeSdk.morphMap).toHaveBeenCalledTimes(2)
    act(() => fakeSdk.emitIdle())
    expect(onViewportChange).toHaveBeenLastCalledWith(expect.objectContaining({
      center: { latitude: expect.closeTo(37.5112), longitude: expect.closeTo(127.016) }, zoom: 14,
    }))
  })

  it('이미 offset이 반영된 위치를 다시 요청하면 현재 viewport를 즉시 알린다', async () => {
    const fakeSdk = createFakeSdk()
    const onViewportChange = vi.fn()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    const cameraTarget = {
      latitude: 37.51, longitude: 127.02, screenOffset: { x: 200, y: -60 }, zoom: 14,
    }
    const { rerender } = render(
      <NaverMap cameraRequestId={1} cameraTarget={cameraTarget} onViewportChange={onViewportChange} />,
    )
    await waitFor(() => expect(fakeSdk.morphMap).toHaveBeenCalledOnce())

    rerender(
      <NaverMap cameraRequestId={2} cameraTarget={cameraTarget} onViewportChange={onViewportChange} />,
    )

    expect(onViewportChange).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({
      center: { latitude: expect.closeTo(37.5112), longitude: expect.closeTo(127.016) }, zoom: 14,
    }))
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

    await waitFor(() => expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce())
    expect(fakeSdk.panToMap).not.toHaveBeenCalled()
    expect(fakeSdk.setZoomMap).not.toHaveBeenCalled()

    rerender(
      <NaverMap
        cameraTarget={{
          latitude: 37.510004,
          longitude: 127.020004,
          zoom: 15.004,
        }}
      />,
    )

    expect(fakeSdk.panToMap).not.toHaveBeenCalled()
    expect(fakeSdk.setZoomMap).not.toHaveBeenCalled()

    rerender(
      <NaverMap
        cameraTarget={{
          latitude: 37.51002,
          longitude: 127.020004,
          zoom: 15.004,
        }}
      />,
    )

    expect(fakeSdk.panToMap).toHaveBeenCalledOnce()
    expect(fakeSdk.setZoomMap).not.toHaveBeenCalled()

    rerender(
      <NaverMap
        cameraTarget={{
          latitude: 37.51002,
          longitude: 127.02002,
          zoom: 15.004,
        }}
      />,
    )

    expect(fakeSdk.panToMap).toHaveBeenCalledTimes(2)
    expect(fakeSdk.setZoomMap).not.toHaveBeenCalled()

    rerender(
      <NaverMap
        cameraTarget={{
          latitude: 37.51002,
          longitude: 127.02002,
          zoom: 15.02,
        }}
      />,
    )

    expect(fakeSdk.panToMap).toHaveBeenCalledTimes(2)
    expect(fakeSdk.setZoomMap).toHaveBeenCalledOnce()
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
        ...markerPresentation,
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
    expect(fakeSdk.markerConstructor).toHaveBeenCalledOnce()

    const markerButton = createdMarkerButton(fakeSdk, 0)
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

    const mapSurface = document.querySelector('.map-surface')
    if (!(mapSurface instanceof HTMLElement)) {
      throw new Error('지도 surface를 찾을 수 없습니다.')
    }
    const removeWheelListener = vi.spyOn(mapSurface, 'removeEventListener')
    unmount()

    expect(fakeSdk.markerSetMap).toHaveBeenCalledOnce()
    expect(fakeSdk.markerSetMap).toHaveBeenCalledWith(null)
    expect(fakeSdk.removeListener).toHaveBeenCalledTimes(3)
    expect(fakeSdk.removeListener).toHaveBeenCalledWith(
      expect.objectContaining({ eventName: 'init' }),
    )
    expect(removeWheelListener).toHaveBeenCalledWith(
      'wheel',
      expect.any(Function),
    )
  })

  it('개별 marker hover와 focus를 카드에 전달하고 강조만 바뀌면 DOM을 유지한다', async () => {
    const fakeSdk = createFakeSdk()
    const onMarkerHighlight = vi.fn()
    const nextMarkerHighlight = vi.fn()
    const marker = {
      ...markerPresentation,
      id: '101',
      latitude: 37.6,
      longitude: 127,
      name: '테스트 단지',
    } satisfies NaverMapMarker
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)

    const { rerender } = render(
      <NaverMap markers={[marker]} onMarkerHighlight={onMarkerHighlight} />,
    )

    await waitFor(() =>
      expect(fakeSdk.markerConstructor).toHaveBeenCalledOnce(),
    )
    const markerButton = createdMarkerButton(fakeSdk, 0)

    fireEvent.mouseEnter(markerButton)
    markerButton.focus()
    fireEvent.mouseLeave(markerButton)

    expect(onMarkerHighlight).toHaveBeenLastCalledWith('101')

    rerender(
      <NaverMap
        markers={[{ ...marker, highlighted: true }]}
        onMarkerHighlight={nextMarkerHighlight}
      />,
    )

    expect(fakeSdk.markerConstructor).toHaveBeenCalledOnce()
    expect(markerButton).toHaveClass('is-highlighted')
    expect(fakeSdk.markerSetZIndex).toHaveBeenLastCalledWith(20)
    act(() => fakeSdk.emitIdle())
    expect(fakeSdk.markerConstructor).toHaveBeenCalledOnce()
    expect(markerButton).toHaveFocus()

    markerButton.blur()

    expect(nextMarkerHighlight).toHaveBeenLastCalledWith(null)

    nextMarkerHighlight.mockClear()
    fireEvent.mouseEnter(markerButton)
    rerender(
      <NaverMap
        markers={[{ ...marker, latitude: 37.61 }]}
        onMarkerHighlight={nextMarkerHighlight}
      />,
    )

    expect(nextMarkerHighlight).toHaveBeenLastCalledWith(null)
    expect(fakeSdk.markerConstructor).toHaveBeenCalledTimes(2)
  })

  it.each(['individual', 'aggregate', 'cluster'] as const)(
    '%s marker의 Enter와 Space 입력 양쪽 단계를 지도에 전달하지 않고 기본 동작은 보존한다',
    async (kind) => {
      const fakeSdk = createFakeSdk()
      const markers = [{
        ...markerPresentation, id: '101', latitude: 37.6, longitude: 127, name: '키보드 단지',
      }]
      loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
      render(kind === 'aggregate'
        ? <NaverMap markerRenderMode="server" representation="AGGREGATE"
            aggregateMarkers={[aggregateMarker]} onAggregateMarkerSelect={vi.fn()} />
        : <NaverMap markers={kind === 'cluster'
            ? [...markers, { ...markers[0], id: '102' }]
            : markers} />)
      await waitFor(() => expect(fakeSdk.markerConstructor).toHaveBeenCalledOnce())
      const markerButton = createdMarkerButton(fakeSdk, 0)
      const mapKeyboard = vi.fn()
      document.body.addEventListener('keydown', mapKeyboard)
      document.body.addEventListener('keyup', mapKeyboard)
      try {
        for (const key of ['Enter', ' ']) {
          for (const type of ['keydown', 'keyup']) {
            const event = new KeyboardEvent(type, {
              key,
              bubbles: true,
              cancelable: true,
            })
            fireEvent(markerButton, event)
            expect(event.defaultPrevented).toBe(false)
          }
        }
        expect(mapKeyboard).not.toHaveBeenCalled()
      } finally {
        document.body.removeEventListener('keydown', mapKeyboard)
        document.body.removeEventListener('keyup', mapKeyboard)
      }
    },
  )

  it.each(['individual', 'aggregate', 'cluster'] as const)(
    '%s marker는 키보드와 포인터 클릭을 SDK 좌표 처리 전에 한 번만 선택한다',
    async (kind) => {
      const fakeSdk = createFakeSdk()
      const onMarkerSelect = vi.fn()
      const selection = kind === 'cluster' ? fakeSdk.fitBoundsMap : onMarkerSelect
      const markers = [{
        ...markerPresentation, id: '101', latitude: 37.6, longitude: 127, name: '클릭 단지',
      }]
      loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
      render(kind === 'aggregate'
        ? <NaverMap markerRenderMode="server" representation="AGGREGATE"
            aggregateMarkers={[aggregateMarker]} onAggregateMarkerSelect={onMarkerSelect} />
        : <NaverMap markers={kind === 'cluster'
            ? [...markers, { ...markers[0], id: '102' }]
            : markers} onMarkerSelect={onMarkerSelect} />)
      await waitFor(() => expect(fakeSdk.markerConstructor).toHaveBeenCalledOnce())
      const markerButton = createdMarkerButton(fakeSdk, 0)
      const sdkPointerClick = vi.fn()
      markerButton.addEventListener('click', sdkPointerClick)
      markerButton.parentElement?.parentElement?.addEventListener('click', sdkPointerClick)

      for (const detail of [0, 1]) {
        selection.mockClear()
        fireEvent.click(markerButton, { detail })
        expect(selection).toHaveBeenCalledOnce()
        if (kind === 'individual') {
          expect(onMarkerSelect).toHaveBeenCalledWith('101')
        }
      }
      expect(sdkPointerClick).not.toHaveBeenCalled()
    },
  )

  it.each(['server', 'legacy'] as const)(
    '%s 경로에서 선택만 변경하면 마커와 포커스를 유지하고 필요한 표시만 갱신한다',
    async (mode) => {
      const fakeSdk = createFakeSdk()
      const onMarkerSelect = vi.fn()
      const nextMarkerSelect = vi.fn()
      const markers = ['a', 'b', 'c'].map((id, index) => ({
        ...markerPresentation,
        id,
        latitude: 37.5,
        longitude: 127 + index * 0.01,
        name: `${id} 단지`,
        highlighted: id === 'b',
      }))
      loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
      function renderMap(selectedId: string | null, onSelect = onMarkerSelect) {
        const nextMarkers = markers.map((marker) => ({
          ...marker,
          selected: marker.id === selectedId,
        }))
        return mode === 'server'
          ? <NaverMap
              markerRenderMode="server"
              representation="INDIVIDUAL"
              markers={nextMarkers}
              onMarkerSelect={onSelect}
            />
          : <NaverMap markers={nextMarkers} onMarkerSelect={onSelect} />
      }
      const { rerender, unmount } = render(renderMap(null))
      await waitFor(() => expect(fakeSdk.markerConstructor).toHaveBeenCalledTimes(3))
      const buttons = [0, 1, 2].map((index) => createdMarkerButton(fakeSdk, index))
      const [a, b, c] = buttons
      const [aOverlay, bOverlay, cOverlay] = fakeSdk.markerInstances
      buttons.forEach((button) => expect(button).toHaveAttribute('aria-pressed', 'false'))
      c.focus()
      const focus = vi.spyOn(HTMLButtonElement.prototype, 'focus')
      fakeSdk.markerInstances.forEach(({ setZIndex }) => setZIndex.mockClear())

      rerender(renderMap('a'))
      expect(fakeSdk.markerConstructor).toHaveBeenCalledTimes(3)
      expect(fakeSdk.markerSetMap).not.toHaveBeenCalled()
      expect(a).toHaveClass('is-selected')
      expect(a).toHaveAttribute('aria-pressed', 'true')
      expect(aOverlay.setZIndex).toHaveBeenLastCalledWith(30)
      expect(bOverlay.setZIndex).not.toHaveBeenCalled()
      expect(cOverlay.setZIndex).not.toHaveBeenCalled()

      rerender(renderMap('b', nextMarkerSelect))
      expect(a).not.toHaveClass('is-selected')
      expect(a).toHaveAttribute('aria-pressed', 'false')
      expect(aOverlay.setZIndex).toHaveBeenLastCalledWith(10)
      expect(b).toHaveClass('is-selected', 'is-highlighted')
      expect(b).toHaveAttribute('aria-pressed', 'true')
      expect(bOverlay.setZIndex).toHaveBeenLastCalledWith(30)
      fireEvent.click(b)
      expect(nextMarkerSelect).toHaveBeenCalledExactlyOnceWith('b')
      expect(onMarkerSelect).not.toHaveBeenCalled()

      rerender(renderMap(null))
      expect(b).not.toHaveClass('is-selected')
      expect(b).toHaveAttribute('aria-pressed', 'false')
      expect(b).toHaveClass('is-highlighted')
      expect(bOverlay.setZIndex).toHaveBeenLastCalledWith(20)
      const updateCount = fakeSdk.markerSetZIndex.mock.calls.length
      rerender(renderMap(null))
      act(() => fakeSdk.emitIdle())
      expect(fakeSdk.markerSetZIndex).toHaveBeenCalledTimes(updateCount)
      expect(cOverlay.setZIndex).not.toHaveBeenCalled()
      expect(fakeSdk.markerConstructor).toHaveBeenCalledTimes(3)
      expect(fakeSdk.markerSetMap).not.toHaveBeenCalled()
      buttons.forEach((button) => expect(button).toBeInTheDocument())
      expect(c).toHaveFocus()
      expect(focus).not.toHaveBeenCalled()
      focus.mockRestore()

      unmount()
      fakeSdk.markerInstances.forEach(({ setMap }) => {
        expect(setMap).toHaveBeenCalledExactlyOnceWith(null)
      })
    },
  )

  it('선택 이후 idle과 동일 데이터에서는 마커를 유지하고 실제 데이터 변경은 반영한다', async () => {
    const fakeSdk = createFakeSdk()
    const marker = {
      ...markerPresentation,
      id: 'a',
      latitude: 37.5,
      longitude: 127,
      name: 'a 단지',
      selected: true,
    }
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    const { rerender } = render(
      <NaverMap markerRenderMode="server" representation="INDIVIDUAL" markers={[marker]} />,
    )
    await waitFor(() => expect(fakeSdk.markerConstructor).toHaveBeenCalledOnce())
    const firstButton = createdMarkerButton(fakeSdk, 0)
    expect(firstButton).toHaveClass('is-selected')
    expect(fakeSdk.markerInstances[0].setZIndex).toHaveBeenLastCalledWith(30)
    rerender(<NaverMap markerRenderMode="server" representation="INDIVIDUAL"
      markers={[{ ...marker, selected: false }]} />)
    rerender(<NaverMap markerRenderMode="server" representation="INDIVIDUAL"
      markers={[{ ...marker }]} />)
    act(() => fakeSdk.emitIdle())
    expect(fakeSdk.markerConstructor).toHaveBeenCalledOnce()
    expect(fakeSdk.markerSetMap).not.toHaveBeenCalled()
    expect(firstButton).toHaveClass('is-selected')

    rerender(<NaverMap markerRenderMode="server" representation="INDIVIDUAL"
      markers={[{ ...marker, monthlyRent: {
        digits: '24', unit: '만', exactLabel: '240,000원',
      } }]} />)
    expect(fakeSdk.markerConstructor).toHaveBeenCalledTimes(2)
    expect(firstButton).not.toBeInTheDocument()
    expect(createdMarkerButton(fakeSdk, 1)).toHaveTextContent('월24만~')
    expect(createdMarkerButton(fakeSdk, 1)).toHaveClass('is-selected')
    expect(fakeSdk.markerInstances[1].setZIndex).toHaveBeenLastCalledWith(30)
    rerender(<NaverMap markerRenderMode="server" representation="INDIVIDUAL" markers={[]} />)
    expect(fakeSdk.markerSetMap).toHaveBeenCalledTimes(2)
    expect(createdMarkerButton(fakeSdk, 1)).not.toBeInTheDocument()
  })

  it('기존 군집 경로에서 선택으로 묶음 구성이 바뀌면 분리와 재결합을 유지한다', async () => {
    const fakeSdk = createFakeSdk()
    const markers = ['a', 'b'].map((id) => ({
      ...markerPresentation,
      id,
      latitude: 37.5,
      longitude: 127,
      name: `${id} 단지`,
    }))
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    const { rerender } = render(<NaverMap markers={markers} />)
    await waitFor(() => expect(fakeSdk.markerConstructor).toHaveBeenCalledOnce())
    expect(createdMarkerButton(fakeSdk, 0)).toHaveClass('housing-map-cluster')
    rerender(<NaverMap markers={markers.map((marker) => ({
      ...marker, selected: marker.id === 'a',
    }))} />)
    expect(fakeSdk.markerConstructor).toHaveBeenCalledTimes(3)
    expect(createdMarkerButton(fakeSdk, 1)).toHaveClass('is-selected')
    rerender(<NaverMap markers={markers} />)
    expect(fakeSdk.markerConstructor).toHaveBeenCalledTimes(4)
    expect(createdMarkerButton(fakeSdk, 3)).toHaveClass('housing-map-cluster')
    expect(fakeSdk.markerSetMap).toHaveBeenCalledTimes(3)
  })

  it('처음 표시하는 마커는 SDK 위치 요소 안에서 시간차를 두고 등장하고 효과를 정리한다', async () => {
    const fakeSdk = createFakeSdk()
    const markers = Array.from({ length: 8 }, (_, index) => ({
      ...markerPresentation,
      id: String(index),
      latitude: 37.5,
      longitude: 127 + index * 0.01,
      name: `${index} 단지`,
    }))
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    render(<NaverMap markerRenderMode="server" representation="INDIVIDUAL" markers={markers} />)

    const buttons = await screen.findAllByRole('button', { name: /단지 상세 보기/ })
    const expectedDelays = ['0ms', '10ms', '20ms', '30ms', '40ms', '40ms', '40ms', '40ms']
    buttons.forEach((button, index) => {
      const motion = button.parentElement
      expect(motion).toHaveClass('housing-marker-enter')
      expect(motion?.parentElement).toHaveClass('housing-marker-content')
      expect(motion?.style.getPropertyValue('--marker-enter-delay')).toBe(expectedDelays[index])
    })
    const firstMotion = buttons[0].parentElement!
    fireEvent.animationEnd(firstMotion)
    expect(firstMotion).not.toHaveClass('housing-marker-enter')
    expect(firstMotion.style.getPropertyValue('--marker-enter-delay')).toBe('')
    const secondMotion = buttons[1].parentElement!
    fireEvent(secondMotion, new Event('animationcancel', { bubbles: true }))
    expect(secondMotion).not.toHaveClass('housing-marker-enter')
  })

  it('조회 결과 일부가 바뀌면 기존 마커와 포커스를 유지하고 새 마커만 등장한다', async () => {
    const fakeSdk = createFakeSdk()
    const onMarkerSelect = vi.fn()
    const nextMarkerSelect = vi.fn()
    const onMarkerHighlight = vi.fn()
    const [a, b, c] = ['a', 'b', 'c'].map((id, index) => ({
      ...markerPresentation,
      id,
      latitude: 37.5,
      longitude: 127 + index * 0.01,
      name: `${id} 단지`,
    }))
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    const { rerender, unmount } = render(
      <NaverMap markerRenderMode="server" representation="INDIVIDUAL" markers={[a, b]}
        onMarkerSelect={onMarkerSelect} onMarkerHighlight={onMarkerHighlight} />,
    )
    const aButton = await screen.findByRole('button', { name: /^a 단지,/ })
    const bButton = screen.getByRole('button', { name: /^b 단지,/ })
    fireEvent.animationEnd(aButton.parentElement!)
    aButton.focus()
    const focus = vi.spyOn(HTMLButtonElement.prototype, 'focus')

    rerender(<NaverMap markerRenderMode="server" representation="INDIVIDUAL" markers={[a, c]}
      onMarkerSelect={nextMarkerSelect} onMarkerHighlight={onMarkerHighlight} />)

    expect(screen.getByRole('button', { name: /^a 단지,/ })).toBe(aButton)
    expect(aButton).toHaveFocus()
    expect(focus).not.toHaveBeenCalled()
    expect(onMarkerHighlight).toHaveBeenLastCalledWith('a')
    expect(aButton.parentElement).not.toHaveClass('housing-marker-enter')
    expect(bButton).not.toBeInTheDocument()
    fireEvent.click(bButton)
    expect(onMarkerSelect).not.toHaveBeenCalled()
    expect(nextMarkerSelect).not.toHaveBeenCalled()
    fireEvent.click(aButton)
    expect(nextMarkerSelect).toHaveBeenCalledExactlyOnceWith('a')
    const cButton = screen.getByRole('button', { name: /^c 단지,/ })
    expect(cButton.parentElement).toHaveClass('housing-marker-enter')
    expect(cButton.parentElement?.style.getPropertyValue('--marker-enter-delay')).toBe('0ms')

    rerender(<NaverMap markerRenderMode="server" representation="INDIVIDUAL" markers={[
      { ...a, selected: true, highlighted: true },
      { ...c, monthlyRent: { digits: '25', unit: '만', exactLabel: '250,000원' } },
    ]} onMarkerSelect={nextMarkerSelect} onMarkerHighlight={onMarkerHighlight} />)
    act(() => fakeSdk.emitIdle())
    expect(screen.getByRole('button', { name: /^a 단지,/ })).toBe(aButton)
    expect(aButton).toHaveClass('is-selected', 'is-highlighted')
    expect(aButton.parentElement).not.toHaveClass('housing-marker-enter')
    const updatedC = screen.getByRole('button', { name: /^c 단지,/ })
    expect(updatedC).toHaveTextContent('월25만~')
    expect(updatedC.parentElement).not.toHaveClass('housing-marker-enter')
    focus.mockRestore()
    unmount()
    nextMarkerSelect.mockClear()
    fireEvent.click(aButton)
    fireEvent.click(updatedC)
    expect(nextMarkerSelect).not.toHaveBeenCalled()
  })

  it('모션 감소 설정에서는 새 마커도 등장 효과 없이 바로 표시한다', async () => {
    const fakeSdk = createFakeSdk()
    vi.stubGlobal('matchMedia', (query: string) => ({ matches: query === '(prefers-reduced-motion: reduce)' }))
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    render(<NaverMap markers={[{
      ...markerPresentation, id: 'a', latitude: 37.5, longitude: 127, name: 'a 단지',
    }]} />)
    const button = await screen.findByRole('button', { name: /^a 단지,/ })
    expect(button.parentElement).not.toHaveClass('housing-marker-enter')
    expect(button.parentElement?.style.getPropertyValue('--marker-enter-delay')).toBe('')
  })

  it('hover 중인 마커가 제거되면 유지된 키보드 포커스 마커의 강조를 복원한다', async () => {
    const fakeSdk = createFakeSdk()
    const markers = ['a', 'b'].map((id, index) => ({
      ...markerPresentation, id, latitude: 37.5, longitude: 127 + index * 0.01, name: `${id} 단지`,
    }))
    function HighlightedMap({ items }: { items: NaverMapMarker[] }) {
      const [highlightedId, setHighlightedId] = useState<string | null>(null)
      return <NaverMap markerRenderMode="server" representation="INDIVIDUAL"
        markers={items.map((marker) => ({ ...marker, highlighted: marker.id === highlightedId }))}
        onMarkerHighlight={setHighlightedId} />
    }
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    const { rerender } = render(<HighlightedMap items={markers} />)
    const a = await screen.findByRole('button', { name: /^a 단지,/ })
    const b = screen.getByRole('button', { name: /^b 단지,/ })
    act(() => a.focus())
    expect(a).toHaveClass('is-highlighted')
    fireEvent.mouseEnter(b)
    expect(b).toHaveClass('is-highlighted')
    expect(a).not.toHaveClass('is-highlighted')
    rerender(<HighlightedMap items={[markers[0]]} />)
    expect(screen.getByRole('button', { name: /^a 단지,/ })).toBe(a)
    expect(a).toHaveFocus()
    expect(a).toHaveClass('is-highlighted')
    expect(b).not.toBeInTheDocument()
  })

  it('같은 집계의 값은 재등장 없이 갱신하고 다른 마커를 유지하며 최신 확대 정보를 전달한다', async () => {
    const fakeSdk = createFakeSdk()
    const onSelect = vi.fn()
    const second = { ...aggregateMarker, groupKey: 'METROPOLITAN:11', groupLabel: '서울' }
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    const { rerender } = render(<NaverMap markerRenderMode="server" representation="AGGREGATE"
      aggregateMarkers={[aggregateMarker, second]} onAggregateMarkerSelect={onSelect} />)
    const seoulButton = await screen.findByRole('button', { name: /^서울 42곳,/ })
    fireEvent.animationEnd(seoulButton.parentElement!)
    const updated = { ...aggregateMarker, uniqueComplexCount: 17, expansionZoom: 13, latitude: 37.6 }
    rerender(<NaverMap markerRenderMode="server" representation="AGGREGATE"
      aggregateMarkers={[updated, second]} onAggregateMarkerSelect={onSelect} />)
    expect(screen.getByRole('button', { name: /^서울 42곳,/ })).toBe(seoulButton)
    const updatedButton = screen.getByRole('button', { name: /^경기 17곳,/ })
    expect(updatedButton.parentElement).not.toHaveClass('housing-marker-enter')
    fireEvent.click(updatedButton)
    expect(onSelect).toHaveBeenCalledExactlyOnceWith(updated)

    rerender(<NaverMap markerRenderMode="server" representation="INDIVIDUAL" markers={[{
      ...markerPresentation, id: second.groupKey, latitude: 37.5, longitude: 127, name: '개별 단지',
    }]} />)
    expect(screen.getByRole('button', { name: /^개별 단지,/ }).parentElement)
      .toHaveClass('housing-marker-enter')
  })

  it('기존 군집의 선택이나 줌만 바뀌면 등장 효과를 재생하지 않고 새 조회 마커에만 적용한다', async () => {
    const fakeSdk = createFakeSdk()
    const markers = ['a', 'b'].map((id, index) => ({
      ...markerPresentation, id, latitude: 37.5, longitude: 127 + index * 0.001, name: `${id} 단지`,
    }))
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    const { rerender } = render(<NaverMap markers={markers} />)
    const cluster = await screen.findByRole('button', { name: '2곳 단지 묶음, 확대해서 보기' })
    expect(cluster.parentElement).toHaveClass('housing-marker-enter')
    rerender(<NaverMap markers={markers.map((marker) => ({ ...marker, selected: marker.id === 'a' }))} />)
    screen.getAllByRole('button', { name: /단지 상세 보기/ }).forEach((button) => {
      expect(button.parentElement).not.toHaveClass('housing-marker-enter')
    })
    rerender(<NaverMap markers={markers} />)
    expect(screen.getByRole('button', { name: '2곳 단지 묶음, 확대해서 보기' }).parentElement)
      .not.toHaveClass('housing-marker-enter')
    act(() => {
      fakeSdk.setCurrentZoom(15)
      fakeSdk.emitIdle()
    })
    screen.getAllByRole('button', { name: /단지 상세 보기/ }).forEach((button) => {
      expect(button.parentElement).not.toHaveClass('housing-marker-enter')
    })
    rerender(<NaverMap markers={[...markers, {
      ...markerPresentation, id: 'c', latitude: 37.5, longitude: 127.1, name: 'c 단지',
    }]} />)
    expect(screen.getByRole('button', { name: /^c 단지,/ }).parentElement)
      .toHaveClass('housing-marker-enter')
    expect(screen.getByRole('button', { name: /^a 단지,/ }).parentElement)
      .not.toHaveClass('housing-marker-enter')
  })

  it('재사용한 군집을 선택하면 새 결과가 먼저 와도 다음 idle까지 확대 완료를 안내하지 않는다', async () => {
    const fakeSdk = createFakeSdk()
    const markers = ['a', 'b'].map((id) => ({
      ...markerPresentation, id, latitude: 37.5, longitude: 127, name: `${id} 단지`,
    }))
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    const { rerender } = render(<NaverMap markers={markers} />)
    const cluster = await screen.findByRole('button', { name: '2곳 단지 묶음, 확대해서 보기' })
    act(() => fakeSdk.emitIdle())
    fireEvent.click(cluster)
    rerender(<NaverMap markers={[...markers, {
      ...markerPresentation, id: 'c', latitude: 37.5, longitude: 127.1, name: 'c 단지',
    }]} />)
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '2곳 단지 묶음, 확대해서 보기' })).toBe(cluster)
    act(() => fakeSdk.emitIdle())
    expect(await screen.findByRole('status')).toHaveTextContent('아직 함께 표시됩니다.')
    await waitFor(() => expect(cluster).toHaveFocus())
  })

  it('군집 중심이 같아도 구성원 좌표가 갱신되면 최신 좌표로 확대한다', async () => {
    const fakeSdk = createFakeSdk()
    const markers = ['a', 'b'].map((id, index) => ({
      ...markerPresentation, id, latitude: 37.5, longitude: 127 + index * 0.001, name: `${id} 단지`,
    }))
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    const { rerender } = render(<NaverMap markers={markers} />)
    const previousCluster = await screen.findByRole('button', { name: '2곳 단지 묶음, 확대해서 보기' })
    fireEvent.animationEnd(previousCluster.parentElement!)
    rerender(<NaverMap markers={[
      { ...markers[0], latitude: 37.4999 },
      { ...markers[1], latitude: 37.5001 },
    ]} />)
    const cluster = screen.getByRole('button', { name: '2곳 단지 묶음, 확대해서 보기' })
    expect(cluster.parentElement).not.toHaveClass('housing-marker-enter')
    fireEvent.click(cluster)
    expect(fakeSdk.fitBoundsMap.mock.calls.at(-1)?.[0]).toEqual([
      { latitude: 37.4999, longitude: 127 },
      { latitude: 37.5001, longitude: 127.001 },
    ])
  })

  it('개별 marker에 짧은 기관·유형과 최소 보증금·월세를 표시하고 정확한 값으로 안내한다', async () => {
    const fakeSdk = createFakeSdk()
    const marker = {
      ...markerPresentation,
      highlighted: true,
      id: '101',
      latitude: 37.6,
      longitude: 127,
      name: '서울 공공임대 1단지',
      selected: true,
    } satisfies NaverMapMarker
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)

    render(<NaverMap markers={[marker]} />)

    await waitFor(() =>
      expect(fakeSdk.markerConstructor).toHaveBeenCalledOnce(),
    )
    const markerButton = createdMarkerButton(fakeSdk, 0)

    expect(markerButton).toHaveTextContent('보1000만~')
    expect(markerButton).toHaveTextContent('월23.4만~')
    expect(markerButton).not.toHaveTextContent('㎡')
    expect(markerButton).toHaveAccessibleName(
      '서울 공공임대 1단지, 한국토지주택공사 · 국민임대, 보증금 최소 10,000,000원, 월 임대료 최소 234,000원, 단지 상세 보기',
    )
    expect(markerButton).toHaveAttribute('title',
      '서울 공공임대 1단지, 한국토지주택공사 · 국민임대, 보증금 최소 10,000,000원, 월 임대료 최소 234,000원',
    )
    expect(markerButton).toHaveAttribute('aria-pressed', 'true')
    expect(markerButton).toHaveClass('is-highlighted', 'is-selected')
    await waitFor(() =>
      expect(fakeSdk.markerSetZIndex).toHaveBeenLastCalledWith(30),
    )
    expect(Array.from(markerButton.querySelectorAll('.housing-map-marker__name'))
      .map((node) => node.textContent)).toEqual(['LH', '국민'])
    expect(markerButton.querySelector('.housing-map-marker__body')).toHaveTextContent(
      '보1000만~월23.4만~',
    )
    expect(fakeSdk.markerConstructor.mock.calls[0]?.[0]).toMatchObject({
      icon: { anchor: { x: 48, y: 66 }, size: { width: 96, height: 66 } },
    })
  })

  it('marker 표시 문구가 바뀌면 overlay를 새 정보로 교체한다', async () => {
    const fakeSdk = createFakeSdk()
    const marker = {
      ...markerPresentation,
      id: '101',
      latitude: 37.6,
      longitude: 127,
      name: '테스트 단지',
    } satisfies NaverMapMarker
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)

    const { rerender } = render(<NaverMap markers={[marker]} />)

    await waitFor(() =>
      expect(fakeSdk.markerConstructor).toHaveBeenCalledOnce(),
    )
    const previousButton = createdMarkerButton(fakeSdk, 0)

    rerender(
      <NaverMap
        markers={[{
          ...marker,
          deposit: { digits: '1', unit: '억', exactLabel: '100,000,000원' },
          monthlyRent: { digits: '24', unit: '만', exactLabel: '240,000원' },
          rentalTypeLabel: '통합',
          rentalTypeName: '통합공공임대',
        }]}
      />,
    )

    await waitFor(() =>
      expect(fakeSdk.markerConstructor).toHaveBeenCalledTimes(2),
    )
    const nextButton = createdMarkerButton(fakeSdk, 1)
    expect(previousButton).not.toBeInTheDocument()
    expect(nextButton).toHaveTextContent('LH통합')
    expect(nextButton).toHaveTextContent('보1억~')
    expect(nextButton).toHaveTextContent('월24만~')
    expect(nextButton).toHaveAccessibleName(
      '테스트 단지, 한국토지주택공사 · 통합공공임대, 보증금 최소 100,000,000원, 월 임대료 최소 240,000원, 단지 상세 보기',
    )
  })

  it.each<{
    description: string
    change: Partial<MapMarkerPresentation>
    expected: string
  }>([
    { description: '기관 원문', change: { agencyName: '지역 주택 공사' }, expected: '지역 주택 공사' },
    { description: '임대유형 원문', change: { rentalTypeName: '국민임대주택' }, expected: '국민임대주택' },
    {
      description: '정확한 보증금',
      change: { deposit: { ...markerPresentation.deposit, exactLabel: '10,000,100원' } },
      expected: '보증금 최소 10,000,100원',
    },
    {
      description: '정확한 월 임대료',
      change: { monthlyRent: { ...markerPresentation.monthlyRent, exactLabel: '234,100원' } },
      expected: '월 임대료 최소 234,100원',
    },
  ])('보이는 축약값이 같아도 $description 변경을 접근성 이름과 툴팁에 반영한다', async ({ change, expected }) => {
    const fakeSdk = createFakeSdk()
    const marker = {
      ...markerPresentation,
      id: '101',
      latitude: 37.6,
      longitude: 127,
      name: '정확한 금액 단지',
    }
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    const { rerender } = render(
      <NaverMap markerRenderMode="server" representation="INDIVIDUAL" markers={[marker]} />,
    )
    await waitFor(() => expect(fakeSdk.markerConstructor).toHaveBeenCalledOnce())
    const previousButton = createdMarkerButton(fakeSdk, 0)

    rerender(<NaverMap markerRenderMode="server" representation="INDIVIDUAL"
      markers={[{ ...marker, ...change }]} />)

    expect(fakeSdk.markerConstructor).toHaveBeenCalledTimes(2)
    expect(previousButton).not.toBeInTheDocument()
    const nextButton = createdMarkerButton(fakeSdk, 1)
    expect(nextButton.textContent).toBe(previousButton.textContent)
    expect(nextButton).toHaveAccessibleName(expect.stringContaining(expected))
    expect(nextButton.title).toContain(expected)
  })

  it('금액 결측은 정보 없음으로 표시하고 확인된 0원과 구분한다', async () => {
    const fakeSdk = createFakeSdk()
    const marker: NaverMapMarker = {
      ...markerPresentation,
      deposit: null,
      monthlyRent: null,
      id: '101',
      latitude: 37.6,
      longitude: 127,
      name: '금액 확인 단지',
    }
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    const { rerender } = render(<NaverMap markers={[marker]} />)
    await waitFor(() => expect(fakeSdk.markerConstructor).toHaveBeenCalledOnce())
    const missingButton = createdMarkerButton(fakeSdk, 0)
    expect(missingButton).toHaveTextContent('보정보 없음월정보 없음')
    expect(missingButton).not.toHaveTextContent('~')
    expect(missingButton).toHaveAccessibleName(
      '금액 확인 단지, 한국토지주택공사 · 국민임대, 보증금 정보 없음, 월 임대료 정보 없음, 단지 상세 보기',
    )

    rerender(<NaverMap markers={[{
      ...marker,
      deposit: { digits: '0', unit: '원', exactLabel: '0원' },
    }]} />)

    const zeroButton = createdMarkerButton(fakeSdk, 1)
    expect(zeroButton).toHaveTextContent('보0원~월정보 없음')
    expect(zeroButton.querySelectorAll('.housing-map-marker__from')).toHaveLength(1)
    expect(zeroButton).toHaveAccessibleName(
      '금액 확인 단지, 한국토지주택공사 · 국민임대, 보증금 최소 0원, 월 임대료 정보 없음, 단지 상세 보기',
    )
  })

  it('화면에서 가까운 단지만 64px cluster로 묶는다', async () => {
    const fakeSdk = createFakeSdk()
    const markers = [
      {
        ...markerPresentation,
        id: 'near-a',
        latitude: 37.5,
        longitude: 127,
        name: '가까운 첫 단지',
      },
      {
        ...markerPresentation,
        id: 'near-b',
        latitude: 37.5,
        longitude: 127.001,
        name: '가까운 둘째 단지',
      },
      {
        ...markerPresentation,
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
    expect(farMarkerButton).toHaveAccessibleName(
      '먼 단지, 한국토지주택공사 · 국민임대, 보증금 최소 10,000,000원, 월 임대료 최소 234,000원, 단지 상세 보기',
    )
    expect(fakeSdk.fromCoordToOffset).toHaveBeenCalledTimes(3)
  })

  it('서버 집계 marker를 화면에서 다시 묶지 않고 0곳도 각각 표시한다', async () => {
    const fakeSdk = createFakeSdk()
    const onAggregateMarkerSelect = vi.fn()
    const emptyMarker = {
      ...aggregateMarker,
      groupKey: 'METROPOLITAN:11',
      groupLabel: '서울',
      latitude: 37.5666,
      longitude: 126.9784,
      uniqueComplexCount: 0,
    } satisfies NaverMapAggregateMarker
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)

    render(
      <NaverMap
        aggregateMarkers={[aggregateMarker, emptyMarker]}
        markerRenderMode="server"
        onAggregateMarkerSelect={onAggregateMarkerSelect}
        representation="AGGREGATE"
      />,
    )

    await waitFor(() =>
      expect(fakeSdk.markerConstructor).toHaveBeenCalledTimes(2),
    )
    const buttons = [
      createdMarkerButton(fakeSdk, 0),
      createdMarkerButton(fakeSdk, 1),
    ]
    const seoulButton = buttons.find(
      (button) => button.dataset.groupKey === 'METROPOLITAN:11',
    )
    const gyeonggiButton = buttons.find(
      (button) => button.dataset.groupKey === 'METROPOLITAN:41',
    )
    if (!seoulButton || !gyeonggiButton) {
      throw new Error('서버 집계 marker 버튼을 찾을 수 없습니다.')
    }

    expect(seoulButton).toHaveTextContent('서울')
    expect(seoulButton).toHaveTextContent('0곳')
    expect(seoulButton).toHaveAccessibleName(
      '서울 0곳, 다음 지역 단계로 확대해서 보기',
    )
    expect(seoulButton).toHaveAttribute('title', '서울 0곳')
    expect(seoulButton).toHaveAttribute(
      'data-aggregate-marker-id',
      'METROPOLITAN:11',
    )
    expect(seoulButton).toHaveAttribute('data-next-stage', '3')
    expect(seoulButton).toHaveAttribute('data-expansion-zoom', '11')
    expect(seoulButton).toHaveAttribute('data-unique-complex-count', '0')
    expect(gyeonggiButton).toHaveTextContent('경기42곳')
    expect(fakeSdk.fromCoordToOffset).not.toHaveBeenCalled()

    fireEvent.click(seoulButton)

    expect(onAggregateMarkerSelect).toHaveBeenCalledWith(emptyMarker)
    expect(fakeSdk.fitBoundsMap).not.toHaveBeenCalled()
  })

  it('서버 집계 전환 중 idle마다 다음 사용자 입력 취소를 다시 허용한다', async () => {
    const fakeSdk = createFakeSdk()
    const onTransitionInterrupt = vi.fn()
    const onViewportChange = vi.fn()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)

    render(
      <NaverMap
        aggregateMarkers={[aggregateMarker]}
        markerRenderMode="server"
        onAggregateMarkerSelect={vi.fn()}
        onTransitionInterrupt={onTransitionInterrupt}
        onViewportChange={onViewportChange}
        representation="AGGREGATE"
        transitioning
      />,
    )

    await waitFor(() => expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce())
    const mapSurface = document.querySelector('.map-surface')
    if (!(mapSurface instanceof HTMLElement)) {
      throw new Error('지도 surface를 찾을 수 없습니다.')
    }

    fireEvent.wheel(mapSurface)
    fireEvent.wheel(mapSurface)

    expect(fakeSdk.stopMap).toHaveBeenCalledOnce()
    expect(onTransitionInterrupt).toHaveBeenCalledOnce()
    expect(onTransitionInterrupt.mock.invocationCallOrder[0]).toBeLessThan(
      fakeSdk.stopMap.mock.invocationCallOrder[0] ?? 0,
    )

    act(() => fakeSdk.emitIdle())

    expect(onViewportChange).toHaveBeenCalledOnce()
    act(() => fakeSdk.emitDragStart())

    expect(fakeSdk.stopMap).toHaveBeenCalledTimes(2)
    expect(onTransitionInterrupt).toHaveBeenCalledTimes(2)
  })

  it('전환 중 stop이 동기 idle을 보내도 취소를 먼저 알린다', async () => {
    const fakeSdk = createFakeSdk()
    const onTransitionInterrupt = vi.fn()
    const onViewportChange = vi.fn()
    fakeSdk.stopMap.mockImplementation(() => fakeSdk.emitIdle())
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)

    render(
      <NaverMap
        aggregateMarkers={[aggregateMarker]}
        markerRenderMode="server"
        onAggregateMarkerSelect={vi.fn()}
        onTransitionInterrupt={onTransitionInterrupt}
        onViewportChange={onViewportChange}
        representation="AGGREGATE"
        transitioning
      />,
    )

    await waitFor(() => expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce())
    const mapSurface = document.querySelector('.map-surface')
    if (!(mapSurface instanceof HTMLElement)) {
      throw new Error('지도 surface를 찾을 수 없습니다.')
    }

    fireEvent.wheel(mapSurface)

    expect(onTransitionInterrupt).toHaveBeenCalledOnce()
    expect(onViewportChange).toHaveBeenCalledOnce()
    expect(onTransitionInterrupt.mock.invocationCallOrder[0]).toBeLessThan(
      onViewportChange.mock.invocationCallOrder[0] ?? 0,
    )
  })

  it('서버 집계 전환 중 dragstart만 취소하고 다음 전환에서 다시 알린다', async () => {
    const fakeSdk = createFakeSdk()
    const onTransitionInterrupt = vi.fn()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)

    const { rerender } = render(
      <NaverMap
        aggregateMarkers={[aggregateMarker]}
        markerRenderMode="server"
        onAggregateMarkerSelect={vi.fn()}
        onTransitionInterrupt={onTransitionInterrupt}
        representation="AGGREGATE"
      />,
    )

    await waitFor(() => expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce())
    act(() => fakeSdk.emitDragStart())

    expect(fakeSdk.stopMap).not.toHaveBeenCalled()
    expect(onTransitionInterrupt).not.toHaveBeenCalled()

    rerender(
      <NaverMap
        aggregateMarkers={[aggregateMarker]}
        markerRenderMode="server"
        onAggregateMarkerSelect={vi.fn()}
        onTransitionInterrupt={onTransitionInterrupt}
        representation="AGGREGATE"
        transitioning
      />,
    )
    act(() => fakeSdk.emitDragStart())
    act(() => fakeSdk.emitDragStart())

    expect(fakeSdk.stopMap).toHaveBeenCalledOnce()
    expect(onTransitionInterrupt).toHaveBeenCalledOnce()

    rerender(
      <NaverMap
        aggregateMarkers={[aggregateMarker]}
        markerRenderMode="server"
        onAggregateMarkerSelect={vi.fn()}
        onTransitionInterrupt={onTransitionInterrupt}
        representation="AGGREGATE"
      />,
    )
    rerender(
      <NaverMap
        aggregateMarkers={[aggregateMarker]}
        markerRenderMode="server"
        onAggregateMarkerSelect={vi.fn()}
        onTransitionInterrupt={onTransitionInterrupt}
        representation="AGGREGATE"
        transitioning
      />,
    )
    act(() => fakeSdk.emitDragStart())

    expect(fakeSdk.stopMap).toHaveBeenCalledTimes(2)
    expect(onTransitionInterrupt).toHaveBeenCalledTimes(2)
  })

  it('갱신 또는 전환 중인 서버 집계 marker는 선택할 수 없다', async () => {
    const fakeSdk = createFakeSdk()
    const onAggregateMarkerSelect = vi.fn()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)

    const { rerender } = render(
      <NaverMap
        aggregateMarkers={[aggregateMarker]}
        dataBusy
        markerRenderMode="server"
        onAggregateMarkerSelect={onAggregateMarkerSelect}
        representation="AGGREGATE"
      />,
    )

    await waitFor(() =>
      expect(fakeSdk.markerConstructor).toHaveBeenCalledOnce(),
    )
    const markerButton = createdMarkerButton(fakeSdk, 0)
    expect(markerButton).toBeDisabled()
    fireEvent.click(markerButton)
    expect(onAggregateMarkerSelect).not.toHaveBeenCalled()

    rerender(
      <NaverMap
        aggregateMarkers={[aggregateMarker]}
        markerRenderMode="server"
        onAggregateMarkerSelect={onAggregateMarkerSelect}
        representation="AGGREGATE"
        transitioning
      />,
    )
    expect(markerButton).toBeDisabled()
    fireEvent.click(markerButton)
    expect(onAggregateMarkerSelect).not.toHaveBeenCalled()

    rerender(
      <NaverMap
        aggregateMarkers={[aggregateMarker]}
        markerRenderMode="server"
        onAggregateMarkerSelect={onAggregateMarkerSelect}
        representation="AGGREGATE"
      />,
    )
    expect(markerButton).toBeEnabled()
    fireEvent.click(markerButton)
    expect(onAggregateMarkerSelect).toHaveBeenCalledWith(aggregateMarker)
  })

  it('서버 개별 marker는 같은 좌표라도 각각 하나의 overlay로 표시한다', async () => {
    const fakeSdk = createFakeSdk()
    const onMarkerSelect = vi.fn()
    const markers = [
      {
        ...markerPresentation,
        id: 'same-a',
        latitude: 37.5,
        longitude: 127,
        name: '같은 좌표 첫 단지',
        selected: true,
      },
      {
        ...markerPresentation,
        id: 'same-b',
        latitude: 37.5,
        longitude: 127,
        name: '같은 좌표 둘째 단지',
        highlighted: true,
      },
    ] satisfies NaverMapMarker[]
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)

    render(
      <NaverMap
        markerRenderMode="server"
        markers={markers}
        onMarkerSelect={onMarkerSelect}
        representation="INDIVIDUAL"
      />,
    )

    await waitFor(() =>
      expect(fakeSdk.markerConstructor).toHaveBeenCalledTimes(2),
    )
    const buttons = [
      createdMarkerButton(fakeSdk, 0),
      createdMarkerButton(fakeSdk, 1),
    ]

    expect(buttons.map((button) => button.dataset.complexId)).toEqual([
      'same-a',
      'same-b',
    ])
    expect(buttons.every((button) =>
      !button.classList.contains('housing-map-cluster'),
    )).toBe(true)
    expect(fakeSdk.fromCoordToOffset).not.toHaveBeenCalled()
    fireEvent.click(buttons[1])

    expect(onMarkerSelect).toHaveBeenCalledWith('same-b')
    expect(fakeSdk.fitBoundsMap).not.toHaveBeenCalled()
  })

  it('선택 marker는 cluster에서 제외하고 강조 단지가 든 cluster를 강조한다', async () => {
    const fakeSdk = createFakeSdk()
    const markers = [
      {
        ...markerPresentation,
        id: 'selected',
        latitude: 37.5,
        longitude: 127,
        name: '선택 단지',
        selected: true,
      },
      {
        ...markerPresentation,
        id: 'cluster-a',
        latitude: 37.5,
        longitude: 127,
        name: '묶음 첫 단지',
      },
      {
        ...markerPresentation,
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

  it('cluster 선택 시 bounds를 맞추고 분리된 marker로 focus를 복원한다', async () => {
    const fakeSdk = createFakeSdk()
    const markers = [
      {
        ...markerPresentation,
        id: '101',
        latitude: 37.5,
        longitude: 127,
        name: '첫 단지',
      },
      {
        ...markerPresentation,
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
    fakeSdk.setCurrentZoom(20)
    fireEvent.click(clusterButton)

    expect(fakeSdk.fitBoundsMap.mock.calls.at(-1)?.[1]).toMatchObject({
      maxZoom: 21,
    })
    expect(fakeSdk.getMaxZoomMap).toHaveBeenCalledTimes(2)

    const focus = vi.spyOn(HTMLButtonElement.prototype, 'focus')
    act(() => {
      fakeSdk.setCurrentZoom(15)
      fakeSdk.emitIdle()
    })

    await waitFor(() => expect(focus).toHaveBeenCalledOnce())
    expect(await screen.findByRole('status')).toHaveTextContent(
      '2곳 단지 묶음을 확대해 개별 단지를 표시했습니다.',
    )
    expect(fakeSdk.markerConstructor).toHaveBeenCalledTimes(3)
    expect(document.activeElement).toHaveAttribute('data-complex-id', '101')
    focus.mockRestore()
  })

  it('확대 뒤에도 묶인 cluster에 focus를 복원하고 한 번만 안내한다', async () => {
    const fakeSdk = createFakeSdk()
    const markers = [
      {
        ...markerPresentation,
        id: '101',
        latitude: 37.5,
        longitude: 127,
        name: '첫 단지',
      },
      {
        ...markerPresentation,
        id: '102',
        latitude: 37.5,
        longitude: 127,
        name: '둘째 단지',
      },
    ] satisfies NaverMapMarker[]
    const focus = vi.spyOn(HTMLButtonElement.prototype, 'focus')
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)

    render(<NaverMap markers={markers} />)

    await waitFor(() =>
      expect(fakeSdk.markerConstructor).toHaveBeenCalledOnce(),
    )
    fireEvent.click(createdMarkerButton(fakeSdk, 0))
    act(() => {
      fakeSdk.setCurrentZoom(15)
      fakeSdk.emitIdle()
    })

    await waitFor(() => expect(focus).toHaveBeenCalledOnce())
    expect(screen.getAllByRole('status')).toHaveLength(1)
    expect(screen.getByRole('status')).toHaveTextContent(
      '2곳 단지 묶음을 확대했지만 아직 함께 표시됩니다.',
    )
    expect(document.activeElement).toBe(createdMarkerButton(fakeSdk, 0))

    act(() => fakeSdk.emitIdle())
    expect(fakeSdk.markerConstructor).toHaveBeenCalledOnce()
    expect(focus).toHaveBeenCalledOnce()
    expect(document.activeElement).toBe(createdMarkerButton(fakeSdk, 0))
    focus.mockRestore()
  })

  it('idle에서 zoom이 바뀌면 화면 거리를 다시 계산해 분리하고 재결합한다', async () => {
    const fakeSdk = createFakeSdk()
    const markers = [
      {
        ...markerPresentation,
        id: '101',
        latitude: 37.5,
        longitude: 127,
        name: '첫 단지',
      },
      {
        ...markerPresentation,
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
    const onTransitionInterrupt = vi.fn()
    fakeSdk.destroyMap.mockImplementationOnce(() => {
      throw new Error('NAVER SDK가 이미 지도를 해제했습니다.')
    })
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)

    render(
      <NaverMap
        onTransitionInterrupt={onTransitionInterrupt}
        onViewportChange={vi.fn()}
        transitioning
      />,
    )
    await waitFor(() => expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce())
    const mapSurface = document.querySelector('.map-surface')
    if (!(mapSurface instanceof HTMLElement)) {
      throw new Error('지도 surface를 찾을 수 없습니다.')
    }
    const removeWheelListener = vi.spyOn(mapSurface, 'removeEventListener')

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
    expect(fakeSdk.removeListener).toHaveBeenCalledTimes(3)
    expect(fakeSdk.removeListener).toHaveBeenCalledWith(
      expect.objectContaining({ eventName: 'init' }),
    )
    expect(removeWheelListener).toHaveBeenCalledWith(
      'wheel',
      expect.any(Function),
    )
    fireEvent.wheel(mapSurface)
    expect(onTransitionInterrupt).not.toHaveBeenCalled()
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
    expect(fakeSdk.removeListener).toHaveBeenCalledTimes(3)
    expect(fakeSdk.removeListener).toHaveBeenCalledWith(
      expect.objectContaining({ eventName: 'init' }),
    )
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
