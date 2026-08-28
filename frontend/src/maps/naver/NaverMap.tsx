import { useEffect, useRef, useState } from 'react'
import type { ViewportSnapshot } from '../../public-housing/map/viewportPolicy.ts'
import {
  clusterScreenMarkers,
  type ClusteredScreenMarkers,
} from './screenMarkerClustering.ts'
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
  id: string
  highlighted?: boolean
  latitude: number
  longitude: number
  name: string
  selected?: boolean
}

export type NaverMapMarker = NaverMapComplexMarker

export interface NaverMapCameraTarget {
  readonly latitude: number
  readonly longitude: number
  readonly zoom?: number
}

export interface NaverMapProps {
  cameraTarget?: NaverMapCameraTarget
  markers?: NaverMapMarker[]
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
  markers = [],
  onMarkerSelect,
  onViewportChange,
}: NaverMapProps) {
  const mapContainerRef = useRef<HTMLDivElement>(null)
  const mapInstanceRef = useRef<naver.maps.Map | null>(null)
  const mapsRef = useRef<typeof naver.maps | null>(null)
  const markerOverlaysRef = useRef<naver.maps.Marker[]>([])
  const appliedCameraTargetRef = useRef<NaverMapCameraTarget | null>(null)
  const onMarkerSelectRef = useRef(onMarkerSelect)
  const onViewportChangeRef = useRef(onViewportChange)
  const [attempt, setAttempt] = useState(0)
  const [markerAnnouncement, setMarkerAnnouncement] = useState('')
  const [projectionRevision, setProjectionRevision] = useState(0)
  const [status, setStatus] = useState<MapStatus>({ kind: 'loading' })
  const cameraLatitude = cameraTarget?.latitude
  const cameraLongitude = cameraTarget?.longitude
  const cameraZoom = cameraTarget?.zoom

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

          const emitViewport = () => {
            setProjectionRevision((current) => current + 1)
            const viewport = readViewport(createdMap)
            if (viewport) {
              onViewportChangeRef.current?.(viewport)
            }
          }

          idleListener = maps.Event.addListener(
            createdMap,
            'idle',
            emitViewport,
          )

          if (typeof ResizeObserver === 'function') {
            resizeObserver = new ResizeObserver(() => {
              createdMap.autoResize()
              setProjectionRevision((current) => current + 1)
            })
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
    const clusteredMarkers = toRenderedMarkers(maps, mapInstance, markers)
    const createdMarkers = clusteredMarkers.map((marker) => createMarker(
      maps,
      mapInstance,
      marker,
      onMarkerSelectRef.current,
      (cluster) => {
        fitClusterBounds(maps, mapInstance, cluster)
        setMarkerAnnouncement(
          `${cluster.members.length}곳 단지 묶음을 확대했습니다.`,
        )
      },
    ))
    markerOverlaysRef.current = createdMarkers.map(({ overlay }) => overlay)

    return () => {
      clearMarkers(markerOverlaysRef.current)
      markerOverlaysRef.current = []
    }
  }, [markers, projectionRevision, status.kind])

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

interface RenderedComplexMarker {
  readonly kind: 'complex'
  readonly marker: NaverMapComplexMarker
}

interface RenderedClusterMarker {
  readonly cluster: ClusteredScreenMarkers
  readonly highlighted: boolean
  readonly kind: 'cluster'
  readonly members: readonly NaverMapComplexMarker[]
}

type RenderedMarker = RenderedComplexMarker | RenderedClusterMarker

function toRenderedMarkers(
  maps: typeof naver.maps,
  mapInstance: naver.maps.Map,
  markers: readonly NaverMapMarker[],
): RenderedMarker[] {
  const uniqueMarkers = uniqueSortedMarkers(markers)
  const selectedMarkers = uniqueMarkers.filter((marker) => marker.selected)
  const candidates = uniqueMarkers.filter((marker) => !marker.selected)
  const markerById = new Map(candidates.map((marker) => [marker.id, marker]))

  try {
    const projection = mapInstance.getProjection()
    const projected = candidates.map((marker) => {
      const point = projection.fromCoordToOffset(
        new maps.LatLng(marker.latitude, marker.longitude),
      )
      return {
        id: marker.id,
        latitude: marker.latitude,
        longitude: marker.longitude,
        x: point.x,
        y: point.y,
      }
    })
    const clustered = clusterScreenMarkers(projected).map((result) => {
      if (result.kind === 'singleton') {
        return {
          kind: 'complex' as const,
          marker: markerById.get(result.marker.id)!,
        }
      }
      const members = result.markers.map((marker) => markerById.get(marker.id)!)
      return {
        cluster: result,
        highlighted: members.some((marker) => marker.highlighted),
        kind: 'cluster' as const,
        members,
      }
    })
    return sortRenderedMarkers([
      ...clustered,
      ...selectedMarkers.map((marker) => ({
        kind: 'complex' as const,
        marker,
      })),
    ])
  } catch {
    return uniqueMarkers.map((marker) => ({ kind: 'complex', marker }))
  }
}

function uniqueSortedMarkers(markers: readonly NaverMapMarker[]) {
  const uniqueMarkers = new Map<string, NaverMapMarker>()
  markers.forEach((marker) => {
    if (!uniqueMarkers.has(marker.id)) {
      uniqueMarkers.set(marker.id, marker)
    }
  })
  return [...uniqueMarkers.values()].sort((left, right) =>
    left.id.localeCompare(right.id),
  )
}

function sortRenderedMarkers(markers: readonly RenderedMarker[]) {
  return [...markers].sort((left, right) =>
    renderedMarkerId(left).localeCompare(renderedMarkerId(right)),
  )
}

function renderedMarkerId(marker: RenderedMarker) {
  if (marker.kind === 'complex') {
    return marker.marker.id
  }
  return marker.cluster.id
}

function createMarker(
  maps: typeof naver.maps,
  mapInstance: naver.maps.Map,
  marker: RenderedMarker,
  onMarkerSelect: ((complexId: string) => void) | undefined,
  onClusterSelect: (cluster: RenderedClusterMarker) => void,
): CreatedMarker {
  if (marker.kind === 'cluster') {
    return createClusterMarker(
      maps,
      mapInstance,
      marker,
      () => onClusterSelect(marker),
    )
  }
  return createComplexMarker(
    maps,
    mapInstance,
    marker.marker,
    () => onMarkerSelect?.(marker.marker.id),
  )
}

function createComplexMarker(
  maps: typeof naver.maps,
  mapInstance: naver.maps.Map,
  marker: NaverMapComplexMarker,
  onSelect: () => void,
): CreatedMarker {
  const button = document.createElement('button')
  button.type = 'button'
  button.className = markerClassName(marker)
  button.setAttribute('aria-label', `${marker.name} 단지 상세 보기`)
  button.dataset.complexId = marker.id
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

function markerClassName(marker: NaverMapComplexMarker) {
  return [
    'housing-map-marker',
    marker.selected ? 'is-selected' : '',
    marker.highlighted ? 'is-highlighted' : '',
  ].filter(Boolean).join(' ')
}

function createClusterMarker(
  maps: typeof naver.maps,
  mapInstance: naver.maps.Map,
  marker: RenderedClusterMarker,
  onSelect: () => void,
): CreatedMarker {
  const button = document.createElement('button')
  const count = document.createElement('strong')
  const complexCount = marker.members.length
  const title = `공공임대 단지 ${complexCount}곳 모여 있음`
  button.type = 'button'
  button.className = marker.highlighted
    ? 'housing-map-cluster is-highlighted'
    : 'housing-map-cluster'
  button.dataset.clusterId = marker.cluster.id
  button.dataset.complexIds = marker.members.map(({ id }) => id).join(',')
  button.setAttribute('aria-label', `${complexCount}곳 단지 묶음, 확대해서 보기`)
  button.title = title
  count.textContent = `${complexCount}곳`
  button.append(count)
  button.addEventListener('click', onSelect)

  const overlay = new maps.Marker({
    clickable: true,
    cursor: 'pointer',
    icon: {
      anchor: new maps.Point(30, 26),
      content: button,
      size: new maps.Size(60, 52),
    },
    map: mapInstance,
    position: new maps.LatLng(
      marker.cluster.latitude,
      marker.cluster.longitude,
    ),
    title,
  })
  return { button, overlay }
}

function fitClusterBounds(
  maps: typeof naver.maps,
  mapInstance: naver.maps.Map,
  cluster: RenderedClusterMarker,
) {
  const coordinates = cluster.members.map((marker) => new maps.LatLng(
    marker.latitude,
    marker.longitude,
  ))
  const maximumZoom = Math.min(
    mapInstance.getZoom() + 2,
    mapInstance.getMaxZoom(),
  )
  mapInstance.fitBounds(coordinates, {
    bottom: 72,
    left: 72,
    maxZoom: maximumZoom,
    right: 72,
    top: 72,
  })
}

function clearMarkers(markers: naver.maps.Marker[]) {
  markers.forEach((marker) => marker.setMap(null))
}
