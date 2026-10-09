import { captureProductEvent, createAnalyticsId } from '../../analytics/productAnalytics'
import { useEffect, useRef, useState } from 'react'
import styles from './NaverMap.module.css'
import {
  cameraCoordinatesChanged,
  cameraZoomChanged,
  initialMapCamera,
  isValidCameraTarget,
  offsetCameraTarget,
  readViewport,
} from './mapCamera.ts'
import {
  createMarkerContentKey,
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
import type { NaverMapCameraTarget, NaverMapProps } from './naverMapTypes.ts'
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
}: NaverMapProps) {
  const interactionId = useRef(createAnalyticsId())
  const zoomSource = useRef<'button' | 'other_user' | 'programmatic' | 'unknown'>('unknown')
  const previousZoom = useRef<number | null>(null)
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
  const cameraBoundsMaxZoom = cameraTarget?.boundsMaxZoom
  const cameraPaddingTop = cameraTarget?.boundsPadding?.top
  const cameraPaddingRight = cameraTarget?.boundsPadding?.right
  const cameraPaddingBottom = cameraTarget?.boundsPadding?.bottom
  const cameraPaddingLeft = cameraTarget?.boundsPadding?.left
  const markerContentKey = createMarkerContentKey(aggregateMarkers, markers, representation)

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
    let dragEndListener: naver.maps.MapEventListener | null = null
    let dragStartListener: naver.maps.MapEventListener | null = null
    let idleListener: naver.maps.MapEventListener | null = null
    let initListener: naver.maps.MapEventListener | null = null
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

    const removeInitListener = () => {
      if (!initListener || !mapsRef.current) {
        return
      }
      mapsRef.current.Event.removeListener(initListener)
      initListener = null
    }

    const removeTransitionInterruptListeners = () => {
      if (dragEndListener && mapsRef.current) { mapsRef.current.Event.removeListener(dragEndListener); dragEndListener = null }
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
            logoControlOptions: {
              position: maps.Position.BOTTOM_RIGHT,
            },
            mapDataControlOptions: {
              position: maps.Position.BOTTOM_RIGHT,
            },
            scaleControlOptions: {
              position: maps.Position.BOTTOM_RIGHT,
            },
            zoom: initialCamera.zoom,
            zoomControl: false,
          })
          mapInstance = createdMap
          mapInstanceRef.current = createdMap
          mapsRef.current = maps
          appliedCameraTargetRef.current = initialCamera
          // Bounds and screen offsets need an initialized map; apply them once afterward.
          appliedCameraRequestIdRef.current = cameraTargetRef.current?.screenOffset
            || cameraTargetRef.current?.bounds
            ? undefined
            : cameraRequestIdRef.current

          initListener = maps.Event.once(createdMap, 'init', () => {
            initListener = null
            if (!cancelled && mapInstance === createdMap) {
              setInitializedAttempt(attempt)
            }
          })

          previousZoom.current = createdMap.getZoom()
          let dragStart: { latitude: number; longitude: number } | null = null
          const emitViewport = () => {
            transitionInterruptedRef.current = false
            const viewport = readViewport(createdMap)
            if (viewport) {
              const old = appliedCameraTargetRef.current
              if (old && (old.latitude !== viewport.center.latitude || old.longitude !== viewport.center.longitude || old.zoom !== viewport.zoom)) interactionId.current = createAnalyticsId()
              if (previousZoom.current !== null && previousZoom.current !== viewport.zoom) captureProductEvent('map_zoomed', { zoom_before: previousZoom.current, zoom_after: viewport.zoom, source: zoomSource.current })
              previousZoom.current = viewport.zoom
              zoomSource.current = 'unknown'
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
            () => { dragStart = readViewport(createdMap)?.center ?? null; interruptTransition() },
          )
          dragEndListener = maps.Event.addListener(createdMap, 'dragend', () => {
            const end = readViewport(createdMap)?.center
            if (dragStart && end && (end.latitude !== dragStart.latitude || end.longitude !== dragStart.longitude)) captureProductEvent('map_panned', { source: 'drag' })
            dragStart = null
          })
          mapSurface = mapContainerRef.current
          wheelListener = () => { zoomSource.current = 'other_user'; interruptTransition() }
          mapSurface.addEventListener('wheel', wheelListener, { passive: true })

          if (typeof ResizeObserver === 'function') {
            resizeObserver = new ResizeObserver(() => {
              createdMap.autoResize()
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
        previewScopeId: () => interactionId.current,
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
  }, [markerContentKey, status.kind])

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
        || cameraSouthWestLat >= cameraNorthEastLat
        || cameraSouthWestLng >= cameraNorthEastLng) {
        return
      }
      appliedCameraRequestIdRef.current = cameraRequestId
      const bounds = [
        new maps.LatLng(cameraSouthWestLat, cameraSouthWestLng),
        new maps.LatLng(cameraNorthEastLat, cameraNorthEastLng),
      ]
      const fitOptions: naver.maps.FitBoundsOptions = {}
      if (cameraPaddingTop !== undefined && cameraPaddingRight !== undefined
        && cameraPaddingBottom !== undefined && cameraPaddingLeft !== undefined) {
        Object.assign(fitOptions, {
          top: cameraPaddingTop,
          right: cameraPaddingRight,
          bottom: cameraPaddingBottom,
          left: cameraPaddingLeft,
        })
      }
      if (cameraBoundsMaxZoom !== undefined && Number.isFinite(cameraBoundsMaxZoom)
        && cameraBoundsMaxZoom >= 0) {
        fitOptions.maxZoom = cameraBoundsMaxZoom
      }
      zoomSource.current = 'programmatic'
      if (Object.keys(fitOptions).length > 0) {
        mapInstance.fitBounds(bounds, fitOptions)
      } else {
        mapInstance.fitBounds(bounds)
      }
      return
    }

    if (
      cameraLatitude === undefined ||
      cameraLongitude === undefined ||
      !isValidCameraTarget(cameraLatitude, cameraLongitude, cameraZoom)
    ) {
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

    if (cameraRequested || coordinatesChanged || zoomChanged) zoomSource.current = 'programmatic'
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
  }, [cameraLatitude, cameraLongitude, cameraOffsetX, cameraOffsetY, cameraRequestId, cameraZoom,
    cameraSouthWestLat, cameraSouthWestLng, cameraNorthEastLat, cameraNorthEastLng,
    cameraBoundsMaxZoom,
    cameraPaddingTop, cameraPaddingRight, cameraPaddingBottom, cameraPaddingLeft,
    initializedAttempt, attempt, status.kind])

  const retry = () => {
    captureProductEvent('exploration_retry_clicked', { surface: 'map_sdk' })
    setStatus({ kind: 'loading' })
    setAttempt((currentAttempt) => currentAttempt + 1)
  }

  const isLoading = status.kind === 'loading'
  const isReady = status.kind === 'ready'

  const changeZoom = (delta: number) => {
    const map = mapInstanceRef.current
    if (!map) return
    if (transitioningRef.current && !transitionInterruptedRef.current) {
      transitionInterruptedRef.current = true
      onTransitionInterruptRef.current?.()
      stopMapSafely(map)
    }
    const before = map.getZoom()
    const zoom = Math.max(map.getMinZoom(), Math.min(map.getMaxZoom(), before + delta))
    captureProductEvent('map_zoom_button_clicked', { direction: delta > 0 ? 'in' : 'out', zoom_before: before, zoom_target: zoom })
    zoomSource.current = 'button'
    map.setZoom(zoom)
  }

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
      {isLoading && <MapLoading />}
      {isReady && (
        <div className={styles.zoomControls} role="group" aria-label="지도 확대·축소">
          <button type="button" aria-label="지도 확대" title="지도 확대" onClick={() => changeZoom(1)}>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
              <path d="M5 12h14M12 5v14" />
            </svg>
          </button>
          <button type="button" aria-label="지도 축소" title="지도 축소" onClick={() => changeZoom(-1)}>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
              <path d="M5 12h14" />
            </svg>
          </button>
        </div>
      )}
      {status.kind === 'unavailable' && (
        <MapUnavailable reason={status.reason} onRetry={retry} />
      )}
    </section>
  )
}
