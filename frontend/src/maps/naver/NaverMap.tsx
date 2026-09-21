import { useEffect, useRef, useState } from 'react'
import type { ViewportSnapshot } from '../../public-housing/map/viewportPolicy.ts'
import type {
  MapMarkerAmount,
  MapMarkerPresentation,
} from '../../public-housing/presentation/mapMarkerPresentation.ts'
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
const CAMERA_COORDINATE_PRECISION = 5
const CAMERA_ZOOM_PRECISION = 2

export interface NaverMapComplexMarker extends MapMarkerPresentation {
  id: string
  highlighted?: boolean
  latitude: number
  longitude: number
  name: string
  selected?: boolean
}

export type NaverMapMarker = NaverMapComplexMarker

export interface NaverMapAggregateMarker {
  readonly expansionZoom: number
  readonly groupKey: string
  readonly groupLabel: string
  readonly latitude: number
  readonly longitude: number
  readonly nextStage: number
  readonly uniqueComplexCount: number
}

export interface NaverMapCameraTarget {
  readonly latitude: number
  readonly longitude: number
  /** Target position relative to the map center, in pixels at the destination zoom. */
  readonly screenOffset?: { readonly x: number; readonly y: number }
  readonly zoom?: number
}

interface NaverMapCommonProps {
  cameraRequestId?: number
  cameraTarget?: NaverMapCameraTarget
  dataBusy?: boolean
  onMarkerHighlight?: (complexId: string | null) => void
  onMarkerSelect?: (complexId: string) => void
  onTransitionInterrupt?: () => void
  onViewportChange?: (viewport: ViewportSnapshot) => void
  transitioning?: boolean
}

interface NaverMapLegacyProps extends NaverMapCommonProps {
  aggregateMarkers?: never
  markerRenderMode?: 'legacy'
  markers?: readonly NaverMapMarker[]
  onAggregateMarkerSelect?: never
  representation?: never
}

interface NaverMapServerAggregateProps extends NaverMapCommonProps {
  aggregateMarkers: readonly NaverMapAggregateMarker[]
  markerRenderMode: 'server'
  markers?: never
  onAggregateMarkerSelect: (marker: NaverMapAggregateMarker) => void
  representation: 'AGGREGATE'
}

interface NaverMapServerIndividualProps extends NaverMapCommonProps {
  aggregateMarkers?: never
  markerRenderMode: 'server'
  markers: readonly NaverMapMarker[]
  onAggregateMarkerSelect?: never
  representation: 'INDIVIDUAL'
}

export type NaverMapProps =
  | NaverMapLegacyProps
  | NaverMapServerAggregateProps
  | NaverMapServerIndividualProps

interface PendingClusterFocus {
  readonly memberIds: readonly string[]
  readonly projectionRevision: number
}

interface MarkerFocusTarget {
  readonly aggregateMarkerId?: string
  readonly memberIds: readonly string[]
  readonly preferredComplexId: string | null
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

function stopMapSafely(mapInstance: naver.maps.Map) {
  try {
    mapInstance.stop()
  } catch {
    // 사용자 입력 시점에 NAVER SDK가 전환을 이미 끝냈을 수 있습니다.
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
  aggregateMarkers = [],
  cameraRequestId,
  cameraTarget,
  dataBusy = false,
  markerRenderMode = 'legacy',
  markers = [],
  onAggregateMarkerSelect,
  onMarkerHighlight,
  onMarkerSelect,
  onTransitionInterrupt,
  onViewportChange,
  representation,
  transitioning = false,
}: NaverMapProps) {
  const mapContainerRef = useRef<HTMLDivElement>(null)
  const mapInstanceRef = useRef<naver.maps.Map | null>(null)
  const mapsRef = useRef<typeof naver.maps | null>(null)
  const cameraTargetRef = useRef(cameraTarget)
  cameraTargetRef.current = cameraTarget
  const cameraRequestIdRef = useRef(cameraRequestId)
  cameraRequestIdRef.current = cameraRequestId
  const createdMarkersRef = useRef<CreatedMarker[]>([])
  const appliedMarkerDataKeyRef = useRef<string | null>(null)
  const aggregateMarkersRef = useRef(aggregateMarkers)
  aggregateMarkersRef.current = aggregateMarkers
  const markerRenderModeRef = useRef(markerRenderMode)
  markerRenderModeRef.current = markerRenderMode
  const markersRef = useRef(markers)
  markersRef.current = markers
  const representationRef = useRef(representation)
  representationRef.current = representation
  const dataBusyRef = useRef(dataBusy)
  dataBusyRef.current = dataBusy
  const transitioningRef = useRef(transitioning)
  const transitionInterruptedRef = useRef(false)
  const wasTransitioning = transitioningRef.current
  transitioningRef.current = transitioning
  if (!transitioning || !wasTransitioning) {
    transitionInterruptedRef.current = false
  }
  const markerFocusTimerRef = useRef<number | undefined>(undefined)
  const appliedCameraTargetRef = useRef<NaverMapCameraTarget | null>(null)
  const appliedCameraRequestIdRef = useRef<number | undefined>(undefined)
  const pendingClusterFocusRef = useRef<PendingClusterFocus | null>(null)
  const onAggregateMarkerSelectRef = useRef(onAggregateMarkerSelect)
  const onMarkerHighlightRef = useRef(onMarkerHighlight)
  const onMarkerSelectRef = useRef(onMarkerSelect)
  const onTransitionInterruptRef = useRef(onTransitionInterrupt)
  const onViewportChangeRef = useRef(onViewportChange)
  const [attempt, setAttempt] = useState(0)
  const [markerAnnouncement, setMarkerAnnouncement] = useState('')
  const [projectionRevision, setProjectionRevision] = useState(0)
  const projectionRevisionRef = useRef(projectionRevision)
  projectionRevisionRef.current = projectionRevision
  const [status, setStatus] = useState<MapStatus>({ kind: 'loading' })
  const cameraLatitude = cameraTarget?.latitude
  const cameraLongitude = cameraTarget?.longitude
  const cameraOffsetX = cameraTarget?.screenOffset?.x
  const cameraOffsetY = cameraTarget?.screenOffset?.y
  const cameraZoom = cameraTarget?.zoom
  const markerGeometryKey = createMarkerGeometryKey({
    aggregateMarkers,
    markerRenderMode,
    markers,
    representation,
  })
  const markerDataKey = createMarkerGeometryKey({
    aggregateMarkers,
    markerRenderMode,
    markers,
    representation,
  }, false)

  useEffect(() => {
    onAggregateMarkerSelectRef.current = onAggregateMarkerSelect
  }, [onAggregateMarkerSelect])

  useEffect(() => {
    onMarkerHighlightRef.current = onMarkerHighlight
  }, [onMarkerHighlight])

  useEffect(() => {
    onMarkerSelectRef.current = onMarkerSelect
  }, [onMarkerSelect])

  useEffect(() => {
    onTransitionInterruptRef.current = onTransitionInterrupt
  }, [onTransitionInterrupt])

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
    let dragStartListener: naver.maps.MapEventListener | null = null
    let idleListener: naver.maps.MapEventListener | null = null
    let mapSurface: HTMLDivElement | null = null
    let wheelListener: (() => void) | null = null
    setStatus({ kind: 'loading' })

    const removeIdleListener = () => {
      if (!idleListener || !mapsRef.current) {
        return
      }
      mapsRef.current.Event.removeListener(idleListener)
      idleListener = null
    }

    const removeTransitionInterruptListeners = () => {
      if (dragStartListener && mapsRef.current) {
        mapsRef.current.Event.removeListener(dragStartListener)
        dragStartListener = null
      }
      if (mapSurface && wheelListener) {
        mapSurface.removeEventListener('wheel', wheelListener)
      }
      mapSurface = null
      wheelListener = null
    }

    const handleAuthenticationFailure = () => {
      if (cancelled) {
        return
      }

      resizeObserver?.disconnect()
      resizeObserver = null
      removeIdleListener()
      removeTransitionInterruptListeners()
      const failedMap = mapInstance
      mapInstance = null
      mapInstanceRef.current = null
      mapsRef.current = null
      appliedCameraTargetRef.current = null
      pendingClusterFocusRef.current = null
      window.clearTimeout(markerFocusTimerRef.current)
      markerFocusTimerRef.current = undefined
      clearMarkers(createdMarkersRef.current)
      createdMarkersRef.current = []
      appliedMarkerDataKeyRef.current = null
      onMarkerHighlightRef.current?.(null)
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
          const initialCamera = initialMapCamera(cameraTargetRef.current)
          const createdMap = new maps.Map(mapContainerRef.current, {
            center: new maps.LatLng(
              initialCamera.latitude,
              initialCamera.longitude,
            ),
            gl: true,
            keyboardShortcuts: true,
            logoControlOptions: {
              position: maps.Position.BOTTOM_LEFT,
            },
            scaleControlOptions: {
              position: maps.Position.BOTTOM_LEFT,
            },
            zoom: initialCamera.zoom,
            zoomControl: true,
            zoomControlOptions: {
              position: maps.Position.RIGHT_BOTTOM,
            },
          })
          mapInstance = createdMap
          mapInstanceRef.current = createdMap
          mapsRef.current = maps
          appliedCameraTargetRef.current = initialCamera
          // Projection is available only after initialization; apply the offset once afterward.
          appliedCameraRequestIdRef.current = cameraTargetRef.current?.screenOffset
            ? undefined
            : cameraRequestIdRef.current

          const emitViewport = () => {
            transitionInterruptedRef.current = false
            setProjectionRevision((current) => current + 1)
            const viewport = readViewport(createdMap)
            if (viewport) {
              appliedCameraTargetRef.current = {
                latitude: viewport.center.latitude,
                longitude: viewport.center.longitude,
                zoom: viewport.zoom,
              }
              onViewportChangeRef.current?.(viewport)
            }
          }

          idleListener = maps.Event.addListener(
            createdMap,
            'idle',
            emitViewport,
          )
          const interruptTransition = () => {
            if (
              !transitioningRef.current ||
              transitionInterruptedRef.current
            ) {
              return
            }
            transitionInterruptedRef.current = true
            onTransitionInterruptRef.current?.()
            stopMapSafely(createdMap)
          }
          dragStartListener = maps.Event.addListener(
            createdMap,
            'dragstart',
            interruptTransition,
          )
          mapSurface = mapContainerRef.current
          wheelListener = interruptTransition
          mapSurface.addEventListener('wheel', wheelListener, { passive: true })

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
          removeTransitionInterruptListeners()
          const failedMap = mapInstance
          mapInstance = null
          mapInstanceRef.current = null
          mapsRef.current = null
          appliedCameraTargetRef.current = null
          appliedCameraRequestIdRef.current = undefined
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
      removeTransitionInterruptListeners()
      clearMarkers(createdMarkersRef.current)
      createdMarkersRef.current = []
      appliedMarkerDataKeyRef.current = null
      window.clearTimeout(markerFocusTimerRef.current)
      markerFocusTimerRef.current = undefined
      mapInstanceRef.current = null
      mapsRef.current = null
      appliedCameraTargetRef.current = null
      appliedCameraRequestIdRef.current = undefined
      pendingClusterFocusRef.current = null
      destroyMapSafely(mapInstance)
    }
  }, [attempt])

  useEffect(() => {
    const mapInstance = mapInstanceRef.current
    const maps = mapsRef.current

    if (!mapInstance || !maps || status.kind !== 'ready') {
      if (createdMarkersRef.current.some(({ isInteracting }) =>
        isInteracting())) {
        onMarkerHighlightRef.current?.(null)
      }
      clearMarkers(createdMarkersRef.current)
      createdMarkersRef.current = []
      appliedMarkerDataKeyRef.current = null
      return
    }

    const clusteredMarkers = toRenderedMarkers(
      maps,
      mapInstance,
      aggregateMarkersRef.current,
      markerRenderModeRef.current,
      markersRef.current,
      representationRef.current,
    )
    const previousMarkers = createdMarkersRef.current
    const animateNewMarkers = appliedMarkerDataKeyRef.current !== markerDataKey
    appliedMarkerDataKeyRef.current = markerDataKey
    const previousFocus = readMarkerFocus(previousMarkers)
    window.clearTimeout(markerFocusTimerRef.current)
    markerFocusTimerRef.current = undefined
    if (sameRenderedMarkers(previousMarkers, clusteredMarkers)) {
      markerFocusTimerRef.current = restoreClusterFocus(
        previousMarkers,
        pendingClusterFocusRef.current,
        projectionRevision,
        completeClusterFocusRestore,
      )
      return
    }

    const previousById = new Map(previousMarkers.map((created) => [
      renderedMarkerIdentity(created.rendered), created,
    ]))
    const nextGeometryById = new Map(clusteredMarkers.map((marker) => [
      renderedMarkerIdentity(marker), renderedMarkerGeometryKey(marker),
    ]))
    const removedMarkers = previousMarkers.filter(({ rendered }) =>
      nextGeometryById.get(renderedMarkerIdentity(rendered))
        !== renderedMarkerGeometryKey(rendered),
    )
    const removedInteraction = removedMarkers.some(({ isInteracting }) => isInteracting())
    clearMarkers(removedMarkers)
    let enteringMarkerCount = 0
    const createdMarkers = clusteredMarkers.map((marker) => {
      const previous = previousById.get(renderedMarkerIdentity(marker))
      if (previous && renderedMarkerGeometryKey(previous.rendered)
        === renderedMarkerGeometryKey(marker)) {
        return previous
      }
      const enterDelay = animateNewMarkers && !previous
        ? Math.min(enteringMarkerCount++ * 25, 150)
        : undefined
      return createMarker({
        enterDelay,
        mapInstance,
        maps,
        marker,
        onAggregateMarkerSelect: (aggregateMarker) => {
          if (dataBusyRef.current || transitioningRef.current) {
            return
          }
          onAggregateMarkerSelectRef.current?.(aggregateMarker)
        },
        onClusterSelect: (cluster) => {
          pendingClusterFocusRef.current = {
            memberIds: cluster.members.map(({ id }) => id),
            projectionRevision: projectionRevisionRef.current,
          }
          setMarkerAnnouncement('')
          fitClusterBounds(maps, mapInstance, cluster)
        },
        onMarkerHighlight: (complexId) => {
          onMarkerHighlightRef.current?.(complexId)
        },
        onMarkerSelect: (complexId) => {
          onMarkerSelectRef.current?.(complexId)
        },
      })
    })
    if (removedInteraction) {
      const activeMarker = createdMarkers.find(({ button }) => button === document.activeElement)
        ?? createdMarkers.find(({ isInteracting }) => isInteracting())
      onMarkerHighlightRef.current?.(activeMarker?.rendered.kind === 'complex'
        ? activeMarker.rendered.marker.id
        : null)
    }
    updateAggregateMarkerAvailability(
      createdMarkers,
      dataBusyRef.current || transitioningRef.current,
    )
    applyMarkerPresentation(createdMarkers, markersRef.current)
    createdMarkersRef.current = createdMarkers
    const clusterFocusTimer = restoreClusterFocus(
      createdMarkers,
      pendingClusterFocusRef.current,
      projectionRevision,
      completeClusterFocusRestore,
    )
    markerFocusTimerRef.current = clusterFocusTimer
      ?? restoreMarkerFocus(createdMarkers, previousFocus)

    function completeClusterFocusRestore(message: string) {
      pendingClusterFocusRef.current = null
      setMarkerAnnouncement(message)
    }
  }, [markerDataKey, markerGeometryKey, projectionRevision, status.kind])

  useEffect(() => {
    applyMarkerPresentation(createdMarkersRef.current, markers)
  }, [markers])

  useEffect(() => {
    updateAggregateMarkerAvailability(
      createdMarkersRef.current,
      dataBusy || transitioning,
    )
  }, [dataBusy, markerGeometryKey, status.kind, transitioning])

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
      return
    }

    if (cameraRequestId !== undefined
      && cameraRequestId === appliedCameraRequestIdRef.current) {
      return
    }

    const nextTarget = offsetCameraTarget(maps, mapInstance, {
      latitude: cameraLatitude,
      longitude: cameraLongitude,
      screenOffset: cameraOffsetX !== undefined && cameraOffsetY !== undefined
        ? { x: cameraOffsetX, y: cameraOffsetY }
        : undefined,
      zoom: cameraZoom,
    })
    const nextCenter = new maps.LatLng(nextTarget.latitude, nextTarget.longitude)
    const previousTarget = appliedCameraTargetRef.current
    const cameraRequested = cameraRequestId !== undefined
      && cameraRequestId !== appliedCameraRequestIdRef.current
    const coordinatesChanged = cameraCoordinatesChanged(previousTarget, nextTarget)
    const zoomChanged = cameraZoom !== undefined
      && cameraZoomChanged(previousTarget?.zoom, cameraZoom)
    const currentViewport = cameraRequested ? readViewport(mapInstance) : null
    const cameraRequestDidNotMove = currentViewport !== null
      && !cameraCoordinatesChanged(currentViewport.center, nextTarget)
      && (cameraZoom === undefined
        || !cameraZoomChanged(currentViewport.zoom, cameraZoom))

    if (cameraRequested && cameraZoom !== undefined) {
      mapInstance.morph(
        nextCenter,
        cameraZoom,
      )
    } else if (cameraRequested) {
      mapInstance.panTo(nextCenter)
    } else if (coordinatesChanged && zoomChanged) {
      mapInstance.morph(
        nextCenter,
        cameraZoom,
      )
    } else if (coordinatesChanged) {
      mapInstance.panTo(nextCenter)
    } else if (zoomChanged) {
      mapInstance.setZoom(cameraZoom)
    }

    appliedCameraTargetRef.current = {
      ...nextTarget,
      zoom: cameraZoom ?? previousTarget?.zoom,
    }
    appliedCameraRequestIdRef.current = cameraRequestId
    if (cameraRequestDidNotMove) {
      transitionInterruptedRef.current = false
      onViewportChangeRef.current?.(currentViewport)
    }
  }, [cameraLatitude, cameraLongitude, cameraOffsetX, cameraOffsetY, cameraRequestId, cameraZoom, status.kind])

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
      aria-busy={isLoading || (isReady && dataBusy)}
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

function initialMapCamera(
  cameraTarget: NaverMapCameraTarget | undefined,
): Required<Omit<NaverMapCameraTarget, 'screenOffset'>> {
  if (cameraTarget && isValidCameraTarget(
    cameraTarget.latitude,
    cameraTarget.longitude,
    cameraTarget.zoom,
  )) {
    return {
      latitude: cameraTarget.latitude,
      longitude: cameraTarget.longitude,
      zoom: cameraTarget.zoom ?? 14,
    }
  }
  return { ...INITIAL_CENTER, zoom: 14 }
}

function offsetCameraTarget(
  maps: typeof naver.maps,
  mapInstance: naver.maps.Map,
  target: NaverMapCameraTarget,
): NaverMapCameraTarget {
  const offset = target.screenOffset
  if (!offset || !Number.isFinite(offset.x) || !Number.isFinite(offset.y)) {
    return target
  }
  const projection = mapInstance.getProjection()
  const currentZoom = mapInstance.getZoom()
  const scaleRatio = projection.factor(currentZoom)
    / projection.factor(target.zoom ?? currentZoom)
  if (!Number.isFinite(scaleRatio) || scaleRatio <= 0) {
    return target
  }
  const targetPoint = projection.fromCoordToOffset(
    new maps.LatLng(target.latitude, target.longitude),
  )
  const center = readCoordinateValue(projection.fromOffsetToCoord(new maps.Point(
    targetPoint.x - offset.x * scaleRatio,
    targetPoint.y - offset.y * scaleRatio,
  )))
  if (!center || !isValidCameraTarget(center.latitude, center.longitude, target.zoom)) {
    return target
  }
  return { ...center, zoom: target.zoom }
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
    fixedValueChanged(
      previousTarget.latitude,
      nextTarget.latitude,
      CAMERA_COORDINATE_PRECISION,
    ) ||
    fixedValueChanged(
      previousTarget.longitude,
      nextTarget.longitude,
      CAMERA_COORDINATE_PRECISION,
    )
  )
}

function cameraZoomChanged(
  previousZoom: number | undefined,
  nextZoom: number,
) {
  return previousZoom === undefined || fixedValueChanged(
    previousZoom,
    nextZoom,
    CAMERA_ZOOM_PRECISION,
  )
}

function fixedValueChanged(
  previousValue: number,
  nextValue: number,
  fractionDigits: number,
) {
  return previousValue.toFixed(fractionDigits)
    !== nextValue.toFixed(fractionDigits)
}

function readViewport(mapInstance: naver.maps.Map): ViewportSnapshot | null {
  const bounds = mapInstance.getBounds()
  const southWest = readCoordinate(bounds, 'getSW')
  const northEast = readCoordinate(bounds, 'getNE')
  const center = readCoordinateValue(mapInstance.getCenter())
  const zoom = mapInstance.getZoom()

  if (!southWest || !northEast || !center || !Number.isFinite(zoom)) {
    return null
  }

  return {
    bounds: {
      southWestLat: southWest.latitude,
      southWestLng: southWest.longitude,
      northEastLat: northEast.latitude,
      northEastLng: northEast.longitude,
    },
    center,
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
  return readCoordinateValue(coordinate)
}

function readCoordinateValue(
  coordinate: unknown,
): { latitude: number; longitude: number } | null {
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
  readonly dispose: () => void
  readonly isInteracting: () => boolean
  readonly overlay: naver.maps.Marker
  readonly rendered: RenderedMarker
  presentation: MarkerPresentation | null
}

interface MarkerPresentation {
  readonly selected: boolean
  readonly highlighted: boolean
  readonly zIndex: number
}

function updateAggregateMarkerAvailability(
  markers: readonly CreatedMarker[],
  disabled: boolean,
) {
  markers.forEach(({ button, rendered }) => {
    if (rendered.kind === 'aggregate') {
      button.disabled = disabled
    }
  })
}

interface RenderedComplexMarker {
  readonly kind: 'complex'
  readonly marker: NaverMapComplexMarker
}

interface RenderedAggregateMarker {
  readonly kind: 'aggregate'
  readonly marker: NaverMapAggregateMarker
}

interface RenderedClusterMarker {
  readonly cluster: ClusteredScreenMarkers
  readonly highlighted: boolean
  readonly kind: 'cluster'
  readonly members: readonly NaverMapComplexMarker[]
}

type RenderedMarker =
  | RenderedAggregateMarker
  | RenderedComplexMarker
  | RenderedClusterMarker

function toRenderedMarkers(
  maps: typeof naver.maps,
  mapInstance: naver.maps.Map,
  aggregateMarkers: readonly NaverMapAggregateMarker[],
  markerRenderMode: 'legacy' | 'server',
  markers: readonly NaverMapMarker[],
  representation: 'AGGREGATE' | 'INDIVIDUAL' | undefined,
): RenderedMarker[] {
  if (markerRenderMode === 'server') {
    return toServerRenderedMarkers(
      aggregateMarkers,
      markers,
      representation,
    )
  }
  return toLegacyRenderedMarkers(maps, mapInstance, markers)
}

function toServerRenderedMarkers(
  aggregateMarkers: readonly NaverMapAggregateMarker[],
  markers: readonly NaverMapMarker[],
  representation: 'AGGREGATE' | 'INDIVIDUAL' | undefined,
): RenderedMarker[] {
  if (representation === 'AGGREGATE') {
    return uniqueSortedAggregateMarkers(aggregateMarkers).map((marker) => ({
      kind: 'aggregate',
      marker,
    }))
  }
  return uniqueSortedMarkers(markers).map((marker) => ({
    kind: 'complex',
    marker,
  }))
}

function toLegacyRenderedMarkers(
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

function uniqueSortedAggregateMarkers(
  markers: readonly NaverMapAggregateMarker[],
) {
  const uniqueMarkers = new Map<string, NaverMapAggregateMarker>()
  markers.forEach((marker) => {
    if (!uniqueMarkers.has(marker.groupKey)) {
      uniqueMarkers.set(marker.groupKey, marker)
    }
  })
  return [...uniqueMarkers.values()].sort((left, right) =>
    left.groupKey.localeCompare(right.groupKey),
  )
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
  if (marker.kind === 'aggregate') {
    return marker.marker.groupKey
  }
  if (marker.kind === 'complex') {
    return marker.marker.id
  }
  return marker.cluster.id
}

function renderedMarkerIdentity(marker: RenderedMarker) {
  return JSON.stringify([marker.kind, renderedMarkerId(marker)])
}

interface CreateMarkerOptions {
  readonly enterDelay?: number
  readonly mapInstance: naver.maps.Map
  readonly maps: typeof naver.maps
  readonly marker: RenderedMarker
  readonly onAggregateMarkerSelect: (
    marker: NaverMapAggregateMarker,
  ) => void
  readonly onClusterSelect: (cluster: RenderedClusterMarker) => void
  readonly onMarkerHighlight: ((complexId: string | null) => void) | undefined
  readonly onMarkerSelect: ((complexId: string) => void) | undefined
}

function createMarker({
  enterDelay,
  mapInstance,
  maps,
  marker,
  onAggregateMarkerSelect,
  onClusterSelect,
  onMarkerHighlight,
  onMarkerSelect,
}: CreateMarkerOptions): CreatedMarker {
  if (marker.kind === 'aggregate') {
    const created = createAggregateMarker(
      maps,
      mapInstance,
      marker.marker,
      () => onAggregateMarkerSelect(marker.marker),
      enterDelay,
    )
    return { ...created, rendered: marker, presentation: null }
  }
  if (marker.kind === 'cluster') {
    const created = createClusterMarker(
      maps,
      mapInstance,
      marker,
      () => onClusterSelect(marker),
      enterDelay,
    )
    return { ...created, rendered: marker, presentation: null }
  }
  const created = createComplexMarker(
    maps,
    mapInstance,
    marker.marker,
    () => onMarkerSelect?.(marker.marker.id),
    (complexId) => onMarkerHighlight?.(complexId),
    enterDelay,
  )
  return { ...created, rendered: marker, presentation: null }
}

interface CreatedMarkerOverlay {
  readonly button: HTMLButtonElement
  readonly dispose: () => void
  readonly isInteracting: () => boolean
  readonly overlay: naver.maps.Marker
}

function createMarkerContent(
  button: HTMLButtonElement,
  signal: AbortSignal,
  enterDelay: number | undefined,
) {
  const content = document.createElement('div')
  const motion = document.createElement('div')
  content.className = 'housing-marker-content'
  motion.append(button)
  content.append(motion)
  const reducedMotion = typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches
  if (enterDelay !== undefined && !reducedMotion) {
    motion.className = 'housing-marker-enter'
    motion.style.setProperty('--marker-enter-delay', `${enterDelay}ms`)
    const finishEntry = (event: Event) => {
      if (event.target !== motion) {
        return
      }
      motion.classList.remove('housing-marker-enter')
      motion.style.removeProperty('--marker-enter-delay')
      motion.removeEventListener('animationend', finishEntry)
      motion.removeEventListener('animationcancel', finishEntry)
    }
    motion.addEventListener('animationend', finishEntry, { signal })
    motion.addEventListener('animationcancel', finishEntry, { signal })
  }
  return content
}

function bindMarkerActivation(
  button: HTMLButtonElement,
  onSelect: () => void,
  signal: AbortSignal,
) {
  // Native keyboard clicks have no pointer coordinates for the SDK to process.
  button.addEventListener('click', (event) => {
    event.stopImmediatePropagation()
    onSelect()
  }, { capture: true, signal })
  const keepKeyboardOnMarker = (event: KeyboardEvent) => {
    // Preserve native Enter/Space activation and keep both phases off the map.
    event.stopPropagation()
  }
  button.addEventListener('keydown', keepKeyboardOnMarker, { signal })
  button.addEventListener('keyup', keepKeyboardOnMarker, { signal })
}

function createAggregateMarker(
  maps: typeof naver.maps,
  mapInstance: naver.maps.Map,
  marker: NaverMapAggregateMarker,
  onSelect: () => void,
  enterDelay: number | undefined,
): CreatedMarkerOverlay {
  const controller = new AbortController()
  const button = aggregateMarkerButton(marker)
  bindMarkerActivation(button, onSelect, controller.signal)
  const title = aggregateMarkerTitle(marker)
  const overlay = new maps.Marker({
    clickable: true,
    cursor: 'pointer',
    icon: {
      anchor: new maps.Point(52, 68),
      content: createMarkerContent(button, controller.signal, enterDelay),
      size: new maps.Size(104, 68),
    },
    map: mapInstance,
    position: new maps.LatLng(marker.latitude, marker.longitude),
    title,
  })
  return { button, dispose: () => controller.abort(), isInteracting: () => false, overlay }
}

function aggregateMarkerButton(marker: NaverMapAggregateMarker) {
  const button = document.createElement('button')
  const label = document.createElement('span')
  const count = document.createElement('strong')
  button.type = 'button'
  button.className = 'housing-map-aggregate'
  button.dataset.aggregateMarkerId = marker.groupKey
  button.dataset.expansionZoom = String(marker.expansionZoom)
  button.dataset.groupKey = marker.groupKey
  button.dataset.mapAggregateMarker = 'true'
  button.dataset.nextStage = String(marker.nextStage)
  button.dataset.uniqueComplexCount = String(marker.uniqueComplexCount)
  button.setAttribute('aria-label', aggregateMarkerAriaLabel(marker))
  button.title = aggregateMarkerTitle(marker)
  label.className = 'housing-map-aggregate__label'
  label.textContent = marker.groupLabel
  count.className = 'housing-map-aggregate__count'
  count.textContent = `${marker.uniqueComplexCount}곳`
  button.append(label, count)
  return button
}

function aggregateMarkerAriaLabel(marker: NaverMapAggregateMarker) {
  return `${aggregateMarkerTitle(marker)}, 다음 지역 단계로 확대해서 보기`
}

function aggregateMarkerTitle(marker: NaverMapAggregateMarker) {
  return `${marker.groupLabel} ${marker.uniqueComplexCount}곳`
}

function createComplexMarker(
  maps: typeof naver.maps,
  mapInstance: naver.maps.Map,
  marker: NaverMapComplexMarker,
  onSelect: () => void,
  onHighlight: (complexId: string | null) => void,
  enterDelay: number | undefined,
): CreatedMarkerOverlay {
  const controller = new AbortController()
  const button = document.createElement('button')
  button.type = 'button'
  button.className = markerClassName(marker)
  button.setAttribute('aria-label', markerAriaLabel(marker))
  button.setAttribute('aria-pressed', String(Boolean(marker.selected)))
  button.dataset.complexId = marker.id
  button.dataset.mapComplexMarker = 'true'
  button.title = markerSummary(marker)
  button.append(
    createMarkerTop(marker),
    createMarkerBody(marker),
  )
  bindMarkerActivation(button, onSelect, controller.signal)
  const isInteracting = bindMarkerHighlight(button, marker.id, onHighlight, controller.signal)

  const overlay = new maps.Marker({
    clickable: true,
    cursor: 'pointer',
    icon: {
      anchor: new maps.Point(48, 66),
      content: createMarkerContent(button, controller.signal, enterDelay),
      size: new maps.Size(96, 66),
    },
    map: mapInstance,
    position: new maps.LatLng(marker.latitude, marker.longitude),
    title: markerSummary(marker),
  })
  return { button, dispose: () => controller.abort(), isInteracting, overlay }
}

function markerAriaLabel(marker: NaverMapComplexMarker) {
  return `${markerSummary(marker)}, 단지 상세 보기`
}

function markerSummary(marker: NaverMapComplexMarker) {
  return [
    marker.name,
    `${marker.agencyName} · ${marker.rentalTypeName}`,
    `보증금 ${markerAmountSummary(marker.deposit)}`,
    `월 임대료 ${markerAmountSummary(marker.monthlyRent)}`,
  ].join(', ')
}

function markerAmountSummary(amount: MapMarkerAmount | null) {
  return amount === null ? '정보 없음' : `최소 ${amount.exactLabel}`
}

function createMarkerTop(marker: NaverMapComplexMarker) {
  const top = document.createElement('span')
  top.className = 'housing-map-marker__top'
  top.append(
    createMarkerText('name', marker.agencyLabel),
    createMarkerText('name', marker.rentalTypeLabel),
  )
  return top
}

function createMarkerBody(marker: NaverMapComplexMarker) {
  const body = document.createElement('span')
  body.className = 'housing-map-marker__body'
  body.append(
    createMarkerAmountRow('보', marker.deposit),
    createMarkerAmountRow('월', marker.monthlyRent),
  )
  return body
}

function createMarkerAmountRow(label: string, amount: MapMarkerAmount | null) {
  const row = document.createElement('span')
  row.className = 'housing-map-marker__row'
  row.append(createMarkerText('label', label))
  if (amount === null) {
    row.append(createMarkerText('missing', '정보 없음'))
    return row
  }
  const value = document.createElement('span')
  value.className = 'housing-map-marker__amount'
  value.append(
    createMarkerText('digits', amount.digits),
    createMarkerText('unit', amount.unit),
    createMarkerText('from', '~'),
  )
  row.append(value)
  return row
}

function createMarkerText(className: string, text: string) {
  const node = document.createElement('span')
  node.className = `housing-map-marker__${className}`
  node.textContent = text
  return node
}

function markerClassName(marker: NaverMapComplexMarker) {
  return [
    'housing-map-marker',
    marker.selected ? 'is-selected' : '',
    marker.highlighted ? 'is-highlighted' : '',
  ].filter(Boolean).join(' ')
}

function bindMarkerHighlight(
  button: HTMLButtonElement,
  complexId: string,
  onHighlight: (complexId: string | null) => void,
  signal: AbortSignal,
) {
  let focused = false
  let pointerInside = false
  const updateHighlight = () => {
    onHighlight(focused || pointerInside ? complexId : null)
  }
  button.addEventListener('mouseenter', () => {
    pointerInside = true
    updateHighlight()
  }, { signal })
  button.addEventListener('mouseleave', () => {
    pointerInside = false
    updateHighlight()
  }, { signal })
  button.addEventListener('focus', () => {
    focused = true
    updateHighlight()
  }, { signal })
  button.addEventListener('blur', () => {
    focused = false
    updateHighlight()
  }, { signal })
  return () => focused || pointerInside
}

function createClusterMarker(
  maps: typeof naver.maps,
  mapInstance: naver.maps.Map,
  marker: RenderedClusterMarker,
  onSelect: () => void,
  enterDelay: number | undefined,
): CreatedMarkerOverlay {
  const controller = new AbortController()
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
  button.dataset.mapClusterMarker = 'true'
  button.setAttribute('aria-label', `${complexCount}곳 단지 묶음, 확대해서 보기`)
  button.title = title
  count.textContent = `${complexCount}곳`
  button.append(count)
  bindMarkerActivation(button, onSelect, controller.signal)

  const overlay = new maps.Marker({
    clickable: true,
    cursor: 'pointer',
    icon: {
      anchor: new maps.Point(30, 26),
      content: createMarkerContent(button, controller.signal, enterDelay),
      size: new maps.Size(60, 52),
    },
    map: mapInstance,
    position: new maps.LatLng(
      marker.cluster.latitude,
      marker.cluster.longitude,
    ),
    title,
  })
  return { button, dispose: () => controller.abort(), isInteracting: () => false, overlay }
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

function clearMarkers(markers: readonly CreatedMarker[]) {
  markers.forEach(({ dispose, overlay }) => {
    dispose()
    overlay.setMap(null)
  })
}

interface MarkerGeometryInput {
  readonly aggregateMarkers: readonly NaverMapAggregateMarker[]
  readonly markerRenderMode: 'legacy' | 'server'
  readonly markers: readonly NaverMapMarker[]
  readonly representation: 'AGGREGATE' | 'INDIVIDUAL' | undefined
}

function createMarkerGeometryKey({
  aggregateMarkers,
  markerRenderMode,
  markers,
  representation,
}: MarkerGeometryInput, includeSelection = true) {
  if (representation === 'AGGREGATE') {
    return JSON.stringify([
      markerRenderMode,
      representation,
      ...uniqueSortedAggregateMarkers(aggregateMarkers).map((marker) => [
        marker.groupKey,
        marker.groupLabel,
        marker.latitude,
        marker.longitude,
        marker.uniqueComplexCount,
        marker.nextStage,
        marker.expansionZoom,
      ]),
    ])
  }
  return JSON.stringify([
    markerRenderMode,
    representation,
    ...uniqueSortedMarkers(markers).map((marker) => [
      marker.id,
      marker.latitude,
      marker.longitude,
      marker.name,
      marker.agencyLabel,
      marker.agencyName,
      marker.rentalTypeLabel,
      marker.rentalTypeName,
      marker.deposit?.digits,
      marker.deposit?.unit,
      marker.deposit?.exactLabel,
      marker.monthlyRent?.digits,
      marker.monthlyRent?.unit,
      marker.monthlyRent?.exactLabel,
      // 기존 군집은 선택 단지를 묶음에서 분리하므로 구성 재계산이 필요하다.
      ...(includeSelection && markerRenderMode === 'legacy' ? [Boolean(marker.selected)] : []),
    ]),
  ])
}

function applyMarkerPresentation(
  createdMarkers: readonly CreatedMarker[],
  markers: readonly NaverMapMarker[],
) {
  const selectedIds = new Set(
    markers.filter(({ selected }) => selected).map(({ id }) => id),
  )
  const highlightedIds = new Set(
    markers.filter(({ highlighted }) => highlighted).map(({ id }) => id),
  )
  createdMarkers.forEach((created) => {
    const { button, overlay, rendered, presentation } = created
    const selected = rendered.kind === 'complex'
      && selectedIds.has(rendered.marker.id)
    const highlighted = markerIsHighlighted(rendered, highlightedIds)
    const zIndex = markerZIndex(rendered, selected, highlighted)
    if (presentation?.selected === selected
      && presentation.highlighted === highlighted
      && presentation.zIndex === zIndex) {
      return
    }
    if (presentation?.selected !== selected) {
      button.classList.toggle('is-selected', selected)
      if (rendered.kind === 'complex') {
        button.setAttribute('aria-pressed', String(selected))
      }
    }
    if (presentation?.highlighted !== highlighted) {
      button.classList.toggle('is-highlighted', highlighted)
    }
    if (presentation?.zIndex !== zIndex) {
      overlay.setZIndex(zIndex)
    }
    created.presentation = { selected, highlighted, zIndex }
  })
}

function markerIsHighlighted(
  marker: RenderedMarker,
  highlightedIds: ReadonlySet<string>,
) {
  if (marker.kind === 'aggregate') {
    return false
  }
  if (marker.kind === 'complex') {
    return highlightedIds.has(marker.marker.id)
  }
  return marker.members.some(({ id }) => highlightedIds.has(id))
}

function markerZIndex(marker: RenderedMarker, selected: boolean, highlighted: boolean) {
  if (selected) {
    return 30
  }
  if (highlighted) {
    return 20
  }
  if (marker.kind === 'aggregate') {
    return 0
  }
  return marker.kind === 'complex' ? 10 : 0
}

function sameRenderedMarkers(
  current: readonly CreatedMarker[],
  next: readonly RenderedMarker[],
) {
  if (current.length !== next.length) {
    return false
  }
  return current.every(({ rendered }, index) =>
    renderedMarkerGeometryKey(rendered)
      === renderedMarkerGeometryKey(next[index]),
  )
}

function renderedMarkerGeometryKey(marker: RenderedMarker | undefined) {
  if (!marker) {
    return ''
  }
  if (marker.kind === 'aggregate') {
    return JSON.stringify([
      marker.kind,
      marker.marker.groupKey,
      marker.marker.groupLabel,
      marker.marker.latitude,
      marker.marker.longitude,
      marker.marker.uniqueComplexCount,
      marker.marker.nextStage,
      marker.marker.expansionZoom,
    ])
  }
  if (marker.kind === 'complex') {
    return JSON.stringify([
      marker.kind,
      marker.marker.id,
      marker.marker.latitude,
      marker.marker.longitude,
      marker.marker.name,
      marker.marker.agencyLabel,
      marker.marker.agencyName,
      marker.marker.rentalTypeLabel,
      marker.marker.rentalTypeName,
      marker.marker.deposit?.digits,
      marker.marker.deposit?.unit,
      marker.marker.deposit?.exactLabel,
      marker.marker.monthlyRent?.digits,
      marker.marker.monthlyRent?.unit,
      marker.marker.monthlyRent?.exactLabel,
    ])
  }
  return JSON.stringify([
    marker.kind,
    marker.cluster.id,
    marker.cluster.latitude,
    marker.cluster.longitude,
    marker.members.map(({ id, latitude, longitude }) => [id, latitude, longitude]),
  ])
}

function readMarkerFocus(
  createdMarkers: readonly CreatedMarker[],
): MarkerFocusTarget | null {
  const focused = createdMarkers.find(
    ({ button }) => button === document.activeElement,
  )
  if (!focused) {
    return null
  }
  if (focused.rendered.kind === 'aggregate') {
    return {
      aggregateMarkerId: focused.rendered.marker.groupKey,
      memberIds: [],
      preferredComplexId: null,
    }
  }
  if (focused.rendered.kind === 'complex') {
    return {
      memberIds: [focused.rendered.marker.id],
      preferredComplexId: focused.rendered.marker.id,
    }
  }
  return {
    memberIds: focused.rendered.members.map(({ id }) => id),
    preferredComplexId: null,
  }
}

function restoreMarkerFocus(
  createdMarkers: readonly CreatedMarker[],
  focus: MarkerFocusTarget | null,
) {
  if (!focus) {
    return undefined
  }
  const target = findMarkerFocusTarget(createdMarkers, focus)
  return target && target.button !== document.activeElement
    ? window.setTimeout(() => target.button.focus({ preventScroll: true }))
    : undefined
}

function findMarkerFocusTarget(
  createdMarkers: readonly CreatedMarker[],
  focus: MarkerFocusTarget,
) {
  const aggregate = createdMarkers.find(({ rendered }) =>
    rendered.kind === 'aggregate'
      && rendered.marker.groupKey === focus.aggregateMarkerId,
  )
  if (aggregate) {
    return aggregate
  }
  const memberIds = new Set(focus.memberIds)
  const preferred = createdMarkers.find(({ rendered }) =>
    rendered.kind === 'complex'
      && rendered.marker.id === focus.preferredComplexId,
  )
  const individual = preferred ?? createdMarkers.find(({ rendered }) =>
    rendered.kind === 'complex' && memberIds.has(rendered.marker.id),
  )
  return individual ?? createdMarkers.find(({ rendered }) =>
    rendered.kind === 'cluster'
      && rendered.members.some(({ id }) => memberIds.has(id)),
  )
}

function restoreClusterFocus(
  createdMarkers: readonly CreatedMarker[],
  pending: PendingClusterFocus | null,
  projectionRevision: number,
  onRestore: (message: string) => void,
) {
  if (!pending || projectionRevision <= pending.projectionRevision) {
    return undefined
  }

  const focus = { memberIds: pending.memberIds, preferredComplexId: null }
  const target = findMarkerFocusTarget(createdMarkers, focus)
  if (!target) {
    return undefined
  }
  const message = target.rendered.kind === 'complex'
    ? `${pending.memberIds.length}곳 단지 묶음을 확대해 개별 단지를 표시했습니다.`
    : `${pending.memberIds.length}곳 단지 묶음을 확대했지만 아직 함께 표시됩니다.`

  onRestore(message)
  return window.setTimeout(() => target.button.focus({ preventScroll: true }))
}
