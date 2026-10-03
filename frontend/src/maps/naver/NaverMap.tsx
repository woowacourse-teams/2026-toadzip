import { useEffect, useRef, useState } from 'react'
import './markerClusterPicker.css'
import { IconButton } from '../../design-system/components/IconButton.tsx'
import { createComplexMarkerButton, markerSummary, markerWidth } from './complexMarkerButton.ts'
import { clusterComplexMarkers, type ComplexMarkerCluster } from './markerClustering.ts'
import type { ViewportSnapshot } from '../../public-housing/map/viewportPolicy.ts'
import type { MapBounds } from '../../public-housing/model/publicHousing.ts'
import { DEFAULT_MINIMUM_MAP_ZOOM, DEFAULT_MAXIMUM_MAP_ZOOM } from '../../public-housing/navigation/mapLocation.ts'
import { createRegionBoundaryOverlay } from './regionBoundaryOverlay.ts'
import type { RegionBoundary } from '../../public-housing/regions/regionBoundary.ts'
import type {
  MapMarkerPresentation,
} from '../../public-housing/presentation/mapMarkerPresentation.ts'
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

export interface NaverMapViewportChangeMetadata {
  readonly cause: 'initial' | 'user' | 'programmatic' | 'resize'
}

export interface NaverMapScreenPadding {
  readonly top: number
  readonly right: number
  readonly bottom: number
  readonly left: number
}

export interface NaverMapCameraTarget {
  readonly latitude: number
  readonly longitude: number
  /** Fit the complete region instead of applying center, zoom, or screenOffset. */
  readonly bounds?: MapBounds
  readonly boundsPadding?: {
    readonly top: number
    readonly right: number
    readonly bottom: number
    readonly left: number
  }
  /** Limit fitBounds zoom for a small number of results. */
  readonly maxZoom?: number
  /** Reveal the marker only when its card is clipped or covered by these panels. */
  readonly revealPadding?: NaverMapScreenPadding
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
  onViewportChange?: (viewport: ViewportSnapshot, metadata?: NaverMapViewportChangeMetadata) => void
  regionBoundary?: RegionBoundary | null
  visiblePadding?: NaverMapScreenPadding
  transitioning?: boolean
}

interface NaverMapAggregateProps extends NaverMapCommonProps {
  aggregateMarkers: readonly NaverMapAggregateMarker[]
  markers?: never
  onAggregateMarkerSelect: (marker: NaverMapAggregateMarker) => void
  representation: 'AGGREGATE'
}

interface NaverMapIndividualProps extends NaverMapCommonProps {
  aggregateMarkers?: never
  markers: readonly NaverMapMarker[]
  onAggregateMarkerSelect?: never
  representation: 'INDIVIDUAL'
}

export type NaverMapProps =
  | NaverMapAggregateProps
  | NaverMapIndividualProps

interface MarkerFocusTarget {
  readonly kind: RenderedMarker['kind']
  readonly id: string
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
  markers = [],
  onAggregateMarkerSelect,
  onMarkerHighlight,
  onMarkerSelect,
  onTransitionInterrupt,
  onViewportChange,
  regionBoundary,
  representation,
  transitioning = false,
  visiblePadding,
}: NaverMapProps) {
  const mapContainerRef = useRef<HTMLDivElement>(null)
  const mapInstanceRef = useRef<naver.maps.Map | null>(null)
  const mapsRef = useRef<typeof naver.maps | null>(null)
  const cameraTargetRef = useRef(cameraTarget)
  cameraTargetRef.current = cameraTarget
  const cameraRequestIdRef = useRef(cameraRequestId)
  cameraRequestIdRef.current = cameraRequestId
  const createdMarkersRef = useRef<CreatedMarker[]>([])
  const boundaryOverlaysRef = useRef<naver.maps.OverlayView[]>([])
  const appliedMarkerDataKeyRef = useRef<string | null>(null)
  const aggregateMarkersRef = useRef(aggregateMarkers)
  aggregateMarkersRef.current = aggregateMarkers
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
  const viewportCauseRef = useRef<NaverMapViewportChangeMetadata['cause']>('initial')
  const programmaticMovingRef = useRef(false)
  const [projectionRevision, setProjectionRevision] = useState(0)
  const visiblePaddingRef = useRef(visiblePadding)
  visiblePaddingRef.current = visiblePadding
  const [expandedCluster, setExpandedCluster] = useState<ComplexMarkerCluster | null>(null)
  const clusterPickerRef = useRef<HTMLDivElement>(null)
  const clusterTriggerRef = useRef<HTMLButtonElement | null>(null)
  const markerFocusTimerRef = useRef<number | undefined>(undefined)
  const appliedCameraTargetRef = useRef<NaverMapCameraTarget | null>(null)
  const appliedCameraRequestIdRef = useRef<number | undefined>(undefined)
  const onAggregateMarkerSelectRef = useRef(onAggregateMarkerSelect)
  const onMarkerHighlightRef = useRef(onMarkerHighlight)
  const onMarkerSelectRef = useRef(onMarkerSelect)
  const onTransitionInterruptRef = useRef(onTransitionInterrupt)
  const onViewportChangeRef = useRef(onViewportChange)
  const [attempt, setAttempt] = useState(0)
  const [initializedAttempt, setInitializedAttempt] = useState<number | null>(null)
  const [status, setStatus] = useState<MapStatus>({ kind: 'loading' })
  const cameraLatitude = cameraTarget?.latitude
  const cameraLongitude = cameraTarget?.longitude
  const cameraOffsetX = cameraTarget?.screenOffset?.x
  const cameraOffsetY = cameraTarget?.screenOffset?.y
  const cameraZoom = cameraTarget?.zoom
  const cameraSouthWestLat = cameraTarget?.bounds?.southWestLat
  const cameraSouthWestLng = cameraTarget?.bounds?.southWestLng
  const cameraNorthEastLat = cameraTarget?.bounds?.northEastLat
  const cameraNorthEastLng = cameraTarget?.bounds?.northEastLng
  const cameraPaddingTop = cameraTarget?.boundsPadding?.top
  const cameraPaddingRight = cameraTarget?.boundsPadding?.right
  const cameraPaddingBottom = cameraTarget?.boundsPadding?.bottom
  const cameraPaddingLeft = cameraTarget?.boundsPadding?.left
  const cameraMaxZoom = cameraTarget?.maxZoom
  const revealPaddingTop = cameraTarget?.revealPadding?.top
  const revealPaddingRight = cameraTarget?.revealPadding?.right
  const revealPaddingBottom = cameraTarget?.revealPadding?.bottom
  const revealPaddingLeft = cameraTarget?.revealPadding?.left
  const markerGeometryKey = createMarkerGeometryKey({
    aggregateMarkers,
    markers,
    representation,
  })

  useEffect(() => {
    setExpandedCluster(null)
  }, [markerGeometryKey])

  useEffect(() => {
    if (expandedCluster) clusterPickerRef.current?.focus({ preventScroll: true })
  }, [expandedCluster])

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
    let boundsListener: naver.maps.MapEventListener | null = null
    let projectionTimer: number | undefined
    let initListener: naver.maps.MapEventListener | null = null
    let mapSurface: HTMLDivElement | null = null
    let interactionController: AbortController | null = null
    viewportCauseRef.current = 'initial'
    setStatus({ kind: 'loading' })

    const removeIdleListener = () => {
      window.clearTimeout(projectionTimer)
      projectionTimer = undefined
      if (boundsListener && mapsRef.current) {
        mapsRef.current.Event.removeListener(boundsListener)
        boundsListener = null
      }
      if (!idleListener || !mapsRef.current) {
        return
      }
      mapsRef.current.Event.removeListener(idleListener)
      idleListener = null
    }

    const removeInitListener = () => {
      if (!initListener || !mapsRef.current) {
        return
      }
      mapsRef.current.Event.removeListener(initListener)
      initListener = null
    }

    const removeTransitionInterruptListeners = () => {
      if (dragStartListener && mapsRef.current) {
        mapsRef.current.Event.removeListener(dragStartListener)
        dragStartListener = null
      }
      interactionController?.abort()
      interactionController = null
      mapSurface = null
    }

    const handleAuthenticationFailure = () => {
      if (cancelled) {
        return
      }

      resizeObserver?.disconnect()
      resizeObserver = null
      removeInitListener()
      removeIdleListener()
      removeTransitionInterruptListeners()
      const failedMap = mapInstance
      mapInstance = null
      mapInstanceRef.current = null
      mapsRef.current = null
      appliedCameraTargetRef.current = null
      window.clearTimeout(markerFocusTimerRef.current)
      markerFocusTimerRef.current = undefined
      clearMarkers(createdMarkersRef.current)
      clearBoundaryOverlays(boundaryOverlaysRef.current)
      createdMarkersRef.current = []
      appliedMarkerDataKeyRef.current = null
      onMarkerHighlightRef.current?.(null)
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
            minZoom: DEFAULT_MINIMUM_MAP_ZOOM,
            maxZoom: DEFAULT_MAXIMUM_MAP_ZOOM,
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
          // Bounds and screen offsets need an initialized map; apply them once afterward.
          appliedCameraRequestIdRef.current = cameraTargetRef.current?.screenOffset
            || cameraTargetRef.current?.bounds
            || cameraTargetRef.current?.revealPadding
            ? undefined
            : cameraRequestIdRef.current

          initListener = maps.Event.once(createdMap, 'init', () => {
            initListener = null
            if (!cancelled && mapInstance === createdMap) {
              setInitializedAttempt(attempt)
            }
          })

          const emitViewport = () => {
            window.clearTimeout(projectionTimer)
            projectionTimer = undefined
            transitionInterruptedRef.current = false
            programmaticMovingRef.current = false
            setProjectionRevision((revision) => revision + 1)
            const viewport = readViewport(createdMap)
            if (viewport) {
              appliedCameraTargetRef.current = {
                latitude: viewport.center.latitude,
                longitude: viewport.center.longitude,
                zoom: viewport.zoom,
              }
              onViewportChangeRef.current?.(viewport, { cause: viewportCauseRef.current })
            }
          }

          idleListener = maps.Event.addListener(
            createdMap,
            'idle',
            emitViewport,
          )
          boundsListener = maps.Event.addListener(createdMap, 'bounds_changed', () => {
            if (projectionTimer !== undefined || representationRef.current !== 'INDIVIDUAL') return
            // Reveal newly visible cards while dragging without notifying the search owner.
            projectionTimer = window.setTimeout(() => {
              projectionTimer = undefined
              if (!cancelled) setProjectionRevision((revision) => revision + 1)
            }, 80)
          })
          const interruptTransition = () => {
            // Keep the cause until the next genuine gesture or camera request. One animation
            // can emit several idle events; clearing a flag on its first idle re-searches lists.
            viewportCauseRef.current = 'user'
            setExpandedCluster(null)
            if (programmaticMovingRef.current) {
              programmaticMovingRef.current = false
              stopMapSafely(createdMap)
            }
            if (transitioningRef.current && !transitionInterruptedRef.current) {
              transitionInterruptedRef.current = true
              onTransitionInterruptRef.current?.()
            }
          }
          dragStartListener = maps.Event.addListener(createdMap, 'dragstart', interruptTransition)
          mapSurface = mapContainerRef.current
          interactionController = new AbortController()
          const interactionOptions = { capture: true, signal: interactionController.signal }
          const isMarkerEvent = (event: Event) => event.target instanceof Element
            && event.target.closest('[data-map-complex-marker], [data-map-aggregate-marker], [data-map-cluster-marker]')
          const onPointerInput = (event: Event) => {
            if (!isMarkerEvent(event)) interruptTransition()
          }
          mapSurface.addEventListener('wheel', interruptTransition, { ...interactionOptions, passive: true })
          mapSurface.addEventListener('pointerdown', onPointerInput, interactionOptions)
          mapSurface.addEventListener('dblclick', onPointerInput, interactionOptions)
          mapSurface.addEventListener('click', onPointerInput, interactionOptions)
          mapSurface.addEventListener('keydown', (event) => {
            if (!isMarkerEvent(event)
              && ['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', '+', '-', '=', 'PageUp', 'PageDown'].includes(event.key)) {
              interruptTransition()
            }
          }, interactionOptions)

          if (typeof ResizeObserver === 'function') {
            let previousSize = { width: mapContainerRef.current.clientWidth, height: mapContainerRef.current.clientHeight }
            resizeObserver = new ResizeObserver(() => {
              const surface = mapContainerRef.current
              if (!surface) return
              const nextSize = { width: surface.clientWidth, height: surface.clientHeight }
              if (previousSize.width === nextSize.width && previousSize.height === nextSize.height) return
              previousSize = nextSize
              viewportCauseRef.current = 'resize'
              programmaticMovingRef.current = false
              createdMap.autoResize()
              setProjectionRevision((revision) => revision + 1)
            })
            resizeObserver.observe(mapContainerRef.current)
          }

          setStatus({ kind: 'ready' })
        } catch {
          resizeObserver?.disconnect()
          resizeObserver = null
          removeInitListener()
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
      removeInitListener()
      removeIdleListener()
      removeTransitionInterruptListeners()
      clearMarkers(createdMarkersRef.current)
      clearBoundaryOverlays(boundaryOverlaysRef.current)
      createdMarkersRef.current = []
      appliedMarkerDataKeyRef.current = null
      window.clearTimeout(markerFocusTimerRef.current)
      markerFocusTimerRef.current = undefined
      mapInstanceRef.current = null
      mapsRef.current = null
      appliedCameraTargetRef.current = null
      appliedCameraRequestIdRef.current = undefined
      destroyMapSafely(mapInstance)
    }
  }, [attempt])

  useEffect(() => {
    const mapInstance = mapInstanceRef.current
    const maps = mapsRef.current
    if (!mapInstance || !maps || status.kind !== 'ready' || !regionBoundary) {
      return
    }

    const overlays = [createRegionBoundaryOverlay(maps, mapInstance, regionBoundary)]
    boundaryOverlaysRef.current = overlays
    return () => clearBoundaryOverlays(overlays)
  }, [regionBoundary, status.kind])

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

    const renderedMarkers = toRenderedMarkers(
      aggregateMarkersRef.current,
      markersRef.current,
      representationRef.current,
      maps,
      mapInstance,
    )
    const previousMarkers = createdMarkersRef.current
    const animateNewMarkers = appliedMarkerDataKeyRef.current !== markerGeometryKey
    appliedMarkerDataKeyRef.current = markerGeometryKey
    const previousFocus = readMarkerFocus(previousMarkers)
    window.clearTimeout(markerFocusTimerRef.current)
    markerFocusTimerRef.current = undefined
    if (sameRenderedMarkers(previousMarkers, renderedMarkers)) {
      return
    }

    const previousById = new Map(previousMarkers.map((created) => [
      renderedMarkerIdentity(created.rendered), created,
    ]))
    const nextGeometryById = new Map(renderedMarkers.map((marker) => [
      renderedMarkerIdentity(marker), renderedMarkerGeometryKey(marker),
    ]))
    const removedMarkers = previousMarkers.filter(({ rendered }) =>
      nextGeometryById.get(renderedMarkerIdentity(rendered))
        !== renderedMarkerGeometryKey(rendered),
    )
    const removedInteraction = removedMarkers.some(({ isInteracting }) => isInteracting())
    clearMarkers(removedMarkers)
    let enteringMarkerCount = 0
    const createdMarkers = renderedMarkers.map((marker) => {
      const previous = previousById.get(renderedMarkerIdentity(marker))
      if (previous && renderedMarkerGeometryKey(previous.rendered)
        === renderedMarkerGeometryKey(marker)) {
        return previous
      }
      const enterDelay = animateNewMarkers && !previous
        ? Math.min(enteringMarkerCount++ * 10, 40)
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
          const { bounds } = cluster
          if (mapInstance.getZoom() >= 17
            || (bounds.southWestLat === bounds.northEastLat && bounds.southWestLng === bounds.northEastLng)) {
            clusterTriggerRef.current = document.activeElement instanceof HTMLButtonElement
              ? document.activeElement : null
            setExpandedCluster(cluster)
            return
          }
          viewportCauseRef.current = 'programmatic'
          programmaticMovingRef.current = true
          const padding = visiblePaddingRef.current
          mapInstance.fitBounds([
            new maps.LatLng(bounds.southWestLat, bounds.southWestLng),
            new maps.LatLng(bounds.northEastLat, bounds.northEastLng),
          ], {
            top: (padding?.top ?? 0) + 96, right: (padding?.right ?? 0) + 80,
            bottom: (padding?.bottom ?? 0) + 80, left: (padding?.left ?? 0) + 80,
            maxZoom: 17,
          })
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
    markerFocusTimerRef.current = restoreMarkerFocus(createdMarkers, previousFocus)
  }, [markerGeometryKey, projectionRevision, status.kind])

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

    if (cameraRequestId !== undefined
      && cameraRequestId === appliedCameraRequestIdRef.current) {
      return
    }

    if (cameraSouthWestLat !== undefined && cameraSouthWestLng !== undefined
      && cameraNorthEastLat !== undefined && cameraNorthEastLng !== undefined) {
      if (initializedAttempt !== attempt) {
        return
      }
      if (!isValidCameraTarget(cameraSouthWestLat, cameraSouthWestLng, undefined)
        || !isValidCameraTarget(cameraNorthEastLat, cameraNorthEastLng, undefined)
        || cameraSouthWestLat > cameraNorthEastLat
        || cameraSouthWestLng > cameraNorthEastLng) {
        return
      }
      appliedCameraRequestIdRef.current = cameraRequestId
      // A single coordinate still needs non-zero geographic extents for the SDK.
      const latitudePadding = cameraSouthWestLat === cameraNorthEastLat ? 0.00005 : 0
      const longitudePadding = cameraSouthWestLng === cameraNorthEastLng ? 0.00005 : 0
      const bounds = [
        new maps.LatLng(Math.max(-90, cameraSouthWestLat - latitudePadding), Math.max(-180, cameraSouthWestLng - longitudePadding)),
        new maps.LatLng(Math.min(90, cameraNorthEastLat + latitudePadding), Math.min(180, cameraNorthEastLng + longitudePadding)),
      ]
      viewportCauseRef.current = 'programmatic'
      programmaticMovingRef.current = true
      const fitOptions: naver.maps.FitBoundsOptions = {}
      if (cameraPaddingTop !== undefined && cameraPaddingRight !== undefined
        && cameraPaddingBottom !== undefined && cameraPaddingLeft !== undefined) {
        Object.assign(fitOptions, {
          top: cameraPaddingTop, right: cameraPaddingRight,
          bottom: cameraPaddingBottom, left: cameraPaddingLeft,
        })
      }
      if (cameraMaxZoom !== undefined && Number.isFinite(cameraMaxZoom)) {
        fitOptions.maxZoom = cameraMaxZoom
      }
      mapInstance.fitBounds(bounds, fitOptions)
      return
    }

    if (
      cameraLatitude === undefined ||
      cameraLongitude === undefined ||
      !isValidCameraTarget(cameraLatitude, cameraLongitude, cameraZoom)
    ) {
      return
    }

    const revealPadding = revealPaddingTop !== undefined && revealPaddingRight !== undefined
      && revealPaddingBottom !== undefined && revealPaddingLeft !== undefined
      ? { top: revealPaddingTop, right: revealPaddingRight, bottom: revealPaddingBottom, left: revealPaddingLeft }
      : undefined
    if (revealPadding && initializedAttempt !== attempt) return
    const targetMarker = markersRef.current.find((marker) => marker.selected)
    const revealTarget = revealPadding
      ? revealCameraTarget(maps, mapInstance, {
        latitude: cameraLatitude, longitude: cameraLongitude, revealPadding,
      }, targetMarker ? markerWidth(targetMarker) : 112)
      : undefined
    if (revealTarget === null) {
      viewportCauseRef.current = 'programmatic'
      if (programmaticMovingRef.current) stopMapSafely(mapInstance)
      programmaticMovingRef.current = false
      appliedCameraRequestIdRef.current = cameraRequestId
      const viewport = readViewport(mapInstance)
      if (viewport) onViewportChangeRef.current?.(viewport, { cause: 'programmatic' })
      return
    }
    const nextTarget = revealTarget ?? offsetCameraTarget(maps, mapInstance, {
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

    if (cameraRequested || coordinatesChanged || zoomChanged) {
      viewportCauseRef.current = 'programmatic'
      programmaticMovingRef.current = !cameraRequestDidNotMove
    }
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
      onViewportChangeRef.current?.(currentViewport, { cause: 'programmatic' })
    }
  }, [cameraLatitude, cameraLongitude, cameraOffsetX, cameraOffsetY, cameraRequestId, cameraZoom,
    cameraSouthWestLat, cameraSouthWestLng, cameraNorthEastLat, cameraNorthEastLng,
    cameraPaddingTop, cameraPaddingRight, cameraPaddingBottom, cameraPaddingLeft, cameraMaxZoom,
    revealPaddingTop, revealPaddingRight, revealPaddingBottom, revealPaddingLeft,
    initializedAttempt, attempt, status.kind])

  const retry = () => {
    setStatus({ kind: 'loading' })
    setAttempt((currentAttempt) => currentAttempt + 1)
  }

  const closeClusterPicker = () => {
    setExpandedCluster(null)
    clusterTriggerRef.current?.focus({ preventScroll: true })
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
      {expandedCluster && isReady && (
        <div
          className="map-cluster-picker"
          ref={clusterPickerRef}
          role="region"
          aria-label="모여 있는 단지"
          tabIndex={-1}
          style={{ left: `calc(50% + ${((visiblePadding?.left ?? 0) - (visiblePadding?.right ?? 0)) / 2}px)` }}
          onKeyDown={(event) => {
            if (event.key === 'Escape') {
              event.stopPropagation()
              closeClusterPicker()
            }
          }}
        >
          <div className="map-cluster-picker__heading">
            <strong>모여 있는 단지 {expandedCluster.members.length}곳</strong>
            <IconButton label="단지 모음 닫기" onClick={closeClusterPicker}>
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true" focusable="false">
                <path d="m5 5 14 14M19 5 5 19" />
              </svg>
            </IconButton>
          </div>
          <ul>
            {expandedCluster.members.map((marker) => (
              <li key={marker.id}>
                <button type="button" onClick={() => {
                  setExpandedCluster(null)
                  onMarkerSelectRef.current?.(marker.id)
                }}>{marker.name}</button>
              </li>
            ))}
          </ul>
        </div>
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
): Required<Pick<NaverMapCameraTarget, 'latitude' | 'longitude' | 'zoom'>> {
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

function revealCameraTarget(
  maps: typeof naver.maps,
  map: naver.maps.Map,
  target: NaverMapCameraTarget,
  markerWidth: number,
): NaverMapCameraTarget | null {
  const padding = target.revealPadding
  if (!padding || !Object.values(padding).every(Number.isFinite)) return target
  const { width, height } = map.getSize()
  if (width <= 0 || height <= 0) return null
  const projection = map.getProjection()
  const mapCenter = projection.fromCoordToOffset(map.getCenter())
  const point = projection.fromCoordToOffset(new maps.LatLng(target.latitude, target.longitude))
  const x = point.x - mapCenter.x + width / 2
  const y = point.y - mapCenter.y + height / 2
  const availableWidth = Math.max(1, width - padding.left - padding.right)
  const availableHeight = Math.max(1, height - padding.top - padding.bottom)
  const horizontalInset = Math.min(markerWidth / 2 + 12, availableWidth / 2)
  const topInset = Math.min(78, availableHeight / 2)
  const bottomInset = Math.min(12, availableHeight / 2)
  const left = padding.left + horizontalInset
  const right = width - padding.right - horizontalInset
  const top = padding.top + topInset
  const bottom = height - padding.bottom - bottomInset
  if (x >= left && x <= right && y >= top && y <= bottom) return null
  const outsideMap = x < 0 || x > width || y < 0 || y > height
  const destinationX = outsideMap ? (left + right) / 2 : Math.min(right, Math.max(left, x))
  const destinationY = outsideMap ? (top + bottom) / 2 : Math.min(bottom, Math.max(top, y))
  const coordinate = readCoordinateValue(projection.fromOffsetToCoord(new maps.Point(
    mapCenter.x + x - destinationX,
    mapCenter.y + y - destinationY,
  )))
  return coordinate ?? target
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
  readonly kind: 'cluster'
  readonly marker: ComplexMarkerCluster
}

type RenderedMarker = RenderedAggregateMarker | RenderedComplexMarker | RenderedClusterMarker

function toRenderedMarkers(
  aggregateMarkers: readonly NaverMapAggregateMarker[],
  markers: readonly NaverMapMarker[],
  representation: 'AGGREGATE' | 'INDIVIDUAL',
  maps: typeof naver.maps,
  map: naver.maps.Map,
): RenderedMarker[] {
  if (representation === 'AGGREGATE') {
    return uniqueSortedAggregateMarkers(aggregateMarkers).map((marker) => ({
      kind: 'aggregate',
      marker,
    }))
  }
  return clusterComplexMarkers(maps, map, uniqueSortedMarkers(markers))
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

function renderedMarkerId(marker: RenderedMarker) {
  if (marker.kind === 'aggregate') {
    return marker.marker.groupKey
  }
  return marker.marker.id
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
  readonly onClusterSelect: (cluster: ComplexMarkerCluster) => void
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
    const aggregate: NaverMapAggregateMarker = {
      ...marker.marker, groupKey: marker.marker.id, groupLabel: '단지',
      uniqueComplexCount: marker.marker.members.length, expansionZoom: 17, nextStage: 4,
    }
    const created = createAggregateMarker(
      maps, mapInstance, aggregate, () => onClusterSelect(marker.marker), enterDelay,
    )
    delete created.button.dataset.mapAggregateMarker
    created.button.dataset.mapClusterMarker = 'true'
    created.button.setAttribute('aria-label', `단지 ${marker.marker.members.length}곳, 모여 있는 단지 확대해서 보기`)
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
  const button = createComplexMarkerButton(marker)
  const width = markerWidth(marker)
  bindMarkerActivation(button, onSelect, controller.signal)
  const isInteracting = bindMarkerHighlight(button, marker.id, onHighlight, controller.signal)

  const overlay = new maps.Marker({
    clickable: true,
    cursor: 'pointer',
    icon: {
      anchor: new maps.Point(width / 2, 66),
      content: createMarkerContent(button, controller.signal, enterDelay),
      size: new maps.Size(width, 66),
    },
    map: mapInstance,
    position: new maps.LatLng(marker.latitude, marker.longitude),
    title: markerSummary(marker),
  })
  return { button, dispose: () => controller.abort(), isInteracting, overlay }
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

function clearBoundaryOverlays(polygons: naver.maps.OverlayView[]) {
  for (const polygon of polygons.splice(0)) {
    try {
      polygon.setMap(null)
    } catch {
      // 인증 실패 시 NAVER SDK가 도형을 먼저 무효화할 수 있습니다.
    }
  }
}

function clearMarkers(markers: readonly CreatedMarker[]) {
  markers.forEach(({ dispose, overlay }) => {
    dispose()
    overlay.setMap(null)
  })
}

interface MarkerGeometryInput {
  readonly aggregateMarkers: readonly NaverMapAggregateMarker[]
  readonly markers: readonly NaverMapMarker[]
  readonly representation: 'AGGREGATE' | 'INDIVIDUAL'
}

function createMarkerGeometryKey({
  aggregateMarkers,
  markers,
  representation,
}: MarkerGeometryInput) {
  if (representation === 'AGGREGATE') {
    return JSON.stringify([
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
    representation,
    ...uniqueSortedMarkers(markers).map((marker) => [
      marker.id,
      marker.latitude,
      marker.longitude,
      marker.name,
      Boolean(marker.selected),
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
  if (marker.kind === 'aggregate') return false
  if (marker.kind === 'cluster') {
    return marker.marker.members.some((member) => highlightedIds.has(member.id))
  }
  return highlightedIds.has(marker.marker.id)
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
  return 10
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
  if (marker.kind === 'cluster') {
    return JSON.stringify([
      marker.kind, marker.marker.id, marker.marker.latitude, marker.marker.longitude, marker.marker.bounds,
      ...marker.marker.members.map((member) => [member.id, member.name, member.latitude, member.longitude]),
    ])
  }
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

function readMarkerFocus(
  createdMarkers: readonly CreatedMarker[],
): MarkerFocusTarget | null {
  const focused = createdMarkers.find(
    ({ button }) => button === document.activeElement,
  )
  if (!focused) {
    return null
  }
  return {
    kind: focused.rendered.kind,
    id: renderedMarkerId(focused.rendered),
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
  const exact = createdMarkers.find(({ rendered }) =>
    rendered.kind === focus.kind && renderedMarkerId(rendered) === focus.id,
  )
  if (exact || focus.kind !== 'cluster') return exact
  const previousMembers = new Set(focus.id.split(','))
  return createdMarkers.find(({ rendered }) => rendered.kind === 'complex'
    ? previousMembers.has(rendered.marker.id)
    : rendered.kind === 'cluster' && rendered.marker.members.some((member) => previousMembers.has(member.id)))
}
