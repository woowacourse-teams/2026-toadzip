import { useEffect, useRef, useState } from 'react'
import type { ViewportSnapshot } from '../../public-housing/map/viewportPolicy.ts'
import {
  loadNaverMapsSdk,
  NaverMapsSdkError,
  subscribeToNaverMapsAuthenticationFailure,
  type NaverMapsSdkErrorCode,
} from './loadNaverMapsSdk.ts'

type MapFailureReason = NaverMapsSdkErrorCode | 'configuration' | 'initialization'

type MapStatus =
  | { kind: 'loading' }
  | { kind: 'ready' }
  | { kind: 'unavailable'; reason: MapFailureReason }

interface FailureContent {
  description: string
  retryable: boolean
  title: string
}

const INITIAL_CENTER = {
  latitude: 37.5666103,
  longitude: 126.9783882,
}

export interface NaverMapComplexMarker {
  readonly kind: 'complex'
  id: string
  latitude: number
  longitude: number
  name: string
  regionCode?: string
  regionName?: string
  selected?: boolean
}

export interface NaverMapRegionClusterMarker {
  readonly kind: 'region-cluster'
  readonly latitude: number
  readonly longitude: number
  readonly regionCode: string
  readonly regionName: string
  readonly uniqueComplexCount: number
}

export type NaverMapMarker =
  | NaverMapComplexMarker
  | NaverMapRegionClusterMarker

export interface NaverMapCameraTarget {
  readonly latitude: number
  readonly longitude: number
  readonly zoom?: number
}

export interface NaverMapProps {
  cameraTarget?: NaverMapCameraTarget
  focusRegionCode?: string | null
  markers?: NaverMapMarker[]
  onClusterSelect?: (regionCode: string) => void
  onMarkerSelect?: (complexId: string) => void
  onViewportChange?: (viewport: ViewportSnapshot) => void
}

const FAILURE_CONTENT: Record<MapFailureReason, FailureContent> = {
  configuration: {
    title: '지도 설정이 준비되지 않았습니다.',
    description: '현재 환경의 NAVER Maps Client ID를 확인해 주세요.',
    retryable: false,
  },
  authentication: {
    title: '지도 인증에 실패했습니다.',
    description:
      'Client ID와 현재 주소의 Web 서비스 URL 설정을 확인한 후 새로고침해 주세요.',
    retryable: false,
  },
  network: {
    title: '지도를 불러오지 못했습니다.',
    description: '네트워크 연결을 확인한 뒤 다시 시도해 주세요.',
    retryable: true,
  },
  'invalid-sdk': {
    title: '지도 SDK를 초기화하지 못했습니다.',
    description: '잠시 후 다시 시도해 주세요.',
    retryable: true,
  },
  initialization: {
    title: '지도를 표시하지 못했습니다.',
    description: '잠시 후 다시 시도해 주세요.',
    retryable: true,
  },
}

function toFailureReason(error: unknown): MapFailureReason {
  if (error instanceof NaverMapsSdkError) {
    return error.code
  }

  return 'invalid-sdk'
}

function destroyMapSafely(mapInstance: naver.maps.Map | null) {
  try {
    mapInstance?.destroy()
  } catch {
    // 인증 실패 시 NAVER SDK가 지도 객체를 먼저 무효화할 수 있습니다.
  }
}

function MapLoading() {
  return (
    <div className="map-state-layer">
      <div className="map-state-card" role="status" aria-live="polite">
        <span className="map-loading-indicator" aria-hidden="true" />
        <strong>지도를 불러오고 있습니다.</strong>
        <span>잠시만 기다려 주세요.</span>
      </div>
    </div>
  )
}

interface MapUnavailableProps {
  onRetry: () => void
  reason: MapFailureReason
}

function MapUnavailable({ onRetry, reason }: MapUnavailableProps) {
  const content = FAILURE_CONTENT[reason]

  return (
    <div className="map-state-layer">
      <div className="map-state-card map-state-card--error" role="alert">
        <span className="map-error-mark" aria-hidden="true">
          !
        </span>
        <strong>{content.title}</strong>
        <span>{content.description}</span>
        {content.retryable && (
          <button className="map-retry-button" type="button" onClick={onRetry}>
            다시 시도
          </button>
        )}
      </div>
    </div>
  )
}

export default function NaverMap({
  cameraTarget,
  focusRegionCode,
  markers = [],
  onClusterSelect,
  onMarkerSelect,
  onViewportChange,
}: NaverMapProps) {
  const mapContainerRef = useRef<HTMLDivElement>(null)
  const mapInstanceRef = useRef<naver.maps.Map | null>(null)
  const mapsRef = useRef<typeof naver.maps | null>(null)
  const markerOverlaysRef = useRef<naver.maps.Marker[]>([])
  const appliedCameraTargetRef = useRef<NaverMapCameraTarget | null>(null)
  const completedFocusRegionCodeRef = useRef<string | null>(null)
  const onClusterSelectRef = useRef(onClusterSelect)
  const onMarkerSelectRef = useRef(onMarkerSelect)
  const onViewportChangeRef = useRef(onViewportChange)
  const [attempt, setAttempt] = useState(0)
  const [markerAnnouncement, setMarkerAnnouncement] = useState('')
  const [status, setStatus] = useState<MapStatus>({ kind: 'loading' })
  const cameraLatitude = cameraTarget?.latitude
  const cameraLongitude = cameraTarget?.longitude
  const cameraZoom = cameraTarget?.zoom

  useEffect(() => {
    onClusterSelectRef.current = onClusterSelect
  }, [onClusterSelect])

  useEffect(() => {
    onMarkerSelectRef.current = onMarkerSelect
  }, [onMarkerSelect])

  useEffect(() => {
    onViewportChangeRef.current = onViewportChange
  }, [onViewportChange])

  useEffect(() => {
    const clientId = import.meta.env.VITE_NAVER_MAPS_CLIENT_ID?.trim() ?? ''

    if (!clientId) {
      setStatus({ kind: 'unavailable', reason: 'configuration' })
      return
    }

    let cancelled = false
    let mapInstance: naver.maps.Map | null = null
    let resizeObserver: ResizeObserver | null = null
    let idleListener: naver.maps.MapEventListener | null = null
    setStatus({ kind: 'loading' })

    const removeIdleListener = () => {
      if (!idleListener || !mapsRef.current) {
        return
      }
      mapsRef.current.Event.removeListener(idleListener)
      idleListener = null
    }

    const handleAuthenticationFailure = () => {
      if (cancelled) {
        return
      }

      resizeObserver?.disconnect()
      resizeObserver = null
      removeIdleListener()
      const failedMap = mapInstance
      mapInstance = null
      mapInstanceRef.current = null
      mapsRef.current = null
      appliedCameraTargetRef.current = null
      completedFocusRegionCodeRef.current = null
      setMarkerAnnouncement('')
      setStatus({ kind: 'unavailable', reason: 'authentication' })
      destroyMapSafely(failedMap)
    }
    const unsubscribeAuthenticationFailure =
      subscribeToNaverMapsAuthenticationFailure(handleAuthenticationFailure)

    loadNaverMapsSdk(clientId)
      .then((maps) => {
        if (cancelled || !mapContainerRef.current) {
          return
        }

        try {
          const createdMap = new maps.Map(mapContainerRef.current, {
            center: new maps.LatLng(
              INITIAL_CENTER.latitude,
              INITIAL_CENTER.longitude,
            ),
            gl: true,
            keyboardShortcuts: true,
            zoom: 14,
            zoomControl: true,
          })
          mapInstance = createdMap
          mapInstanceRef.current = createdMap
          mapsRef.current = maps
          appliedCameraTargetRef.current = null
          completedFocusRegionCodeRef.current = null

          const emitViewport = () => {
            const viewport = readViewport(createdMap)
            if (viewport) {
              onViewportChangeRef.current?.(viewport)
            }
          }

          if (onViewportChangeRef.current) {
            idleListener = maps.Event.addListener(
              createdMap,
              'idle',
              emitViewport,
            )
          }

          if (typeof ResizeObserver === 'function') {
            resizeObserver = new ResizeObserver(() => createdMap.autoResize())
            resizeObserver.observe(mapContainerRef.current)
          }

          setStatus({ kind: 'ready' })
        } catch {
          resizeObserver?.disconnect()
          resizeObserver = null
          removeIdleListener()
          const failedMap = mapInstance
          mapInstance = null
          mapInstanceRef.current = null
          mapsRef.current = null
          appliedCameraTargetRef.current = null
          completedFocusRegionCodeRef.current = null
          setStatus({ kind: 'unavailable', reason: 'initialization' })
          destroyMapSafely(failedMap)
        }
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          setStatus({ kind: 'unavailable', reason: toFailureReason(error) })
        }
      })

    return () => {
      cancelled = true
      unsubscribeAuthenticationFailure()
      resizeObserver?.disconnect()
      removeIdleListener()
      clearMarkers(markerOverlaysRef.current)
      markerOverlaysRef.current = []
      mapInstanceRef.current = null
      mapsRef.current = null
      appliedCameraTargetRef.current = null
      completedFocusRegionCodeRef.current = null
      destroyMapSafely(mapInstance)
    }
  }, [attempt])

  useEffect(() => {
    const mapInstance = mapInstanceRef.current
    const maps = mapsRef.current

    if (!mapInstance || !maps || status.kind !== 'ready') {
      return
    }

    clearMarkers(markerOverlaysRef.current)
    const createdMarkers = markers.map((marker) =>
      createMarker(maps, mapInstance, marker, () => selectMarker(
        marker,
        onClusterSelectRef.current,
        onMarkerSelectRef.current,
      )),
    )
    markerOverlaysRef.current = createdMarkers.map(({ overlay }) => overlay)
    const focusTarget = findRegionFocusTarget(
      createdMarkers,
      markers,
      focusRegionCode,
    )
    let focusTimeout: number | null = null

    if (focusRegionCode === undefined || focusRegionCode === null) {
      completedFocusRegionCodeRef.current = null
      setMarkerAnnouncement('')
    }
    if (
      focusRegionCode !== undefined
      && focusRegionCode !== null
      && focusTarget === null
      && completedFocusRegionCodeRef.current !== focusRegionCode
    ) {
      setMarkerAnnouncement('')
    }
    if (
      focusTarget !== null
      && completedFocusRegionCodeRef.current !== focusRegionCode
    ) {
      setMarkerAnnouncement(
        `${focusTarget.regionName}의 개별 단지 ${focusTarget.count}곳을 지도에 표시했습니다.`,
      )
      focusTimeout = window.setTimeout(() => {
        focusTarget.button.focus()
        completedFocusRegionCodeRef.current = focusRegionCode ?? null
      }, 0)
    }

    return () => {
      if (focusTimeout !== null) {
        window.clearTimeout(focusTimeout)
      }
      clearMarkers(markerOverlaysRef.current)
      markerOverlaysRef.current = []
    }
  }, [focusRegionCode, markers, status.kind])

  useEffect(() => {
    const mapInstance = mapInstanceRef.current
    const maps = mapsRef.current

    if (!mapInstance || !maps || status.kind !== 'ready') {
      return
    }

    if (
      cameraLatitude === undefined ||
      cameraLongitude === undefined ||
      !isValidCameraTarget(cameraLatitude, cameraLongitude, cameraZoom)
    ) {
      appliedCameraTargetRef.current = null
      return
    }

    const nextTarget: NaverMapCameraTarget = {
      latitude: cameraLatitude,
      longitude: cameraLongitude,
      zoom: cameraZoom,
    }
    const previousTarget = appliedCameraTargetRef.current

    if (cameraCoordinatesChanged(previousTarget, nextTarget)) {
      mapInstance.panTo(new maps.LatLng(cameraLatitude, cameraLongitude))
    }

    if (cameraZoom !== undefined && previousTarget?.zoom !== cameraZoom) {
      mapInstance.setZoom(cameraZoom)
    }

    appliedCameraTargetRef.current = nextTarget
  }, [cameraLatitude, cameraLongitude, cameraZoom, status.kind])

  const retry = () => {
    setStatus({ kind: 'loading' })
    setAttempt((currentAttempt) => currentAttempt + 1)
  }

  const isLoading = status.kind === 'loading'
  const isReady = status.kind === 'ready'

  return (
    <section
      className="map-region"
      aria-labelledby="map-title"
      aria-describedby="map-description"
      aria-busy={isLoading}
    >
      <h1 className="visually-hidden" id="map-title">
        공공임대주택 지도
      </h1>
      <p className="visually-hidden" id="map-description">
        현재 지도 영역의 공공임대주택 단지를 표시합니다.
      </p>
      <div
        className="map-surface"
        ref={mapContainerRef}
        aria-hidden={!isReady}
      />
      {markerAnnouncement && (
        <p
          className="visually-hidden"
          role="status"
          aria-live="polite"
          aria-atomic="true"
        >
          {markerAnnouncement}
        </p>
      )}
      {isLoading && <MapLoading />}
      {status.kind === 'unavailable' && (
        <MapUnavailable reason={status.reason} onRetry={retry} />
      )}
    </section>
  )
}

function isValidCameraTarget(
  latitude: number,
  longitude: number,
  zoom: number | undefined,
): boolean {
  return (
    Number.isFinite(latitude) &&
    latitude >= -90 &&
    latitude <= 90 &&
    Number.isFinite(longitude) &&
    longitude >= -180 &&
    longitude <= 180 &&
    (zoom === undefined || Number.isFinite(zoom))
  )
}

function cameraCoordinatesChanged(
  previousTarget: NaverMapCameraTarget | null,
  nextTarget: NaverMapCameraTarget,
): boolean {
  return (
    previousTarget === null ||
    previousTarget.latitude !== nextTarget.latitude ||
    previousTarget.longitude !== nextTarget.longitude
  )
}

function readViewport(mapInstance: naver.maps.Map): ViewportSnapshot | null {
  const bounds = mapInstance.getBounds()
  const southWest = readCoordinate(bounds, 'getSW')
  const northEast = readCoordinate(bounds, 'getNE')
  const zoom = mapInstance.getZoom()

  if (!southWest || !northEast || !Number.isFinite(zoom)) {
    return null
  }

  return {
    bounds: {
      southWestLat: southWest.latitude,
      southWestLng: southWest.longitude,
      northEastLat: northEast.latitude,
      northEastLng: northEast.longitude,
    },
    zoom,
  }
}

function readCoordinate(
  bounds: naver.maps.Bounds,
  methodName: 'getNE' | 'getSW',
): { latitude: number; longitude: number } | null {
  const method: unknown = Reflect.get(bounds, methodName)
  if (typeof method !== 'function') {
    return null
  }

  const coordinate: unknown = Reflect.apply(method, bounds, [])
  if (typeof coordinate !== 'object' || coordinate === null) {
    return null
  }

  const latitudeMethod: unknown = Reflect.get(coordinate, 'lat')
  const longitudeMethod: unknown = Reflect.get(coordinate, 'lng')
  if (
    typeof latitudeMethod !== 'function' ||
    typeof longitudeMethod !== 'function'
  ) {
    return null
  }

  const latitude: unknown = Reflect.apply(latitudeMethod, coordinate, [])
  const longitude: unknown = Reflect.apply(longitudeMethod, coordinate, [])
  if (typeof latitude !== 'number' || typeof longitude !== 'number') {
    return null
  }

  return { latitude, longitude }
}

interface CreatedMarker {
  readonly button: HTMLButtonElement
  readonly overlay: naver.maps.Marker
}

interface RegionFocusTarget {
  readonly button: HTMLButtonElement
  readonly count: number
  readonly regionName: string
}

function selectMarker(
  marker: NaverMapMarker,
  onClusterSelect: ((regionCode: string) => void) | undefined,
  onMarkerSelect: ((complexId: string) => void) | undefined,
) {
  if (marker.kind === 'region-cluster') {
    onClusterSelect?.(marker.regionCode)
    return
  }
  onMarkerSelect?.(marker.id)
}

function findRegionFocusTarget(
  createdMarkers: readonly CreatedMarker[],
  markers: readonly NaverMapMarker[],
  focusRegionCode: string | null | undefined,
): RegionFocusTarget | null {
  if (focusRegionCode === undefined || focusRegionCode === null) {
    return null
  }
  const markerIndex = markers.findIndex((marker) =>
    marker.kind === 'complex' && marker.regionCode === focusRegionCode,
  )
  const marker = markers[markerIndex]
  const createdMarker = createdMarkers[markerIndex]
  if (marker?.kind !== 'complex' || createdMarker === undefined) {
    return null
  }
  const count = markers.filter((candidate) =>
    candidate.kind === 'complex'
      && candidate.regionCode === focusRegionCode,
  ).length
  return {
    button: createdMarker.button,
    count,
    regionName: marker.regionName?.trim() || '선택한 지역',
  }
}

function createMarker(
  maps: typeof naver.maps,
  mapInstance: naver.maps.Map,
  marker: NaverMapMarker,
  onSelect: () => void,
): CreatedMarker {
  if (marker.kind === 'region-cluster') {
    return createRegionClusterMarker(maps, mapInstance, marker, onSelect)
  }
  return createComplexMarker(maps, mapInstance, marker, onSelect)
}

function createComplexMarker(
  maps: typeof naver.maps,
  mapInstance: naver.maps.Map,
  marker: NaverMapComplexMarker,
  onSelect: () => void,
): CreatedMarker {
  const button = document.createElement('button')
  button.type = 'button'
  button.className = marker.selected
    ? 'housing-map-marker is-selected'
    : 'housing-map-marker'
  button.setAttribute('aria-label', `${marker.name} 단지 상세 보기`)
  button.dataset.complexId = marker.id
  if (marker.regionCode !== undefined) {
    button.dataset.regionCode = marker.regionCode
  }
  button.title = marker.name
  button.textContent = marker.selected ? '●' : '•'
  button.addEventListener('click', onSelect)

  const overlay = new maps.Marker({
    clickable: true,
    cursor: 'pointer',
    icon: {
      anchor: new maps.Point(20, 40),
      content: button,
      size: new maps.Size(40, 40),
    },
    map: mapInstance,
    position: new maps.LatLng(marker.latitude, marker.longitude),
    title: marker.name,
  })
  return { button, overlay }
}

function createRegionClusterMarker(
  maps: typeof naver.maps,
  mapInstance: naver.maps.Map,
  marker: NaverMapRegionClusterMarker,
  onSelect: () => void,
): CreatedMarker {
  const button = document.createElement('button')
  const regionName = document.createElement('span')
  const count = document.createElement('strong')
  const title = `${marker.regionName}, 공공임대 단지 ${marker.uniqueComplexCount}곳`
  button.type = 'button'
  button.className = 'housing-map-cluster'
  button.dataset.regionCode = marker.regionCode
  button.setAttribute('aria-label', `${title}, 개별 단지 보기`)
  button.title = title
  regionName.textContent = marker.regionName
  count.textContent = `${marker.uniqueComplexCount}곳`
  button.append(regionName, count)
  button.addEventListener('click', onSelect)

  const overlay = new maps.Marker({
    clickable: true,
    cursor: 'pointer',
    icon: {
      anchor: new maps.Point(38, 31),
      content: button,
      size: new maps.Size(76, 62),
    },
    map: mapInstance,
    position: new maps.LatLng(marker.latitude, marker.longitude),
    title,
  })
  return { button, overlay }
}

function clearMarkers(markers: naver.maps.Marker[]) {
  markers.forEach((marker) => marker.setMap(null))
}
