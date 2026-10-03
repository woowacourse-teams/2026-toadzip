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
  emitBoundsChanged: () => void
  emitDragStart: () => void
  emitIdle: () => void
  emitInit: () => void
  fitBoundsMap: ReturnType<typeof vi.fn>
  fromCoordToOffset: ReturnType<typeof vi.fn>
  fromOffsetToCoord: ReturnType<typeof vi.fn>
  getBoundsMap: ReturnType<typeof vi.fn>
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
  const getBoundsMap = vi.fn(() => ({
    getNE: () => ({ lat: () => 37.7, lng: () => 127.1 }),
    getSW: () => ({ lat: () => 37.5, lng: () => 126.8 }),
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
  let boundsListener: (() => void) | null = null
  let dragStartListener: (() => void) | null = null
  let idleListener: (() => void) | null = null
  let initListener: (() => void) | null = null
  const removeListener = vi.fn((listener: naver.maps.MapEventListener) => {
    const eventName = (listener as unknown as { eventName?: string }).eventName
    if (eventName === 'bounds_changed') boundsListener = null
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
      if (eventName === 'bounds_changed') boundsListener = listener
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
    getBounds: getBoundsMap,
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
    emitBoundsChanged: () => boundsListener?.(),
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
    getBoundsMap,
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
  deposit: { digits: '1', unit: '천', exactLabel: '10,000,000원' },
  monthlyRent: { digits: '23', unit: '만', exactLabel: '234,000원' },
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

    render(<NaverMap representation="INDIVIDUAL" markers={[]} />)

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

    render(<NaverMap representation="INDIVIDUAL" markers={[]} />)

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

    render(<NaverMap representation="INDIVIDUAL" markers={[]} dataBusy />)

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

    const { unmount } = render(<NaverMap representation="INDIVIDUAL" markers={[]} />)

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

    const surface = document.querySelector('.map-surface')!
    Object.defineProperty(surface, 'clientWidth', { configurable: true, value: 900 })
    act(() => resizeCallback([], {} as ResizeObserver))

    expect(fakeSdk.autoResizeMap).toHaveBeenCalledOnce()

    unmount()

    expect(disconnect).toHaveBeenCalledOnce()
    expect(fakeSdk.destroyMap).toHaveBeenCalledOnce()
  })

  it('init 전에 unmount하면 초기화 리스너를 제거하고 bounds를 적용하지 않는다', async () => {
    const fakeSdk = createFakeSdk()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    const { unmount } = render(
      <NaverMap representation="INDIVIDUAL" markers={[]} cameraRequestId={1} cameraTarget={regionCameraTarget} />,
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
      <NaverMap representation="INDIVIDUAL" markers={[]}
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
    }), { cause: 'initial' })

    rerender(
      <NaverMap representation="INDIVIDUAL" markers={[]}
        cameraTarget={{ latitude: 37.51, longitude: 127.02, zoom: 15 }}
        onViewportChange={onViewportChange}
      />,
    )

    expect(fakeSdk.panToMap).not.toHaveBeenCalled()
    expect(fakeSdk.setZoomMap).not.toHaveBeenCalled()

    rerender(
      <NaverMap representation="INDIVIDUAL" markers={[]}
        cameraTarget={{ latitude: 37.52, longitude: 127.02, zoom: 15 }}
        onViewportChange={onViewportChange}
      />,
    )

    expect(fakeSdk.panToMap).toHaveBeenCalledOnce()
    expect(fakeSdk.setZoomMap).not.toHaveBeenCalled()

    rerender(
      <NaverMap representation="INDIVIDUAL" markers={[]}
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
      <NaverMap representation="INDIVIDUAL" markers={[]}
        cameraTarget={{ latitude: 37.51, longitude: 127.02, zoom: 14 }}
      />,
    )

    await waitFor(() => expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce())

    rerender(
      <NaverMap representation="INDIVIDUAL" markers={[]}
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

    render(<NaverMap representation="INDIVIDUAL" markers={[]} cameraRequestId={1} cameraTarget={regionCameraTarget} />)

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

    render(<NaverMap representation="INDIVIDUAL" markers={[]} cameraRequestId={1}
      cameraTarget={{ ...regionCameraTarget, boundsPadding: undefined }} />)

    await waitFor(() => expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce())
    expect(fakeSdk.fitBoundsMap).not.toHaveBeenCalled()
    act(() => fakeSdk.emitInit())

    expect(fakeSdk.fitBoundsMap).toHaveBeenCalledOnce()
    expect(fakeSdk.fitBoundsMap).toHaveBeenCalledWith([
      { latitude: 37.5, longitude: 126.8 },
      { latitude: 37.7, longitude: 127.2 },
    ], {})
  })

  it('새 지역 요청은 bounds를 우선 적용하며 같은 요청 ID와 늦은 경계 응답은 재이동하지 않는다', async () => {
    const fakeSdk = createFakeSdk()
    const onViewportChange = vi.fn()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    const { rerender } = render(<NaverMap representation="INDIVIDUAL" markers={[]} cameraRequestId={1} onViewportChange={onViewportChange} />)
    await waitFor(() => expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce())
    act(() => fakeSdk.emitInit())

    rerender(<NaverMap representation="INDIVIDUAL" markers={[]} cameraRequestId={2} cameraTarget={regionCameraTarget} onViewportChange={onViewportChange} />)
    expect(fakeSdk.fitBoundsMap).toHaveBeenCalledOnce()

    act(() => {
      fakeSdk.emitDragStart()
      fakeSdk.setCurrentCenter(35, 129)
      fakeSdk.setCurrentZoom(9)
      fakeSdk.emitIdle()
    })
    rerender(<NaverMap representation="INDIVIDUAL" markers={[]} cameraRequestId={2}
      cameraTarget={{ ...regionCameraTarget, boundsPadding: { top: 10, right: 10, bottom: 10, left: 10 } }}
      regionBoundary={regionBoundary} />)
    expect(fakeSdk.fitBoundsMap).toHaveBeenCalledOnce()
    expect(onViewportChange).toHaveBeenLastCalledWith(expect.objectContaining({
      center: { latitude: 35, longitude: 129 }, zoom: 9,
    }), { cause: 'user' })
    expect(fakeSdk.panToMap).not.toHaveBeenCalled()
    expect(fakeSdk.morphMap).not.toHaveBeenCalled()
    expect(fakeSdk.setZoomMap).not.toHaveBeenCalled()

    rerender(<NaverMap representation="INDIVIDUAL" markers={[]} cameraRequestId={3} cameraTarget={regionCameraTarget}
      regionBoundary={regionBoundary} />)
    expect(fakeSdk.fitBoundsMap).toHaveBeenCalledTimes(2)
  })

  it('요청 ID 없이 동일한 bounds 객체가 재생성되어도 다시 맞추지 않는다', async () => {
    const fakeSdk = createFakeSdk()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    const { rerender } = render(<NaverMap representation="INDIVIDUAL" markers={[]} cameraTarget={regionCameraTarget} />)
    await waitFor(() => expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce())
    act(() => fakeSdk.emitInit())
    expect(fakeSdk.fitBoundsMap).toHaveBeenCalledOnce()

    act(() => fakeSdk.emitIdle())
    rerender(<NaverMap representation="INDIVIDUAL" markers={[]} cameraTarget={{ ...regionCameraTarget,
      bounds: { ...regionCameraTarget.bounds }, boundsPadding: { ...regionCameraTarget.boundsPadding } }} />)

    expect(fakeSdk.fitBoundsMap).toHaveBeenCalledOnce()
  })

  it('init 전에 바뀐 마지막 bounds와 요청 ID만 초기 카메라에 적용한다', async () => {
    const fakeSdk = createFakeSdk()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    const { rerender } = render(
      <NaverMap representation="INDIVIDUAL" markers={[]} cameraRequestId={1} cameraTarget={regionCameraTarget} />,
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
    rerender(<NaverMap representation="INDIVIDUAL" markers={[]} cameraRequestId={2} cameraTarget={latestTarget} />)

    expect(fakeSdk.fitBoundsMap).not.toHaveBeenCalled()
    act(() => fakeSdk.emitInit())

    expect(fakeSdk.fitBoundsMap).toHaveBeenCalledExactlyOnceWith([
      { latitude: 37.55, longitude: 126.9 },
      { latitude: 37.65, longitude: 127.1 },
    ], { top: 80, right: 20, bottom: 30, left: 320 })

    rerender(<NaverMap representation="INDIVIDUAL" markers={[]} cameraRequestId={2} cameraTarget={regionCameraTarget} />)
    expect(fakeSdk.fitBoundsMap).toHaveBeenCalledOnce()
  })

  it('경계의 섬과 내부 구멍을 각각 보존하고 마커 아래에 클릭을 받지 않는 도형을 표시한다', async () => {
    const fakeSdk = createFakeSdk()
    const onMarkerSelect = vi.fn()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    render(<NaverMap representation="INDIVIDUAL" regionBoundary={regionBoundary} onMarkerSelect={onMarkerSelect}
      markers={[{ ...markerPresentation, id: '101', name: '경계 안 단지', latitude: 37.5666103, longitude: 126.9783882 }]} />)

    await waitFor(() => expect(fakeSdk.overlayConstructor).toHaveBeenCalledTimes(1))
    const path = document.querySelector('svg path')
    expect(path?.getAttribute('d')?.match(/M/g)).toHaveLength(3)
    expect(path?.getAttribute('d')).not.toContain('NaN')
    expect(path?.getAttribute('fill-rule')).toBe('evenodd')
    expect(path?.getAttribute('fill')).toBe('#226b3b')
    expect(document.querySelector('svg')?.style.pointerEvents).toBe('none')
    fireEvent.click(createdMarkerButton(fakeSdk, 0))
    expect(onMarkerSelect).toHaveBeenCalledWith('101')
    expect(fakeSdk.fitBoundsMap).not.toHaveBeenCalled()
  })

  it('지도 이동과 marker 갱신에서는 경계를 유지하고 다른 지역·해제·unmount에서 모두 제거한다', async () => {
    const fakeSdk = createFakeSdk()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    const { rerender, unmount } = render(<NaverMap representation="INDIVIDUAL" markers={[]} regionBoundary={regionBoundary} />)
    await waitFor(() => expect(fakeSdk.overlayConstructor).toHaveBeenCalledTimes(1))

    act(() => fakeSdk.emitIdle())
    rerender(<NaverMap representation="INDIVIDUAL" regionBoundary={regionBoundary}
      markers={[{ ...markerPresentation, id: '101', name: '갱신 단지', latitude: 37.5666103, longitude: 126.9783882 }]} />)
    expect(fakeSdk.overlayConstructor).toHaveBeenCalledTimes(1)
    expect(fakeSdk.overlayInstances[0]?.setMap).toHaveBeenCalledTimes(1)

    rerender(<NaverMap representation="INDIVIDUAL" markers={[]} regionBoundary={{ ...regionBoundary, regionCode: '11140' }} />)
    expect(fakeSdk.overlayConstructor).toHaveBeenCalledTimes(2)
    expect(fakeSdk.overlayInstances[0]?.setMap).toHaveBeenLastCalledWith(null)

    rerender(<NaverMap representation="INDIVIDUAL" markers={[]} regionBoundary={null} />)
    expect(fakeSdk.overlayInstances[1]?.setMap).toHaveBeenCalledWith(null)

    rerender(<NaverMap representation="INDIVIDUAL" markers={[]} regionBoundary={regionBoundary} />)
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
    const { unmount } = render(<NaverMap representation="INDIVIDUAL" markers={[]} regionBoundary={regionBoundary} />)
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
        <NaverMap representation="INDIVIDUAL" markers={[]}
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
      <NaverMap representation="INDIVIDUAL" markers={[]}
        cameraRequestId={1}
        cameraTarget={cameraTarget}
        onViewportChange={onViewportChange}
      />,
    )
    await waitFor(() => expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce())

    rerender(
      <NaverMap representation="INDIVIDUAL" markers={[]}
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
    }), { cause: 'programmatic' })
  })

  it('같은 요청 ID에서 target이 바뀌거나 사용자가 지도를 움직여도 카메라를 되돌리지 않는다', async () => {
    const fakeSdk = createFakeSdk()
    const onViewportChange = vi.fn()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    const { rerender } = render(
      <NaverMap representation="INDIVIDUAL" markers={[]}
        cameraRequestId={1}
        cameraTarget={{ latitude: 37.51, longitude: 127.02, zoom: 14 }}
        onViewportChange={onViewportChange}
      />,
    )
    await waitFor(() => expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce())

    act(() => {
      fakeSdk.emitDragStart()
      fakeSdk.setCurrentCenter(37.6, 127.1)
      fakeSdk.setCurrentZoom(15)
      fakeSdk.emitIdle()
    })
    rerender(
      <NaverMap representation="INDIVIDUAL" markers={[]}
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
    }), { cause: 'user' })
  })

  it('줌 유지 이동은 화면 offset만큼 보정한 중심으로 한 번 이동한다', async () => {
    const fakeSdk = createFakeSdk()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    const { rerender } = render(<NaverMap representation="INDIVIDUAL" markers={[]} cameraRequestId={1} />)
    await waitFor(() => expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce())

    rerender(
      <NaverMap representation="INDIVIDUAL" markers={[]}
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
    const { rerender } = render(<NaverMap representation="INDIVIDUAL" markers={[]} cameraRequestId={1} />)
    await waitFor(() => expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce())

    rerender(
      <NaverMap representation="INDIVIDUAL" markers={[]}
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
      <NaverMap representation="INDIVIDUAL" markers={[]} cameraRequestId={1} cameraTarget={cameraTarget} onViewportChange={onViewportChange} />,
    )
    await waitFor(() => expect(fakeSdk.morphMap).toHaveBeenCalledOnce())
    expect(fakeSdk.morphMap.mock.calls[0]?.[0]).toMatchObject({
      latitude: expect.closeTo(37.5112), longitude: expect.closeTo(127.016),
    })
    act(() => {
      fakeSdk.emitDragStart()
      fakeSdk.setCurrentCenter(37.6, 127.1)
      fakeSdk.emitIdle()
    })
    rerender(
      <NaverMap representation="INDIVIDUAL" markers={[]} cameraRequestId={1} cameraTarget={{ ...cameraTarget }} onViewportChange={onViewportChange} />,
    )
    expect(fakeSdk.morphMap).toHaveBeenCalledOnce()

    rerender(
      <NaverMap representation="INDIVIDUAL" markers={[]} cameraRequestId={2} cameraTarget={cameraTarget} onViewportChange={onViewportChange} />,
    )
    expect(fakeSdk.morphMap).toHaveBeenCalledTimes(2)
    act(() => fakeSdk.emitIdle())
    expect(onViewportChange).toHaveBeenLastCalledWith(expect.objectContaining({
      center: { latitude: expect.closeTo(37.5112), longitude: expect.closeTo(127.016) }, zoom: 14,
    }), { cause: 'programmatic' })
  })

  it('이미 offset이 반영된 위치를 다시 요청하면 현재 viewport를 즉시 알린다', async () => {
    const fakeSdk = createFakeSdk()
    const onViewportChange = vi.fn()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    const cameraTarget = {
      latitude: 37.51, longitude: 127.02, screenOffset: { x: 200, y: -60 }, zoom: 14,
    }
    const { rerender } = render(
      <NaverMap representation="INDIVIDUAL" markers={[]} cameraRequestId={1} cameraTarget={cameraTarget} onViewportChange={onViewportChange} />,
    )
    await waitFor(() => expect(fakeSdk.morphMap).toHaveBeenCalledOnce())

    rerender(
      <NaverMap representation="INDIVIDUAL" markers={[]} cameraRequestId={2} cameraTarget={cameraTarget} onViewportChange={onViewportChange} />,
    )

    expect(onViewportChange).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({
      center: { latitude: expect.closeTo(37.5112), longitude: expect.closeTo(127.016) }, zoom: 14,
    }), { cause: 'programmatic' })
  })

  it('URL 직렬화 정밀도 안의 camera 차이는 무시하고 더 큰 차이만 적용한다', async () => {
    const fakeSdk = createFakeSdk()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)

    const { rerender } = render(
      <NaverMap representation="INDIVIDUAL" markers={[]}
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
      <NaverMap representation="INDIVIDUAL" markers={[]}
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
      <NaverMap representation="INDIVIDUAL" markers={[]}
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
      <NaverMap representation="INDIVIDUAL" markers={[]}
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
      <NaverMap representation="INDIVIDUAL" markers={[]}
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

  it.each([
    { latitude: Number.NaN, longitude: 127, zoom: 15 },
    { latitude: 91, longitude: 127, zoom: 15 },
    { latitude: -91, longitude: 127, zoom: 15 },
    { latitude: 37, longitude: 181, zoom: 15 },
    { latitude: 37, longitude: -181, zoom: 15 },
    { latitude: 37, longitude: 127, zoom: Number.POSITIVE_INFINITY },
  ])('잘못된 camera target %j은 기본 위치로 시작하고 지도에 전달하지 않는다', async (cameraTarget) => {
    const fakeSdk = createFakeSdk()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)

    render(
      <NaverMap representation="INDIVIDUAL" markers={[]}
        cameraTarget={cameraTarget}
      />,
    )

    await waitFor(() => expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce())
    expect(fakeSdk.mapConstructor).toHaveBeenCalledWith(expect.anything(), expect.objectContaining({
      center: { latitude: 37.5666103, longitude: 126.9783882 }, zoom: 14,
    }))
    expect(fakeSdk.panToMap).not.toHaveBeenCalled()
    expect(fakeSdk.setZoomMap).not.toHaveBeenCalled()
    expect(fakeSdk.morphMap).not.toHaveBeenCalled()
  })

  it.each([
    { southWestLat: 37.8 },
    { northEastLat: 37.4 },
    { southWestLng: 127.3 },
    { northEastLng: 126.7 },
    { southWestLat: Number.NaN },
    { northEastLng: 181 },
  ])('뒤집히거나 잘못된 지역 경계 %j는 초기화 후에도 지도에 전달하지 않는다', async (invalidBounds) => {
    const fakeSdk = createFakeSdk()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    render(<NaverMap representation="INDIVIDUAL" markers={[]}
      cameraRequestId={1}
      cameraTarget={{
        ...regionCameraTarget,
        bounds: { ...regionCameraTarget.bounds, ...invalidBounds },
      }} />)
    await waitFor(() => expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce())

    act(() => fakeSdk.emitInit())

    expect(fakeSdk.fitBoundsMap).not.toHaveBeenCalled()
    expect(fakeSdk.panToMap).not.toHaveBeenCalled()
    expect(fakeSdk.morphMap).not.toHaveBeenCalled()
  })

  it.each<unknown>([
    null,
    { lat: 37.6, lng: () => 127 },
    { lat: () => '37.6', lng: () => 127 },
    { lat: () => 37.6, lng: () => '127' },
  ])('SDK가 좌표 계약을 어긴 중심 %j를 반환하면 viewport를 알리지 않는다', async (center) => {
    const fakeSdk = createFakeSdk()
    const onViewportChange = vi.fn()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    render(<NaverMap representation="INDIVIDUAL" markers={[]}
      onViewportChange={onViewportChange} />)
    await waitFor(() => expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce())
    fakeSdk.getCenterMap.mockReturnValue(center)

    act(() => fakeSdk.emitIdle())

    expect(onViewportChange).not.toHaveBeenCalled()
  })

  it.each([
    ['center', Number.NaN, 127],
    ['center', 37.6, Number.POSITIVE_INFINITY],
    ['center', 91, 127],
    ['center', 37.6, -181],
    ['southWest', Number.NEGATIVE_INFINITY, 126.8],
    ['southWest', -91, 126.8],
    ['northEast', 37.7, Number.NaN],
    ['northEast', 37.7, 181],
  ] as const)('SDK %s의 잘못된 좌표 %s/%s는 viewport로 전달하지 않고 회복 후 다시 알린다', async (source, latitude, longitude) => {
    const fakeSdk = createFakeSdk()
    const onViewportChange = vi.fn()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    render(<NaverMap representation="INDIVIDUAL" markers={[]}
      onViewportChange={onViewportChange} />)
    await waitFor(() => expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce())
    const invalidCoordinate = { lat: () => latitude, lng: () => longitude }
    if (source === 'center') {
      fakeSdk.getCenterMap.mockReturnValueOnce(invalidCoordinate)
    } else {
      fakeSdk.getBoundsMap.mockReturnValueOnce({
        getSW: () => source === 'southWest'
          ? invalidCoordinate : { lat: () => 37.5, lng: () => 126.8 },
        getNE: () => source === 'northEast'
          ? invalidCoordinate : { lat: () => 37.7, lng: () => 127.1 },
      })
    }

    act(() => fakeSdk.emitIdle())
    expect(onViewportChange).not.toHaveBeenCalled()

    act(() => fakeSdk.emitIdle())
    expect(onViewportChange).toHaveBeenCalledExactlyOnceWith({
      bounds: { southWestLat: 37.5, southWestLng: 126.8, northEastLat: 37.7, northEastLng: 127.1 },
      center: { latitude: 37.5666103, longitude: 126.9783882 },
      zoom: 14,
    }, { cause: 'initial' })
  })

  it.each([Number.NaN, Number.POSITIVE_INFINITY])('SDK 확대 수준이 %s이면 viewport를 알리지 않는다', async (zoom) => {
    const fakeSdk = createFakeSdk()
    const onViewportChange = vi.fn()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    render(<NaverMap representation="INDIVIDUAL" markers={[]}
      onViewportChange={onViewportChange} />)
    await waitFor(() => expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce())
    fakeSdk.setCurrentZoom(zoom)

    act(() => fakeSdk.emitIdle())

    expect(onViewportChange).not.toHaveBeenCalled()
  })

  it.each([
    { x: Number.NaN, y: 0 },
    { x: 0, y: Number.POSITIVE_INFINITY },
  ])('화면 offset %j가 유효하지 않으면 요청한 중심으로 이동한다', async (screenOffset) => {
    const fakeSdk = createFakeSdk()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    const { rerender } = render(<NaverMap representation="INDIVIDUAL" markers={[]} />)
    await waitFor(() => expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce())

    rerender(<NaverMap representation="INDIVIDUAL" markers={[]}
      cameraTarget={{ latitude: 37.6, longitude: 127, screenOffset }} />)

    expect(fakeSdk.panToMap).toHaveBeenCalledExactlyOnceWith({ latitude: 37.6, longitude: 127 })
    expect(fakeSdk.fromCoordToOffset).not.toHaveBeenCalled()
  })

  it('화면 offset 보정 결과가 잘못된 SDK 좌표이면 원래 요청한 중심으로 이동한다', async () => {
    const fakeSdk = createFakeSdk()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    const { rerender } = render(<NaverMap representation="INDIVIDUAL" markers={[]} />)
    await waitFor(() => expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce())
    fakeSdk.fromOffsetToCoord.mockReturnValue({ lat: () => 91, lng: () => 127 })

    rerender(<NaverMap representation="INDIVIDUAL" markers={[]}
      cameraTarget={{ latitude: 37.6, longitude: 127, screenOffset: { x: 200, y: 0 } }} />)

    expect(fakeSdk.panToMap).toHaveBeenCalledExactlyOnceWith({ latitude: 37.6, longitude: 127 })
  })

  it('camera 이동과 함께 marker와 idle callback 수명주기를 유지한다', async () => {
    const fakeSdk = createFakeSdk()
    const onMarkerSelect = vi.fn()
    const onTransitionInterrupt = vi.fn()
    const onViewportChange = vi.fn()
    const markers = [
      {
        ...markerPresentation,
        id: '101',
        latitude: 37.5666103,
        longitude: 126.9783882,
        name: '테스트 단지',
      },
    ] satisfies NaverMapMarker[]
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)

    const { rerender, unmount } = render(
      <NaverMap representation="INDIVIDUAL"
        markers={markers}
        onMarkerSelect={onMarkerSelect}
        onTransitionInterrupt={onTransitionInterrupt}
        transitioning
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
    }, { cause: 'initial' })
    expect(fakeSdk.markerConstructor).toHaveBeenCalledOnce()

    const markerButton = createdMarkerButton(fakeSdk, 0)
    expect(markerButton).toHaveAttribute('data-complex-id', '101')
    fireEvent.click(markerButton)

    expect(onMarkerSelect).toHaveBeenCalledWith('101')

    rerender(
      <NaverMap representation="INDIVIDUAL"
        cameraTarget={{ latitude: 37.567, longitude: 126.98 }}
        markers={markers}
        onMarkerSelect={onMarkerSelect}
        onTransitionInterrupt={onTransitionInterrupt}
        transitioning
        onViewportChange={onViewportChange}
      />,
    )

    expect(fakeSdk.panToMap).toHaveBeenCalledOnce()
    expect(fakeSdk.markerConstructor).toHaveBeenCalledOnce()

    const mapSurface = document.querySelector('.map-surface')
    if (!(mapSurface instanceof HTMLElement)) {
      throw new Error('지도 surface를 찾을 수 없습니다.')
    }
    unmount()

    fireEvent.wheel(mapSurface)
    expect(onTransitionInterrupt).not.toHaveBeenCalled()
    expect(fakeSdk.markerSetMap).toHaveBeenCalledOnce()
    expect(fakeSdk.markerSetMap).toHaveBeenCalledWith(null)
    expect(fakeSdk.removeListener).toHaveBeenCalledTimes(4)
    expect(fakeSdk.removeListener).toHaveBeenCalledWith(
      expect.objectContaining({ eventName: 'init' }),
    )
  })

  it('개별 marker hover와 focus를 카드에 전달하고 강조만 바뀌면 DOM을 유지한다', async () => {
    const fakeSdk = createFakeSdk()
    const onMarkerHighlight = vi.fn()
    const nextMarkerHighlight = vi.fn()
    const marker = {
      ...markerPresentation,
      id: '101',
      latitude: 37.5666103,
      longitude: 126.9783882,
      name: '테스트 단지',
    } satisfies NaverMapMarker
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)

    const { rerender } = render(
      <NaverMap representation="INDIVIDUAL" markers={[marker]} onMarkerHighlight={onMarkerHighlight} />,
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
      <NaverMap representation="INDIVIDUAL"
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
      <NaverMap representation="INDIVIDUAL"
        markers={[{ ...marker, latitude: 37.567 }]}
        onMarkerHighlight={nextMarkerHighlight}
      />,
    )

    expect(nextMarkerHighlight).toHaveBeenLastCalledWith(null)
    expect(fakeSdk.markerConstructor).toHaveBeenCalledTimes(2)
  })

  it.each(['individual', 'aggregate'] as const)(
    '%s marker의 Enter와 Space 입력 양쪽 단계를 지도에 전달하지 않고 기본 동작은 보존한다',
    async (kind) => {
      const fakeSdk = createFakeSdk()
      const markers = [{
        ...markerPresentation, id: '101', latitude: 37.5666103, longitude: 126.9783882, name: '키보드 단지',
      }]
      loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
      render(kind === 'aggregate'
        ? <NaverMap representation="AGGREGATE"
            aggregateMarkers={[aggregateMarker]} onAggregateMarkerSelect={vi.fn()} />
        : <NaverMap representation="INDIVIDUAL" markers={markers} />)
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

  it.each(['individual', 'aggregate'] as const)(
    '%s marker는 키보드와 포인터 클릭을 SDK 좌표 처리 전에 한 번만 선택한다',
    async (kind) => {
      const fakeSdk = createFakeSdk()
      const onMarkerSelect = vi.fn()
      const markers = [{
        ...markerPresentation, id: '101', latitude: 37.5666103, longitude: 126.9783882, name: '클릭 단지',
      }]
      loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
      render(kind === 'aggregate'
        ? <NaverMap representation="AGGREGATE"
            aggregateMarkers={[aggregateMarker]} onAggregateMarkerSelect={onMarkerSelect} />
        : <NaverMap representation="INDIVIDUAL" markers={markers} onMarkerSelect={onMarkerSelect} />)
      await waitFor(() => expect(fakeSdk.markerConstructor).toHaveBeenCalledOnce())
      const markerButton = createdMarkerButton(fakeSdk, 0)
      const sdkPointerClick = vi.fn()
      markerButton.addEventListener('click', sdkPointerClick)
      markerButton.parentElement?.parentElement?.addEventListener('click', sdkPointerClick)

      for (const detail of [0, 1]) {
        onMarkerSelect.mockClear()
        fireEvent.click(markerButton, { detail })
        expect(onMarkerSelect).toHaveBeenCalledOnce()
        if (kind === 'individual') {
          expect(onMarkerSelect).toHaveBeenCalledWith('101')
        }
      }
      expect(sdkPointerClick).not.toHaveBeenCalled()
    },
  )

  it(
    '선택만 변경하면 마커와 포커스를 유지하고 필요한 표시만 갱신한다',
    async () => {
      const fakeSdk = createFakeSdk()
      const onMarkerSelect = vi.fn()
      const nextMarkerSelect = vi.fn()
      const markers = ['a', 'b', 'c'].map((id, index) => ({
        ...markerPresentation,
        id,
        latitude: 37.5666103,
        longitude: 126.96939 + index * 0.0026,
        name: `${id} 단지`,
        highlighted: id === 'b',
      }))
      loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
      function renderMap(selectedId: string | null, onSelect = onMarkerSelect) {
        const nextMarkers = markers.map((marker) => ({
          ...marker,
          selected: marker.id === selectedId,
        }))
        return <NaverMap
          representation="INDIVIDUAL"
          markers={nextMarkers}
          onMarkerSelect={onSelect}
        />
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

  it.each(['individual', 'aggregate'] as const)(
    '%s marker 데이터 갱신으로 overlay를 교체해도 같은 마커에 키보드 포커스를 복원한다',
    async (kind) => {
      const fakeSdk = createFakeSdk()
      const onAggregateMarkerSelect = vi.fn()
      const individual = {
        ...markerPresentation, id: '101', latitude: 37.5666103, longitude: 126.9783882, name: '포커스 단지',
      }
      loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
      const renderMap = (updated: boolean) => kind === 'aggregate'
        ? <NaverMap representation="AGGREGATE"
            aggregateMarkers={[{ ...aggregateMarker, uniqueComplexCount: updated ? 17 : 42 }]}
            onAggregateMarkerSelect={onAggregateMarkerSelect} />
        : <NaverMap representation="INDIVIDUAL"
            markers={[{ ...individual, name: updated ? '갱신한 포커스 단지' : individual.name }]} />
      const { rerender } = render(renderMap(false))
      await waitFor(() => expect(fakeSdk.markerConstructor).toHaveBeenCalledOnce())
      const previousButton = createdMarkerButton(fakeSdk, 0)
      previousButton.focus()

      rerender(renderMap(true))

      expect(previousButton).not.toBeInTheDocument()
      expect(fakeSdk.markerConstructor).toHaveBeenCalledTimes(2)
      await waitFor(() => expect(createdMarkerButton(fakeSdk, 1)).toHaveFocus())
    },
  )

  it('선택 이후 idle과 동일 데이터에서는 마커를 유지하고 실제 데이터 변경은 반영한다', async () => {
    const fakeSdk = createFakeSdk()
    const marker = {
      ...markerPresentation,
      id: 'a',
      latitude: 37.5666103,
      longitude: 126.9783882,
      name: 'a 단지',
      selected: true,
    }
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    const { rerender } = render(
      <NaverMap representation="INDIVIDUAL" markers={[marker]} />,
    )
    await waitFor(() => expect(fakeSdk.markerConstructor).toHaveBeenCalledOnce())
    const firstButton = createdMarkerButton(fakeSdk, 0)
    expect(firstButton).toHaveClass('is-selected')
    expect(fakeSdk.markerInstances[0].setZIndex).toHaveBeenLastCalledWith(30)
    rerender(<NaverMap representation="INDIVIDUAL"
      markers={[{ ...marker, selected: false }]} />)
    rerender(<NaverMap representation="INDIVIDUAL"
      markers={[{ ...marker }]} />)
    act(() => fakeSdk.emitIdle())
    expect(fakeSdk.markerConstructor).toHaveBeenCalledOnce()
    expect(fakeSdk.markerSetMap).not.toHaveBeenCalled()
    expect(firstButton).toHaveClass('is-selected')

    rerender(<NaverMap representation="INDIVIDUAL"
      markers={[{ ...marker, monthlyRent: {
        digits: '24', unit: '만', exactLabel: '240,000원',
      } }]} />)
    expect(fakeSdk.markerConstructor).toHaveBeenCalledTimes(2)
    expect(firstButton).not.toBeInTheDocument()
    expect(createdMarkerButton(fakeSdk, 1)).toHaveTextContent('월24만~')
    expect(createdMarkerButton(fakeSdk, 1)).toHaveClass('is-selected')
    expect(fakeSdk.markerInstances[1].setZIndex).toHaveBeenLastCalledWith(30)
    rerender(<NaverMap representation="INDIVIDUAL" markers={[]} />)
    expect(fakeSdk.markerSetMap).toHaveBeenCalledTimes(2)
    expect(createdMarkerButton(fakeSdk, 1)).not.toBeInTheDocument()
  })

  it('처음 표시하는 마커는 SDK 위치 요소 안에서 시간차를 두고 등장하고 효과를 정리한다', async () => {
    const fakeSdk = createFakeSdk()
    const markers = Array.from({ length: 8 }, (_, index) => ({
      ...markerPresentation,
      id: String(index),
      latitude: 37.5666103,
      longitude: 126.96939 + index * 0.0026,
      name: `${index} 단지`,
    }))
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    render(<NaverMap representation="INDIVIDUAL" markers={markers} />)

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
      latitude: 37.5666103,
      longitude: 126.96939 + index * 0.0026,
      name: `${id} 단지`,
    }))
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    const { rerender, unmount } = render(
      <NaverMap representation="INDIVIDUAL" markers={[a, b]}
        onMarkerSelect={onMarkerSelect} onMarkerHighlight={onMarkerHighlight} />,
    )
    const aButton = await screen.findByRole('button', { name: /^a 단지,/ })
    const bButton = screen.getByRole('button', { name: /^b 단지,/ })
    fireEvent.animationEnd(aButton.parentElement!)
    aButton.focus()
    const focus = vi.spyOn(HTMLButtonElement.prototype, 'focus')

    rerender(<NaverMap representation="INDIVIDUAL" markers={[a, c]}
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

    rerender(<NaverMap representation="INDIVIDUAL" markers={[
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
    render(<NaverMap representation="INDIVIDUAL" markers={[{
      ...markerPresentation, id: 'a', latitude: 37.5666103, longitude: 126.9783882, name: 'a 단지',
    }]} />)
    const button = await screen.findByRole('button', { name: /^a 단지,/ })
    expect(button.parentElement).not.toHaveClass('housing-marker-enter')
    expect(button.parentElement?.style.getPropertyValue('--marker-enter-delay')).toBe('')
  })

  it('hover 중인 마커가 제거되면 유지된 키보드 포커스 마커의 강조를 복원한다', async () => {
    const fakeSdk = createFakeSdk()
    const markers = ['a', 'b'].map((id, index) => ({
      ...markerPresentation, id, latitude: 37.5666103, longitude: 126.96939 + index * 0.0026, name: `${id} 단지`,
    }))
    function HighlightedMap({ items }: { items: NaverMapMarker[] }) {
      const [highlightedId, setHighlightedId] = useState<string | null>(null)
      return <NaverMap representation="INDIVIDUAL"
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
    const { rerender } = render(<NaverMap representation="AGGREGATE"
      aggregateMarkers={[aggregateMarker, second]} onAggregateMarkerSelect={onSelect} />)
    const seoulButton = await screen.findByRole('button', { name: /^서울 42곳,/ })
    fireEvent.animationEnd(seoulButton.parentElement!)
    const updated = { ...aggregateMarker, uniqueComplexCount: 17, expansionZoom: 13, latitude: 37.6 }
    rerender(<NaverMap representation="AGGREGATE"
      aggregateMarkers={[updated, second]} onAggregateMarkerSelect={onSelect} />)
    expect(screen.getByRole('button', { name: /^서울 42곳,/ })).toBe(seoulButton)
    const updatedButton = screen.getByRole('button', { name: /^경기 17곳,/ })
    expect(updatedButton.parentElement).not.toHaveClass('housing-marker-enter')
    fireEvent.click(updatedButton)
    expect(onSelect).toHaveBeenCalledExactlyOnceWith(updated)

    rerender(<NaverMap representation="INDIVIDUAL" markers={[{
      ...markerPresentation, id: second.groupKey, latitude: 37.5666103, longitude: 126.9783882, name: '개별 단지',
    }]} />)
    expect(screen.getByRole('button', { name: /^개별 단지,/ }).parentElement)
      .toHaveClass('housing-marker-enter')
  })

  it('선택이나 줌만 바뀌면 등장 효과를 재생하지 않고 새 조회 마커에만 적용한다', async () => {
    const fakeSdk = createFakeSdk()
    const markers = ['a', 'b'].map((id, index) => ({
      ...markerPresentation, id, latitude: 37.5666103, longitude: 126.9783882 + index * 0.0026, name: `${id} 단지`,
    }))
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    const { rerender } = render(<NaverMap representation="INDIVIDUAL" markers={markers} />)
    const buttons = await screen.findAllByRole('button', { name: /단지 상세 보기/ })
    buttons.forEach((button) => {
      expect(button.parentElement).toHaveClass('housing-marker-enter')
      fireEvent.animationEnd(button.parentElement!)
    })
    rerender(<NaverMap representation="INDIVIDUAL"
      markers={markers.map((marker) => ({ ...marker, selected: marker.id === 'a' }))} />)
    rerender(<NaverMap representation="INDIVIDUAL" markers={markers} />)
    act(() => {
      fakeSdk.setCurrentZoom(15)
      fakeSdk.emitIdle()
    })
    buttons.forEach((button) => {
      expect(button).toBeInTheDocument()
      expect(button.parentElement).not.toHaveClass('housing-marker-enter')
    })
    expect(fakeSdk.markerConstructor).toHaveBeenCalledTimes(2)
    rerender(<NaverMap representation="INDIVIDUAL" markers={[...markers, {
      ...markerPresentation, id: 'c', latitude: 37.5666103, longitude: 126.9757882, name: 'c 단지',
    }]} />)
    expect(screen.getByRole('button', { name: /^c 단지,/ }).parentElement)
      .toHaveClass('housing-marker-enter')
    expect(screen.getByRole('button', { name: /^a 단지,/ }).parentElement)
      .not.toHaveClass('housing-marker-enter')
  })

  it('개별 marker에 짧은 기관·유형과 최소 보증금·월세를 표시하고 정확한 값으로 안내한다', async () => {
    const fakeSdk = createFakeSdk()
    const marker = {
      ...markerPresentation,
      highlighted: true,
      id: '101',
      latitude: 37.5666103,
      longitude: 126.9783882,
      name: '서울 공공임대 1단지',
      selected: true,
    } satisfies NaverMapMarker
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)

    render(<NaverMap representation="INDIVIDUAL" markers={[marker]} />)

    await waitFor(() =>
      expect(fakeSdk.markerConstructor).toHaveBeenCalledOnce(),
    )
    const markerButton = createdMarkerButton(fakeSdk, 0)

    expect(markerButton).toHaveTextContent('보1천~')
    expect(markerButton).toHaveTextContent('월23만~')
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
      '보1천~월23만~',
    )
    expect(fakeSdk.markerConstructor.mock.calls[0]?.[0]).toMatchObject({
      icon: { anchor: { x: 56, y: 66 }, size: { width: 112, height: 66 } },
    })
  })

  it('금액만 바뀌어도 폭과 anchor를 함께 갱신하고 중심좌표를 유지한다', async () => {
    const fakeSdk = createFakeSdk()
    const marker: NaverMapMarker = {
      ...markerPresentation,
      id: 'precise-money',
      latitude: 37.5666103,
      longitude: 126.9783882,
      name: '정밀 금액 단지',
      selected: true,
    }
    const renderMap = (next: NaverMapMarker) => <NaverMap representation="INDIVIDUAL" markers={[next]} />
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    const { rerender } = render(renderMap(marker))
    await waitFor(() => expect(fakeSdk.markerConstructor).toHaveBeenCalledOnce())
    const originalButton = createdMarkerButton(fakeSdk, 0)
    const originalWidth = Number.parseFloat(originalButton.style.getPropertyValue('--marker-width'))

    rerender(renderMap({
      ...marker,
      deposit: { digits: '90,071,992.5', unit: '억', exactLabel: '9,007,199,254,740,991원' },
    }))
    expect(fakeSdk.markerConstructor).toHaveBeenCalledTimes(2)
    const button = createdMarkerButton(fakeSdk, 1)
    const width = Number.parseFloat(button.style.getPropertyValue('--marker-width'))

    expect(originalButton).not.toBeInTheDocument()
    expect(button).toHaveTextContent('보90,071,992.5억~')
    expect(button).toHaveAccessibleName(expect.stringContaining('보증금 최소 9,007,199,254,740,991원'))
    expect(button).toHaveClass('is-selected')
    expect(width).toBeGreaterThan(originalWidth)
    expect(fakeSdk.markerConstructor.mock.calls[1]?.[0]).toMatchObject({
      icon: { anchor: { x: width / 2, y: 66 }, size: { width, height: 66 } },
      position: { latitude: marker.latitude, longitude: marker.longitude },
    })

    rerender(renderMap(marker))
    expect(fakeSdk.markerConstructor).toHaveBeenCalledTimes(3)
    const restoredButton = createdMarkerButton(fakeSdk, 2)
    expect(button).not.toBeInTheDocument()
    expect(restoredButton.style.getPropertyValue('--marker-width')).toBe(`${originalWidth}px`)
    expect(restoredButton).toHaveClass('is-selected')
    expect(fakeSdk.markerConstructor.mock.calls[2]?.[0]).toMatchObject({
      icon: { anchor: { x: originalWidth / 2, y: 66 }, size: { width: originalWidth, height: 66 } },
      position: { latitude: marker.latitude, longitude: marker.longitude },
    })
  })

  it('marker 표시 문구가 바뀌면 overlay를 새 정보로 교체한다', async () => {
    const fakeSdk = createFakeSdk()
    const marker = {
      ...markerPresentation,
      id: '101',
      latitude: 37.5666103,
      longitude: 126.9783882,
      name: '테스트 단지',
    } satisfies NaverMapMarker
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)

    const { rerender } = render(<NaverMap representation="INDIVIDUAL" markers={[marker]} />)

    await waitFor(() =>
      expect(fakeSdk.markerConstructor).toHaveBeenCalledOnce(),
    )
    const previousButton = createdMarkerButton(fakeSdk, 0)

    rerender(
      <NaverMap representation="INDIVIDUAL"
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
      latitude: 37.5666103,
      longitude: 126.9783882,
      name: '정확한 금액 단지',
    }
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    const { rerender } = render(
      <NaverMap representation="INDIVIDUAL" markers={[marker]} />,
    )
    await waitFor(() => expect(fakeSdk.markerConstructor).toHaveBeenCalledOnce())
    const previousButton = createdMarkerButton(fakeSdk, 0)

    rerender(<NaverMap representation="INDIVIDUAL"
      markers={[{ ...marker, ...change }]} />)

    expect(fakeSdk.markerConstructor).toHaveBeenCalledTimes(2)
    expect(previousButton).not.toBeInTheDocument()
    const nextButton = createdMarkerButton(fakeSdk, 1)
    expect(nextButton.textContent).toBe(previousButton.textContent)
    expect(nextButton).toHaveAccessibleName(expect.stringContaining(expected))
    expect(nextButton.title).toContain(expected)
  })

  it.each<{
    description: string
    change: Partial<MapMarkerPresentation>
    expected: string
  }>([
    { description: '기관 축약값', change: { agencyLabel: 'SH' }, expected: 'SH국민' },
    { description: '임대유형 축약값', change: { rentalTypeLabel: '행복' }, expected: 'LH행복' },
    {
      description: '보증금 숫자',
      change: { deposit: { ...markerPresentation.deposit, digits: '2' } },
      expected: '보2천~',
    },
    {
      description: '보증금 단위',
      change: { deposit: { ...markerPresentation.deposit, unit: '억' } },
      expected: '보1억~',
    },
    {
      description: '월세 숫자',
      change: { monthlyRent: { ...markerPresentation.monthlyRent, digits: '25' } },
      expected: '월25만~',
    },
    {
      description: '월세 단위',
      change: { monthlyRent: { ...markerPresentation.monthlyRent, unit: '천' } },
      expected: '월23천~',
    },
  ])('$description 하나만 바뀌어도 표시를 갱신하고 선택·강조·포커스를 보존한다', async ({ change, expected }) => {
    const fakeSdk = createFakeSdk()
    const marker: NaverMapMarker = {
      ...markerPresentation, id: '101', latitude: 37.5666103, longitude: 126.9783882,
      name: '표시 갱신 단지', selected: true, highlighted: true,
    }
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    const { rerender } = render(<NaverMap representation="INDIVIDUAL" markers={[marker]} />)
    await waitFor(() => expect(fakeSdk.markerConstructor).toHaveBeenCalledOnce())
    createdMarkerButton(fakeSdk, 0).focus()

    rerender(<NaverMap representation="INDIVIDUAL" markers={[{ ...marker, ...change }]} />)

    const updated = screen.getByRole('button', { name: /표시 갱신 단지/ })
    expect(updated).toHaveTextContent(expected)
    expect(updated).toHaveClass('is-selected', 'is-highlighted')
    expect(updated).toHaveAttribute('aria-pressed', 'true')
    await waitFor(() => expect(updated).toHaveFocus())
  })

  it.each(['individual', 'aggregate'] as const)(
    '%s 마커를 제거하면 이전 버튼의 선택과 강조 이벤트를 정리한다',
    async (kind) => {
      const fakeSdk = createFakeSdk()
      const onMarkerSelect = vi.fn()
      const onAggregateMarkerSelect = vi.fn()
      const onMarkerHighlight = vi.fn()
      const marker: NaverMapMarker = {
        ...markerPresentation, id: '101', latitude: 37.5666103, longitude: 126.9783882, name: '제거할 단지',
      }
      loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
      const renderMap = (showMarker: boolean) => kind === 'individual'
        ? <NaverMap representation="INDIVIDUAL" markers={showMarker ? [marker] : []}
            onMarkerSelect={onMarkerSelect} onMarkerHighlight={onMarkerHighlight} />
        : <NaverMap representation="AGGREGATE" aggregateMarkers={showMarker ? [aggregateMarker] : []}
            onAggregateMarkerSelect={onAggregateMarkerSelect} />
      const { rerender } = render(renderMap(true))
      await waitFor(() => expect(fakeSdk.markerConstructor).toHaveBeenCalledOnce())
      const removedButton = createdMarkerButton(fakeSdk, 0)
      fireEvent.mouseEnter(removedButton)

      rerender(renderMap(false))
      expect(removedButton).not.toBeInTheDocument()
      if (kind === 'individual') {
        expect(onMarkerHighlight).toHaveBeenLastCalledWith(null)
      }
      onMarkerHighlight.mockClear()
      fireEvent.click(removedButton)
      fireEvent.mouseEnter(removedButton)
      fireEvent.focus(removedButton)

      expect(onMarkerSelect).not.toHaveBeenCalled()
      expect(onAggregateMarkerSelect).not.toHaveBeenCalled()
      expect(onMarkerHighlight).not.toHaveBeenCalled()
      expect(fakeSdk.markerInstances[0].setMap).toHaveBeenCalledExactlyOnceWith(null)
    },
  )

  it('금액 결측은 하이픈으로 표시하고 확인된 0원과 구분한다', async () => {
    const fakeSdk = createFakeSdk()
    const marker: NaverMapMarker = {
      ...markerPresentation,
      deposit: null,
      monthlyRent: null,
      id: '101',
      latitude: 37.5666103,
      longitude: 126.9783882,
      name: '금액 확인 단지',
    }
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    const { rerender } = render(<NaverMap representation="INDIVIDUAL" markers={[marker]} />)
    await waitFor(() => expect(fakeSdk.markerConstructor).toHaveBeenCalledOnce())
    const missingButton = createdMarkerButton(fakeSdk, 0)
    expect(missingButton).toHaveTextContent('보-월-')
    expect(missingButton).not.toHaveTextContent('~')
    expect(missingButton).toHaveAccessibleName(
      '금액 확인 단지, 한국토지주택공사 · 국민임대, 보증금 공고문 확인, 월 임대료 공고문 확인, 단지 상세 보기',
    )

    rerender(<NaverMap representation="INDIVIDUAL" markers={[{
      ...marker,
      deposit: { digits: '0', unit: '원', exactLabel: '0원' },
    }]} />)

    const zeroButton = createdMarkerButton(fakeSdk, 1)
    expect(zeroButton).toHaveTextContent('보0원~월-')
    expect(zeroButton.querySelectorAll('.housing-map-marker__from')).toHaveLength(1)
    expect(zeroButton).toHaveAccessibleName(
      '금액 확인 단지, 한국토지주택공사 · 국민임대, 보증금 최소 0원, 월 임대료 공고문 확인, 단지 상세 보기',
    )
  })

  it('기관과 유형이 모두 누락된 마커는 안내를 한 번만 표시하고 항목 의미를 남긴다', async () => {
    const fakeSdk = createFakeSdk()
    const marker: NaverMapMarker = {
      ...markerPresentation,
      agencyLabel: '공고문 확인',
      agencyName: '공고문 확인',
      rentalTypeLabel: '공고문 확인',
      rentalTypeName: '공고문 확인',
      id: 'missing-metadata',
      latitude: 37.5666103,
      longitude: 126.9783882,
      name: '기관 확인 단지',
    }
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    const { rerender } = render(<NaverMap representation="INDIVIDUAL" markers={[marker]} />)
    await waitFor(() => expect(fakeSdk.markerConstructor).toHaveBeenCalledOnce())
    const button = createdMarkerButton(fakeSdk, 0)
    const top = button.querySelector('.housing-map-marker__top')

    expect(top).toHaveTextContent(/^공고문 확인$/)
    expect(top).toHaveAttribute('data-missing-summary', 'true')
    expect(button).toHaveAccessibleName(expect.stringContaining('공급기관 및 임대유형 공고문 확인'))

    rerender(<NaverMap representation="INDIVIDUAL" markers={[{
      ...marker,
      agencyLabel: 'LH',
      agencyName: '한국토지주택공사',
    }]} />)
    const mixedTop = createdMarkerButton(fakeSdk, 1).querySelector('.housing-map-marker__top')
    expect(mixedTop).toHaveTextContent('LH공고문 확인')
    expect(mixedTop).toHaveAttribute('data-missing-position', 'last')
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
        onAggregateMarkerSelect={vi.fn()}
        onTransitionInterrupt={onTransitionInterrupt}
        representation="AGGREGATE"
      />,
    )
    rerender(
      <NaverMap
        aggregateMarkers={[aggregateMarker]}
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
        onAggregateMarkerSelect={onAggregateMarkerSelect}
        representation="AGGREGATE"
      />,
    )
    expect(markerButton).toBeEnabled()
    fireEvent.click(markerButton)
    expect(onAggregateMarkerSelect).toHaveBeenCalledWith(aggregateMarker)
  })

  it('같은 좌표의 선택된 단지는 클러스터에서 분리해 개별 표시한다', async () => {
    const fakeSdk = createFakeSdk()
    const onMarkerSelect = vi.fn()
    const markers = [
      {
        ...markerPresentation,
        id: 'same-a',
        latitude: 37.5666103,
        longitude: 126.9783882,
        name: '같은 좌표 첫 단지',
        selected: true,
      },
      {
        ...markerPresentation,
        id: 'same-b',
        latitude: 37.5666103,
        longitude: 126.9783882,
        name: '같은 좌표 둘째 단지',
        highlighted: true,
      },
    ] satisfies NaverMapMarker[]
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)

    render(
      <NaverMap
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
      button.classList.contains('housing-map-marker'),
    )).toBe(true)
    expect(fakeSdk.fromCoordToOffset).toHaveBeenCalled()
    fireEvent.click(buttons[1])

    expect(onMarkerSelect).toHaveBeenCalledWith('same-b')
    expect(fakeSdk.fitBoundsMap).not.toHaveBeenCalled()
  })


  it('초기·프로그램 이동·사용자 이동을 구분하고 연속 idle도 같은 원인을 유지한다', async () => {
    const fakeSdk = createFakeSdk()
    const onViewportChange = vi.fn()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    const { rerender } = render(<NaverMap representation="INDIVIDUAL" markers={[]}
      onViewportChange={onViewportChange} />)
    await waitFor(() => expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce())
    act(() => fakeSdk.emitIdle())
    expect(onViewportChange).toHaveBeenLastCalledWith(expect.anything(), { cause: 'initial' })

    rerender(<NaverMap representation="INDIVIDUAL" markers={[]} cameraRequestId={1}
      cameraTarget={{ latitude: 37.567, longitude: 126.98 }} onViewportChange={onViewportChange} />)
    act(() => { fakeSdk.emitIdle(); fakeSdk.emitIdle() })
    expect(onViewportChange.mock.calls.slice(-2).map(([, metadata]) => metadata.cause))
      .toEqual(['programmatic', 'programmatic'])
    act(() => {
      fakeSdk.emitDragStart()
      fakeSdk.setCurrentCenter(37.57, 126.99)
      fakeSdk.emitIdle()
    })
    expect(onViewportChange).toHaveBeenLastCalledWith(expect.objectContaining({
      center: { latitude: 37.57, longitude: 126.99 },
    }), { cause: 'user' })
  })

  it.each([
    { label: '충분히 보이는', offsetX: 0, moves: false },
    { label: '가장자리의', offsetX: 500, moves: true },
    { label: '지도 밖의', offsetX: 1500, moves: true },
  ])('$label 단지 선택은 필요한 만큼만 이동하고 기존 줌을 유지한다', async ({ offsetX, moves }) => {
    const fakeSdk = createFakeSdk()
    const onViewportChange = vi.fn()
    const center = { latitude: 37.5666103, longitude: 126.9783882 }
    const marker = { ...markerPresentation, id: 'selected', name: '선택 단지',
      latitude: center.latitude, longitude: center.longitude + offsetX / 50_000, selected: true }
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    const { rerender } = render(<NaverMap representation="INDIVIDUAL" markers={[marker]}
      onViewportChange={onViewportChange} />)
    await waitFor(() => expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce())
    act(() => fakeSdk.emitInit())
    rerender(<NaverMap representation="INDIVIDUAL" markers={[marker]} cameraRequestId={1}
      cameraTarget={{ latitude: marker.latitude, longitude: marker.longitude,
        revealPadding: { top: 20, right: 20, bottom: 60, left: 300 } }}
      onViewportChange={onViewportChange} />)
    expect(fakeSdk.morphMap).not.toHaveBeenCalled()
    expect(fakeSdk.setZoomMap).not.toHaveBeenCalled()
    expect(fakeSdk.fitBoundsMap).not.toHaveBeenCalled()
    if (moves) {
      expect(fakeSdk.panToMap).toHaveBeenCalledOnce()
      const destination = fakeSdk.panToMap.mock.calls[0][0] as typeof center
      const markerX = (marker.longitude - destination.longitude) * 50_000 + 512
      if (offsetX > 1024) expect(markerX).toBeCloseTo(652)
      else {
        expect(destination.longitude).toBeGreaterThan(center.longitude)
        expect(destination.longitude).toBeLessThan(marker.longitude)
        expect(markerX).toBeLessThan(984)
      }
      act(() => fakeSdk.emitIdle())
    } else {
      expect(fakeSdk.panToMap).not.toHaveBeenCalled()
    }
    expect(onViewportChange).toHaveBeenLastCalledWith(expect.objectContaining({ zoom: 14 }),
      { cause: 'programmatic' })
  })

  it('단지 한 곳의 동일 좌표 bounds는 작은 여백과 최대 줌을 넣어 맞춘다', async () => {
    const fakeSdk = createFakeSdk()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    render(<NaverMap representation="INDIVIDUAL" markers={[]} cameraRequestId={1}
      cameraTarget={{ latitude: 37.6, longitude: 127, maxZoom: 15,
        bounds: { southWestLat: 37.6, southWestLng: 127, northEastLat: 37.6, northEastLng: 127 } }} />)
    await waitFor(() => expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce())
    act(() => fakeSdk.emitInit())
    expect(fakeSdk.fitBoundsMap).toHaveBeenCalledExactlyOnceWith([
      { latitude: expect.closeTo(37.59995), longitude: expect.closeTo(126.99995) },
      { latitude: expect.closeTo(37.60005), longitude: expect.closeTo(127.00005) },
    ], { maxZoom: 15 })
  })

  it('겹치는 카드만 묶고 클러스터 선택은 목록 검색 없이 여백과 최대 줌으로 이동한다', async () => {
    const fakeSdk = createFakeSdk()
    const onViewportChange = vi.fn()
    const onMarkerSelect = vi.fn()
    const markers = [0, 0.0005, 0.006].map((offset, index) => ({
      ...markerPresentation, id: String(index), name: `단지 ${index}`,
      latitude: 37.5666103, longitude: 126.9783882 + offset,
    }))
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    render(<NaverMap representation="INDIVIDUAL" markers={markers}
      onViewportChange={onViewportChange} onMarkerSelect={onMarkerSelect}
      visiblePadding={{ top: 20, right: 0, bottom: 40, left: 300 }} />)
    const cluster = await screen.findByRole('button', { name: '단지 2곳, 모여 있는 단지 확대해서 보기' })
    expect(screen.getByRole('button', { name: /^단지 2,/ })).toBeInTheDocument()
    expect(fakeSdk.markerConstructor).toHaveBeenCalledTimes(2)
    fireEvent.click(cluster)
    expect(fakeSdk.fitBoundsMap).toHaveBeenCalledExactlyOnceWith([
      { latitude: 37.5666103, longitude: 126.9783882 },
      { latitude: 37.5666103, longitude: 126.9788882 },
    ], { top: 116, right: 80, bottom: 120, left: 380, maxZoom: 17 })
    expect(onMarkerSelect).not.toHaveBeenCalled()
    act(() => fakeSdk.emitIdle())
    expect(onViewportChange).toHaveBeenLastCalledWith(expect.anything(), { cause: 'programmatic' })
  })

  it('동일 좌표 클러스터는 계속 확대하지 않고 단지 선택 목록을 열어 키보드로 닫는다', async () => {
    const fakeSdk = createFakeSdk()
    const onMarkerSelect = vi.fn()
    const markers = ['a', 'b'].map((id) => ({ ...markerPresentation, id, name: `${id} 단지`,
      latitude: 37.5666103, longitude: 126.9783882 }))
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    render(<NaverMap representation="INDIVIDUAL" markers={markers} onMarkerSelect={onMarkerSelect} />)
    const cluster = await screen.findByRole('button', { name: '단지 2곳, 모여 있는 단지 확대해서 보기' })
    cluster.focus()
    fireEvent.click(cluster)
    const picker = screen.getByRole('region', { name: '모여 있는 단지' })
    expect(picker).toHaveFocus()
    expect(fakeSdk.fitBoundsMap).not.toHaveBeenCalled()
    fireEvent.keyDown(picker, { key: 'Escape' })
    expect(screen.queryByRole('region', { name: '모여 있는 단지' })).not.toBeInTheDocument()
    expect(cluster).toHaveFocus()
    fireEvent.click(cluster)
    fireEvent.click(screen.getByRole('button', { name: 'b 단지' }))
    expect(onMarkerSelect).toHaveBeenCalledExactlyOnceWith('b')
    expect(screen.queryByRole('region', { name: '모여 있는 단지' })).not.toBeInTheDocument()
  })

  it('드래그 중에는 새로 보이는 핀을 갱신하고 idle 전까지 검색 viewport를 알리지 않는다', async () => {
    const fakeSdk = createFakeSdk()
    const onViewportChange = vi.fn()
    const markers = ['a', 'b'].map((id, index) => ({ ...markerPresentation, id, name: `${id} 단지`,
      latitude: 37.5666103, longitude: 126.9783882 + index * 0.1 }))
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)
    render(<NaverMap representation="INDIVIDUAL" markers={markers} onViewportChange={onViewportChange} />)
    await screen.findByRole('button', { name: /^a 단지,/ })
    expect(screen.queryByRole('button', { name: /^b 단지,/ })).not.toBeInTheDocument()
    act(() => {
      fakeSdk.emitDragStart()
      fakeSdk.setCurrentCenter(markers[1].latitude, markers[1].longitude)
      fakeSdk.emitBoundsChanged()
    })
    await screen.findByRole('button', { name: /^b 단지,/ })
    expect(screen.queryByRole('button', { name: /^a 단지,/ })).not.toBeInTheDocument()
    expect(onViewportChange).not.toHaveBeenCalled()
    act(() => fakeSdk.emitIdle())
    expect(onViewportChange).toHaveBeenCalledExactlyOnceWith(expect.anything(), { cause: 'user' })
  })

  it('인증 실패에는 재시도 없이 설정 확인을 안내한다', async () => {
    loadNaverMapsSdkMock.mockRejectedValue(
      new NaverMapsSdkError(
        'authentication',
        'NAVER Maps SDK 인증에 실패했습니다.',
      ),
    )

    render(<NaverMap representation="INDIVIDUAL" markers={[]} />)

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
      <NaverMap representation="INDIVIDUAL" markers={[]}
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
    expect(fakeSdk.removeListener).toHaveBeenCalledTimes(4)
    expect(fakeSdk.removeListener).toHaveBeenCalledWith(
      expect.objectContaining({ eventName: 'init' }),
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

    render(<NaverMap representation="INDIVIDUAL" markers={[]} />)

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

    render(<NaverMap representation="INDIVIDUAL" markers={[]} />)

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

    render(<NaverMap representation="INDIVIDUAL" markers={[]} onViewportChange={vi.fn()} />)

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '지도를 표시하지 못했습니다.',
    )
    expect(fakeSdk.destroyMap).toHaveBeenCalledOnce()
    expect(fakeSdk.removeListener).toHaveBeenCalledTimes(4)
    expect(fakeSdk.removeListener).toHaveBeenCalledWith(
      expect.objectContaining({ eventName: 'init' }),
    )
  })

  it('준비되기 전에 unmount되면 늦은 응답으로 지도를 만들지 않는다', async () => {
    const fakeSdk = createFakeSdk()
    const deferred = createDeferred<typeof naver.maps>()
    loadNaverMapsSdkMock.mockReturnValue(deferred.promise)
    const { unmount } = render(<NaverMap representation="INDIVIDUAL" markers={[]} />)

    unmount()
    await act(async () => deferred.resolve(fakeSdk.maps))

    expect(fakeSdk.mapConstructor).not.toHaveBeenCalled()
  })

  it('StrictMode 재실행에서도 생성한 지도를 한 번 해제한다', async () => {
    const fakeSdk = createFakeSdk()
    loadNaverMapsSdkMock.mockResolvedValue(fakeSdk.maps)

    const { unmount } = render(
      <StrictMode>
        <NaverMap representation="INDIVIDUAL" markers={[]} />
      </StrictMode>,
    )

    await waitFor(() => expect(fakeSdk.mapConstructor).toHaveBeenCalledOnce())
    unmount()

    expect(fakeSdk.destroyMap).toHaveBeenCalledOnce()
  })
})
