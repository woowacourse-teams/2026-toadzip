import { useEffect, useRef, useState } from 'react'
import './markerClusterPicker.css'
import { IconButton } from '../../design-system/components/IconButton.tsx'
import { markerWidth } from './complexMarkerButton.ts'
import type { ComplexMarkerCluster } from './markerClustering.ts'
import { DEFAULT_MINIMUM_MAP_ZOOM, DEFAULT_MAXIMUM_MAP_ZOOM } from '../../public-housing/navigation/mapLocation.ts'
import {
  cameraCoordinatesChanged,
  cameraZoomChanged,
  initialMapCamera,
  isValidCameraTarget,
  offsetCameraTarget,
  readViewport,
  revealCameraTarget,
} from './mapCamera.ts'
import {
  createMarkerContentKey,
  createMarkerGeometryKey,
  renderedMarkerContentKey,
  renderedMarkerIdentity,
  toRenderedMarkers,
} from './markerData.ts'
import {
  applyMarkerPresentation,
  clearMarkers,
  createMarker,
  readMarkerFocus,
  restoreMarkerFocus,
  sameRenderedMarkers,
  updateAggregateMarkerAvailability,
  type CreatedMarker,
} from './markerOverlays.ts'
import type { NaverMapCameraTarget, NaverMapProps, NaverMapViewportChangeMetadata } from './naverMapTypes.ts'
import { clearBoundaryOverlays, createRegionBoundaryOverlay } from './regionBoundaryOverlay.ts'
import {
  loadNaverMapsSdk,
  NaverMapsSdkError,
  subscribeToNaverMapsAuthenticationFailure,
  type NaverMapsSdkErrorCode,
} from './loadNaverMapsSdk.ts'

export type {
  NaverMapAggregateMarker,
  NaverMapCameraTarget,
  NaverMapComplexMarker,
  NaverMapMarker,
  NaverMapProps,
  NaverMapScreenPadding,
  NaverMapViewportChangeMetadata,
} from './naverMapTypes.ts'

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
  const appliedMarkerContentKeyRef = useRef<string | null>(null)
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
  const markerGeometryKey = createMarkerGeometryKey(aggregateMarkers, markers, representation)
  const markerContentKey = createMarkerContentKey(aggregateMarkers, markers, representation)

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
      appliedMarkerContentKeyRef.current = null
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
            const interruptingTransition = transitioningRef.current && !transitionInterruptedRef.current
            if (interruptingTransition) {
              transitionInterruptedRef.current = true
              onTransitionInterruptRef.current?.()
            }
            if (programmaticMovingRef.current || interruptingTransition) {
              programmaticMovingRef.current = false
              stopMapSafely(createdMap)
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
      appliedMarkerContentKeyRef.current = null
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
      appliedMarkerContentKeyRef.current = null
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
    const animateNewMarkers = appliedMarkerContentKeyRef.current !== markerContentKey
    appliedMarkerContentKeyRef.current = markerContentKey
    const previousFocus = readMarkerFocus(previousMarkers)
    window.clearTimeout(markerFocusTimerRef.current)
    markerFocusTimerRef.current = undefined
    if (sameRenderedMarkers(previousMarkers, renderedMarkers)) {
      return
    }

    const previousById = new Map(previousMarkers.map((created) => [
      renderedMarkerIdentity(created.rendered), created,
    ]))
    const nextContentById = new Map(renderedMarkers.map((marker) => [
      renderedMarkerIdentity(marker), renderedMarkerContentKey(marker),
    ]))
    const removedMarkers = previousMarkers.filter(({ rendered }) =>
      nextContentById.get(renderedMarkerIdentity(rendered))
        !== renderedMarkerContentKey(rendered),
    )
    const removedInteraction = removedMarkers.some(({ isInteracting }) => isInteracting())
    clearMarkers(removedMarkers)
    let enteringMarkerCount = 0
    const createdMarkers = renderedMarkers.map((marker) => {
      const previous = previousById.get(renderedMarkerIdentity(marker))
      if (previous && renderedMarkerContentKey(previous.rendered)
        === renderedMarkerContentKey(marker)) {
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
  }, [markerContentKey, markerGeometryKey, projectionRevision, status.kind])

  useEffect(() => {
    applyMarkerPresentation(createdMarkersRef.current, markers)
  }, [markers])

  useEffect(() => {
    updateAggregateMarkerAvailability(
      createdMarkersRef.current,
      dataBusy || transitioning,
    )
  }, [dataBusy, markerContentKey, status.kind, transitioning])

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
