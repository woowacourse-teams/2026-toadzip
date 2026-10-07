import {
  complexListRequestKey,
  type ComplexResultsState,
  useComplexResults,
} from './complexes/useComplexResults.ts'
import { rentalTypeLabel } from './presentation/rentalTypeLabel.ts'
import {
  clearDetailHistoryState,
  popDetailHistoryState,
  readDetailReturnFocusStack,
  sameDetailLocation,
  toDetailReturnFocusLocation,
  withDetailHistoryState,
  type DetailReturnFocus,
} from './navigation/detailHistory.ts'
import { HousingDetailStatePanel, type DetailStatus } from './components/HousingDetailStatePanel.tsx'
import { toHousingComplexCardData } from './presentation/complexPresentation.ts'
import { MISSING_DATA_LABEL } from './presentation/missingData.ts'
import {
  type KeyboardEvent,
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from 'react'
import { useLocation, useNavigate } from 'react-router'
import { type DetailEntryPoint, trackEvent } from '../analytics/googleAnalytics.ts'
import { trackAppliedFilters, useHousingAnalytics } from '../analytics/useHousingAnalytics.ts'
import NaverMap, {
  type NaverMapAggregateMarker,
  type NaverMapCameraTarget,
  type NaverMapMarker,
  type NaverMapProps,
} from '../maps/naver/NaverMap.tsx'
import { defaultPublicHousingRepository } from './api/defaultPublicHousingRepository.ts'
import type { HousingMapRepository } from './api/housingMapRepository.ts'
import {
  type PublicHousingRegionRepository,
  publicHousingRegionRepository,
} from './api/publicHousingRegionRepository.ts'
import {
  type AnnouncementSearchFilters,
  type ComplexSearchFilters,
  PublicHousingHttpError,
  type PublicHousingRepository,
} from './api/publicHousingRepository.ts'
import {
  type AnnouncementResultsState,
  useAnnouncementResults,
} from './announcements/useAnnouncementResults.ts'
import { HousingAnnouncementDetailPanel } from './components/HousingAnnouncementDetailPanel.tsx'
import { HousingAnnouncementCard } from './components/HousingAnnouncementCard.tsx'
import {
  HousingComplexCard,
} from './components/HousingComplexCard.tsx'
import { HousingComplexDetailPanel } from './components/HousingComplexDetailPanel.tsx'
import { ComplexFilterToolbar } from './filters/ComplexFilterToolbar.tsx'
import { AnnouncementFilterPanel } from './filters/AnnouncementFilterPanel.tsx'
import {
  parseAnnouncementSearchFilters,
  parseComplexSearchFilters,
  searchFiltersSignature,
  setAnnouncementSearchFilters,
  setComplexSearchFilters,
} from './filters/searchFilterLocation.ts'
import {
  createBoundsSignature,
  evaluateServerMapRequest,
  evaluateViewportRequest,
  type ViewportBlockReason,
  type ViewportSnapshot,
} from './map/viewportPolicy.ts'
import { useHousingMapResults } from './map/useHousingMapResults.ts'
import type {
  AnnouncementDetail,
  ComplexDetail,
  MapBounds,
  MapComplex,
} from './model/publicHousing.ts'
import type { HousingMapResult } from './model/housingMap.ts'
import {
  clearDetailQuery,
  parseDetailLocation,
  setAnnouncementIdQuery,
  setComplexIdQuery,
} from './navigation/detailLocation.ts'
import {
  clearMapLocationQuery,
  parseMapLocation,
} from './navigation/mapLocation.ts'
import { toHousingAnnouncementDetailData } from './presentation/announcementDetailPresentation.ts'
import { toHousingComplexDetailData } from './presentation/complexDetailPresentation.ts'
import { toHousingAnnouncementCardData } from './presentation/announcementPresentation.ts'
import {
  presentComplexDetailMarker,
  presentMapComplexMarker,
} from './presentation/mapMarkerPresentation.ts'
import { enrichRecentComplexes, readRecentComplexes, rememberComplex } from './navigation/recentComplexes.ts'
import { IntegratedSearch } from './search/IntegratedSearch.tsx'
import type {
  IntegratedSearchRepository,
  SearchResultItem,
} from './search/integratedSearchRepository.ts'
import { parseRegionBoundaryCode, setRegionBoundaryCode } from './navigation/regionBoundaryLocation.ts'
import { findRegionBoundaryMetadata, findRegionBoundaryName, regionBoundaryRepository } from './regions/regionBoundaryCatalog.ts'
import type { RegionBoundaryRepository } from './regions/regionBoundaryRepository.ts'
import { useRegionBoundary } from './regions/useRegionBoundary.ts'
import { RegionBoundaryControl } from './regions/RegionBoundaryControl.tsx'

const EMPTY_MAP_ITEMS: readonly MapComplex[] = []
const DEFAULT_MAP_LOCATION = {
  center: {
    latitude: 37.5666103,
    longitude: 126.9783882,
  },
  zoom: 14,
}

type ResultTab = 'complexes' | 'announcements'

interface ComplexDetailState {
  readonly complexId: string | null
  readonly detail: ComplexDetail | null
  readonly errorMessage: string | null
  readonly status: DetailStatus
}

interface AnnouncementDetailState {
  readonly announcementId: string | null
  readonly detail: AnnouncementDetail | null
  readonly errorMessage: string | null
  readonly status: DetailStatus
}

interface PendingListFocus {
  readonly announcementId: string | null
  readonly announcementOpener: HTMLElement | null
  readonly complexId: string | null
  readonly complexOpener: HTMLElement | null
  readonly detailKind: ResultTab
  readonly openerWasMarker: boolean
  readonly resultTab: ResultTab
}

export interface PublicHousingExplorerProps {
  boundaryRepository?: RegionBoundaryRepository
  mapRepository: HousingMapRepository
  regionRepository?: PublicHousingRegionRepository
  repository?: PublicHousingRepository
  searchRepository?: IntegratedSearchRepository
}

const INITIAL_COMPLEX_DETAIL: ComplexDetailState = {
  complexId: null,
  detail: null,
  errorMessage: null,
  status: 'closed',
}

const INITIAL_ANNOUNCEMENT_DETAIL: AnnouncementDetailState = {
  announcementId: null,
  detail: null,
  errorMessage: null,
  status: 'closed',
}

export function PublicHousingExplorer({
  boundaryRepository = regionBoundaryRepository,
  mapRepository,
  regionRepository = publicHousingRegionRepository,
  repository = defaultPublicHousingRepository,
  searchRepository,
}: PublicHousingExplorerProps) {
  const location = useLocation()
  const navigate = useNavigate()
  const [mapCameraTarget, setMapCameraTarget] = useState<NaverMapCameraTarget>(
    () => {
      const initialMapLocation = parseMapLocation(
        new URLSearchParams(location.search),
      )
      if (initialMapLocation.kind === 'valid') {
        return {
          latitude: initialMapLocation.center.latitude,
          longitude: initialMapLocation.center.longitude,
          zoom: initialMapLocation.zoom,
        }
      }
      return {
        latitude: DEFAULT_MAP_LOCATION.center.latitude,
        longitude: DEFAULT_MAP_LOCATION.center.longitude,
        zoom: DEFAULT_MAP_LOCATION.zoom,
      }
    },
  )
  const [viewport, setViewport] = useState<ViewportSnapshot | null>(null)
  const [viewportRefreshPending, setViewportRefreshPending] = useState(false)
  const viewportRef = useRef<ViewportSnapshot | null>(null)
  const viewportRevisionRef = useRef(0)
  const viewportTimerRef = useRef<number | null>(null)
  const mapWorkspaceRef = useRef<HTMLElement>(null)
  const initialDetail = parseDetailLocation(new URLSearchParams(location.search))
  const pendingDetailCameraRef = useRef<{ id: string; revision: number } | null>(
    initialDetail.kind === 'complex'
      ? { id: initialDetail.complexId, revision: 0 }
      : null,
  )
  const [recentComplexes, setRecentComplexes] = useState(readRecentComplexes)
  const [recentExpanded, setRecentExpanded] = useState(false)
  const [clusterTransitioning, setClusterTransitioning] = useState(false)
  const [cameraRequestId, setCameraRequestId] = useState(0)
  const [detailCameraRevision, setDetailCameraRevision] = useState(0)
  const [selectedSearchComplex, setSelectedSearchComplex] =
    useState<SearchResultItem | null>(null)
  const [integratedSearchActive, setIntegratedSearchActive] = useState(false)
  const [activeResultTab, setActiveResultTab] =
    useState<ResultTab>('complexes')
  const {
    cancel: cancelServerMapRequest,
    request: requestServerMap,
    retry: retryServerMap,
    state: serverMapState,
  } = useHousingMapResults(mapRepository)
  const clusterTransitionRef = useRef(false)
  const transitionWaitingForIdleRef = useRef(false)
  const previousServerRepresentationRef =
    useRef<'AGGREGATE' | 'INDIVIDUAL' | null>(null)
  const handledServerMapResultRef = useRef<object | null>(null)
  const refreshListAfterMapRef = useRef<string | null>(null)
  const activeResultTabRef = useRef<ResultTab>(activeResultTab)
  activeResultTabRef.current = activeResultTab
  const locationSearchRef = useRef(location.search)
  locationSearchRef.current = location.search
  const [selectedComplexId, setSelectedComplexId] = useState<string | null>(
    null,
  )
  const [cardHighlightedComplexId, setCardHighlightedComplexId] =
    useState<string | null>(null)
  const [markerHighlightedComplexId, setMarkerHighlightedComplexId] =
    useState<string | null>(null)
  const [complexDetail, setComplexDetail] =
    useState<ComplexDetailState>(INITIAL_COMPLEX_DETAIL)
  const [announcementDetail, setAnnouncementDetail] =
    useState<AnnouncementDetailState>(INITIAL_ANNOUNCEMENT_DETAIL)
  const [detailRetryRevision, setDetailRetryRevision] = useState(0)
  const complexResultsScrollRef = useRef<HTMLDivElement | null>(null)
  const announcementResultsScrollRef = useRef<HTMLDivElement | null>(null)
  const previousDetailKindRef = useRef<ResultTab | null>(null)
  const complexDetailOpenerRef = useRef<HTMLElement | null>(null)
  const complexDetailOpenerIdRef = useRef<string | null>(null)
  const complexDetailOpenerWasMarkerRef = useRef(false)
  const announcementDetailOpenerRef = useRef<HTMLElement | null>(null)
  const announcementDetailOpenerIdRef = useRef<string | null>(null)
  const detailReturnFocusStack = useMemo(
    () => readDetailReturnFocusStack(location.state),
    [location.state],
  )
  const previousDetailReturnFocusStackRef = useRef(detailReturnFocusStack)
  const pendingDetailReturnFocusRef = useRef<DetailReturnFocus | null>(null)
  const pendingListFocusRef = useRef<PendingListFocus | null>(null)
  const complexCardRefsRef = useRef(new Map<string, HTMLElement>())
  const announcementCardRefsRef = useRef(new Map<string, HTMLElement>())
  const detailLocationSearch = useMemo(
    () => pickDetailLocationQuery(location.search),
    [location.search],
  )
  const detailLocation = useMemo(
    () => parseDetailLocation(new URLSearchParams(detailLocationSearch)),
    [detailLocationSearch],
  )
  const prepareDetailVisit = useHousingAnalytics({
    detailLocation,
    readyComplexId: complexDetail.status === 'ready'
      && complexDetail.detail?.complexId === complexDetail.complexId
      ? complexDetail.complexId : null,
    readyAnnouncementId: announcementDetail.status === 'ready'
      && announcementDetail.detail?.announcementId === announcementDetail.announcementId
      ? announcementDetail.announcementId : null,
  })
  const mapLocation = useMemo(
    () => parseMapLocation(new URLSearchParams(location.search)),
    [location.search],
  )
  const boundaryRegionCode = parseRegionBoundaryCode(new URLSearchParams(location.search))
  const boundaryMetadata = boundaryRegionCode ? findRegionBoundaryMetadata(boundaryRegionCode) : null
  const [selectedSearchRegion, setSelectedSearchRegion] = useState<SearchResultItem | null>(null)
  const boundarySelectionRef = useRef<SearchResultItem | null>(null)
  const handledBoundaryCodeRef = useRef<string | null>(null)
  const boundaryState = useRegionBoundary(boundaryMetadata?.regionCode ?? null, boundaryRepository)
  const selectedBoundarySearchItem = selectedSearchRegion
    && (selectedSearchRegion.regionCode ?? selectedSearchRegion.id) === boundaryRegionCode
      ? selectedSearchRegion : null

  const focusBoundary = useCallback((code: string) => {
    const metadata = findRegionBoundaryMetadata(code)
    const item = boundarySelectionRef.current
    pendingDetailCameraRef.current = null
    if (metadata) {
      const { bounds } = metadata
      setMapCameraTarget({
        latitude: (bounds.southWestLat + bounds.northEastLat) / 2,
        longitude: (bounds.southWestLng + bounds.northEastLng) / 2,
        bounds,
        boundsPadding: boundaryScreenPadding(mapWorkspaceRef.current),
      })
      setCameraRequestId((current) => current + 1)
    } else if (item && (item.regionCode ?? item.id) === code
      && item.latitude !== null && item.longitude !== null) {
      setMapCameraTarget({ latitude: item.latitude, longitude: item.longitude })
      setCameraRequestId((current) => current + 1)
    }
  }, [])

  useLayoutEffect(() => {
    if (handledBoundaryCodeRef.current === boundaryRegionCode) {
      return
    }
    handledBoundaryCodeRef.current = boundaryRegionCode
    if (boundaryRegionCode !== null) {
      focusBoundary(boundaryRegionCode)
    }
  }, [boundaryRegionCode, focusBoundary])

  const changeBoundarySelection = useCallback((code: string | null) => {
    const query = setRegionBoundaryCode(new URLSearchParams(location.search), code)
    navigate({ pathname: location.pathname, hash: location.hash, search: toSearchString(query) }, { state: location.state })
  }, [location.hash, location.pathname, location.search, location.state, navigate])

  const complexFilters = useMemo(
    () => parseComplexSearchFilters(new URLSearchParams(location.search)),
    [location.search],
  )
  const announcementFilters = useMemo(
    () => parseAnnouncementSearchFilters(new URLSearchParams(location.search)),
    [location.search],
  )
  const complexFiltersKey = useMemo(
    () => searchFiltersSignature(complexFilters),
    [complexFilters],
  )
  const announcementFiltersKey = useMemo(
    () => searchFiltersSignature(announcementFilters),
    [announcementFilters],
  )

  useLayoutEffect(() => {
    if (announcementResultsScrollRef.current) {
      announcementResultsScrollRef.current.scrollTop = 0
    }
  }, [announcementFiltersKey])
  const announcementResults = useAnnouncementResults(
    repository,
    activeResultTab === 'announcements',
    announcementFilters,
    announcementFiltersKey,
  )
  const updateAnnouncementViewCount = announcementResults.updateViewCount

  const resetComplexListPresentation = useCallback(() => {
    setCardHighlightedComplexId(null)
    setMarkerHighlightedComplexId(null)
    if (complexResultsScrollRef.current) {
      complexResultsScrollRef.current.scrollTop = 0
    }
  }, [])
  const {
    cancel: cancelComplexListRequest,
    hasAppliedViewport,
    loadMore,
    request: applyViewport,
    retry: retryComplexResults,
    state: complexResults,
  } = useComplexResults(repository, {
    filters: complexFilters,
    appliedMap: serverMapState.applied,
    paginationPaused: viewportRefreshPending || serverMapState.status === 'loading',
    onViewportApplied: resetComplexListPresentation,
  })

  useEffect(() => {
    setRecentComplexes((current) => enrichRecentComplexes(current, complexResults.items.map((item) => ({
      complexId: item.complexId,
      agencyCode: item.agency?.code ?? null,
      agencyName: item.agency?.name ?? null,
      rentalType: item.rentalType,
    }))))
  }, [complexResults.items])

  useEffect(() => {
    if (mapLocation.kind === 'absent') {
      return
    }
    const nextSearch = clearMapLocationQuery(
      new URLSearchParams(location.search),
    )
    navigate({
      hash: location.hash,
      pathname: location.pathname,
      search: toSearchString(nextSearch),
    }, { replace: true, state: location.state })
  }, [
    location.hash,
    location.pathname,
    location.search,
    location.state,
    mapLocation.kind,
    navigate,
  ])

  useEffect(() => {
    if (detailLocation.kind === 'none') {
      const previousKind = previousDetailKindRef.current
      previousDetailKindRef.current = null
      setComplexDetail(INITIAL_COMPLEX_DETAIL)
      setAnnouncementDetail(INITIAL_ANNOUNCEMENT_DETAIL)
      const complexOpener = complexDetailOpenerRef.current
      const complexId = complexDetailOpenerIdRef.current
      const openerWasMarker = complexDetailOpenerWasMarkerRef.current
      const announcementOpener = announcementDetailOpenerRef.current
      const announcementId = announcementDetailOpenerIdRef.current
      const nextResultTab = activeResultTabRef.current
      setSelectedComplexId(null)
      setSelectedSearchComplex(null)
      setCardHighlightedComplexId(null)
      setMarkerHighlightedComplexId(null)
      pendingDetailReturnFocusRef.current = null
      pendingListFocusRef.current = previousKind === null
        ? null
        : {
            announcementId,
            announcementOpener,
            complexId,
            complexOpener,
            detailKind: previousKind,
            openerWasMarker,
            resultTab: nextResultTab,
          }
      clearDetailOpenerRefs({
        announcementDetailOpenerIdRef,
        announcementDetailOpenerRef,
        complexDetailOpenerIdRef,
        complexDetailOpenerRef,
        complexDetailOpenerWasMarkerRef,
      })
      return
    }

    if (detailLocation.kind === 'invalid') {
      previousDetailKindRef.current = null
      pendingDetailReturnFocusRef.current = null
      pendingListFocusRef.current = null
      setComplexDetail(INITIAL_COMPLEX_DETAIL)
      setAnnouncementDetail(INITIAL_ANNOUNCEMENT_DETAIL)
      clearDetailOpenerRefs({
        announcementDetailOpenerIdRef,
        announcementDetailOpenerRef,
        complexDetailOpenerIdRef,
        complexDetailOpenerRef,
        complexDetailOpenerWasMarkerRef,
      })
      const nextSearch = clearDetailQuery(
        new URLSearchParams(locationSearchRef.current),
      )
      navigate({
        hash: location.hash,
        pathname: location.pathname,
        search: toSearchString(nextSearch),
      }, { replace: true, state: location.state })
      return
    }

    const controller = new AbortController()
    let active = true
    if (detailLocation.kind === 'complex') {
      const complexId = detailLocation.complexId
      previousDetailKindRef.current = 'complexes'
      setSelectedComplexId(complexId)
      setAnnouncementDetail(INITIAL_ANNOUNCEMENT_DETAIL)
      setComplexDetail({
        complexId,
        detail: null,
        errorMessage: null,
        status: 'loading',
      })
      repository
        .findComplexDetail(complexId, controller.signal)
        .then((detail) => {
          if (!active) {
            return
          }
          setRecentComplexes((current) => rememberComplex(current, {
            complexId,
            name: detail.name ?? MISSING_DATA_LABEL,
            address: detail.address?.roadAddress ?? null,
            agencyCode: detail.agency?.code ?? null,
            agencyName: detail.agency?.name ?? null,
            rentalType: detail.rentalType,
          }))
          setComplexDetail({
            complexId,
            detail,
            errorMessage: null,
            status: 'ready',
          })
        })
        .catch((error: unknown) => {
          if (!active || isAbortError(error)) {
            return
          }
          setComplexDetail({
            complexId,
            detail: null,
            errorMessage: detailErrorMessage(error),
            status: isNotFoundError(error) ? 'not-found' : 'error',
          })
        })
    }

    if (detailLocation.kind === 'announcement') {
      const announcementId = detailLocation.announcementId
      previousDetailKindRef.current = 'announcements'
      setComplexDetail(INITIAL_COMPLEX_DETAIL)
      setSelectedComplexId(null)
      setSelectedSearchComplex(null)
      setAnnouncementDetail({
        announcementId,
        detail: null,
        errorMessage: null,
        status: 'loading',
      })
      repository
        .findAnnouncementDetail(announcementId, controller.signal)
        .then((detail) => {
          if (!active) {
            return
          }
          updateAnnouncementViewCount(announcementId, detail.viewCount)
          setAnnouncementDetail({
            announcementId,
            detail,
            errorMessage: null,
            status: 'ready',
          })
        })
        .catch((error: unknown) => {
          if (!active || isAbortError(error)) {
            return
          }
          setAnnouncementDetail({
            announcementId,
            detail: null,
            errorMessage: detailErrorMessage(error),
            status: isNotFoundError(error) ? 'not-found' : 'error',
          })
        })
    }

    return () => {
      active = false
      controller.abort()
    }
  }, [
    detailLocation,
    detailRetryRevision,
    location.hash,
    location.pathname,
    location.state,
    navigate,
    repository,
    updateAnnouncementViewCount,
  ])

  useEffect(() => {
    const pending = pendingListFocusRef.current
    if (detailLocation.kind !== 'none'
      || pending === null
      || pending.resultTab !== activeResultTab) {
      return
    }
    const timeout = window.setTimeout(() => {
      restoreDetailListFocus({
        announcementCards: announcementCardRefsRef.current,
        announcementId: pending.announcementId,
        announcementOpener: pending.announcementOpener,
        complexCards: complexCardRefsRef.current,
        complexId: pending.complexId,
        complexOpener: pending.complexOpener,
        kind: pending.detailKind,
        openerWasMarker: pending.openerWasMarker,
      })
      pendingListFocusRef.current = null
    }, 0)
    return () => window.clearTimeout(timeout)
  }, [activeResultTab, detailLocation])

  useEffect(() => {
    const previousStack = previousDetailReturnFocusStackRef.current
    previousDetailReturnFocusStackRef.current = detailReturnFocusStack
    if (previousStack.length <= detailReturnFocusStack.length) {
      pendingDetailReturnFocusRef.current = null
      return
    }
    const currentDetail = toDetailReturnFocusLocation(detailLocation)
    pendingDetailReturnFocusRef.current = currentDetail === null
      ? null
      : previousStack
        .slice(detailReturnFocusStack.length)
        .reverse()
        .find((entry) => sameDetailLocation(entry, currentDetail)) ?? null
  }, [detailLocation, detailReturnFocusStack])

  useEffect(() => {
    const pending = pendingDetailReturnFocusRef.current
    if (!pending || !isReadyDetailReturnTarget({
      announcementDetail,
      complexDetail,
      location: detailLocation,
      pending,
    })) {
      return
    }
    const timeout = window.setTimeout(() => {
      const target = findDetailReturnFocusTarget(pending.actionKey)
      if (!isAvailableFocusTarget(target)) {
        return
      }
      target.focus({ preventScroll: true })
      pendingDetailReturnFocusRef.current = null
    }, 0)
    return () => window.clearTimeout(timeout)
  }, [announcementDetail, complexDetail, detailLocation])

  const openDetail = useCallback((kind: ResultTab, id: string, entryPoint: DetailEntryPoint) => {
    const currentSearch = new URLSearchParams(location.search)
    const activeElement = document.activeElement
    const opener = activeElement instanceof HTMLElement
      && activeElement !== document.body
      ? activeElement
      : null
    const currentDetail = toDetailReturnFocusLocation(detailLocation)
    const actionKey = opener?.dataset.detailReturnFocus ?? null
    const returnFocus = currentDetail
      && currentDetail.kind !== detailKind(kind)
      && actionKey
      ? { ...currentDetail, actionKey }
      : null
    if (kind === 'complexes' && currentDetail === null) {
      complexDetailOpenerRef.current = opener
      complexDetailOpenerIdRef.current = id
      complexDetailOpenerWasMarkerRef.current = Boolean(
        opener?.classList.contains('housing-map-marker'),
      )
      setSelectedComplexId(id)
    }
    if (kind === 'announcements' && currentDetail === null) {
      announcementDetailOpenerRef.current = opener
      announcementDetailOpenerIdRef.current = id
    }
    const currentKind = detailResultTab(detailLocation)
    const replace = currentKind === kind
    const internalState = replace
      ? location.state
      : withDetailHistoryState(location.state, returnFocus)
    const nextSearch = kind === 'complexes'
      ? setComplexIdQuery(currentSearch, id)
      : setAnnouncementIdQuery(currentSearch, id)
    prepareDetailVisit(kind === 'complexes' ? 'complex' : 'announcement', id, entryPoint)
    navigate({
      hash: location.hash,
      pathname: location.pathname,
      search: toSearchString(nextSearch),
    }, {
      replace,
      state: internalState,
    })
  }, [
    detailLocation,
    location.hash,
    location.pathname,
    location.search,
    location.state,
    navigate,
    prepareDetailVisit,
  ])

  const applyComplexFilters = useCallback((filters: ComplexSearchFilters) => {
    const currentSearch = new URLSearchParams(location.search)
    const nextSearch = setComplexSearchFilters(currentSearch, filters)
    if (nextSearch.toString() === currentSearch.toString()) {
      return
    }
    trackAppliedFilters('complex', currentSearch, nextSearch)
    navigate({
      hash: location.hash,
      pathname: location.pathname,
      search: toSearchString(nextSearch),
    }, { state: location.state })
  }, [
    location.hash,
    location.pathname,
    location.search,
    location.state,
    navigate,
  ])

  const applyAnnouncementFilters = useCallback((
    filters: AnnouncementSearchFilters,
  ) => {
    const currentSearch = new URLSearchParams(location.search)
    const nextSearch = setAnnouncementSearchFilters(currentSearch, filters)
    if (nextSearch.toString() === currentSearch.toString()) {
      return
    }
    trackAppliedFilters('announcement', currentSearch, nextSearch)
    navigate({
      hash: location.hash,
      pathname: location.pathname,
      search: toSearchString(nextSearch),
    }, { state: location.state })
  }, [
    location.hash,
    location.pathname,
    location.search,
    location.state,
    navigate,
  ])

  const openComplexDetail = useCallback((complexId: string, entryPoint: DetailEntryPoint = 'list') => {
    pendingDetailCameraRef.current = {
      id: complexId,
      revision: viewportRevisionRef.current,
    }
    setDetailCameraRevision((current) => current + 1)
    openDetail('complexes', complexId, entryPoint)
  }, [openDetail])

  const openComplexMarker = useCallback((complexId: string) => {
    pendingDetailCameraRef.current = null
    openDetail('complexes', complexId, 'map')
  }, [openDetail])

  const openAnnouncementDetail = useCallback((announcementId: string, entryPoint: DetailEntryPoint = 'list') => {
    pendingDetailCameraRef.current = null
    openDetail('announcements', announcementId, entryPoint)
  }, [openDetail])

  const closeDetail = useCallback(() => {
    pendingDetailCameraRef.current = null
    setSelectedComplexId(null)
    setSelectedSearchComplex(null)
    setCardHighlightedComplexId(null)
    setMarkerHighlightedComplexId(null)

    const nextSearch = clearDetailQuery(
      new URLSearchParams(location.search),
    )
    navigate({
      hash: location.hash,
      pathname: location.pathname,
      search: toSearchString(nextSearch),
    }, { replace: true, state: clearDetailHistoryState(location.state) })
  }, [
    location.hash,
    location.pathname,
    location.search,
    location.state,
    navigate,
  ])

  const returnToDetail = useCallback(() => {
    const previous = detailReturnFocusStack.at(-1)
    if (!previous) {
      return
    }
    pendingDetailCameraRef.current = null
    const query = new URLSearchParams(location.search)
    const nextSearch = previous.kind === 'complex'
      ? setComplexIdQuery(query, previous.id)
      : setAnnouncementIdQuery(query, previous.id)
    prepareDetailVisit(previous.kind, previous.id, 'history')
    navigate({ pathname: location.pathname, hash: location.hash, search: toSearchString(nextSearch) }, {
      replace: true,
      state: popDetailHistoryState(location.state),
    })
  }, [detailReturnFocusStack, location, navigate, prepareDetailVisit])

  useLayoutEffect(() => {
    const pending = pendingDetailCameraRef.current
    if (!pending || pending.id !== complexDetail.complexId) {
      return
    }
    if (pending.revision !== viewportRevisionRef.current) {
      pendingDetailCameraRef.current = null
      return
    }
    const searchItem = selectedSearchComplex?.id === pending.id ? selectedSearchComplex : null
    const target = searchItem?.latitude != null && searchItem.longitude !== null
      ? { latitude: searchItem.latitude, longitude: searchItem.longitude, zoom: 16 }
      : toDetailMapTarget(complexDetail.detail)
    if (!target) {
      return
    }
    pendingDetailCameraRef.current = null
    setMapCameraTarget({
      ...target,
      screenOffset: detailScreenOffset(mapWorkspaceRef.current),
    })
    setCameraRequestId((current) => current + 1)
  }, [complexDetail, detailCameraRevision, selectedSearchComplex])

  const previousComplexFiltersKeyRef = useRef(complexFiltersKey)

  const requestServerMapWithListIntent = useCallback((
    nextViewport: ViewportSnapshot,
    filters: ComplexSearchFilters,
    refreshList: boolean,
  ) => {
    if (refreshList) {
      refreshListAfterMapRef.current = complexListRequestKey(
        nextViewport.bounds,
        filters,
      )
    }
    const outcome = requestServerMap(nextViewport, filters)
    if (!refreshList || outcome === 'started' || outcome === 'pending') {
      return outcome
    }
    refreshListAfterMapRef.current = null
    if (outcome === 'applied'
      && serverMapState.applied?.result.representation === 'AGGREGATE') {
      cancelComplexListRequest()
      return outcome
    }
    applyViewport(nextViewport, false, filters)
    return outcome
  }, [applyViewport, cancelComplexListRequest, requestServerMap, serverMapState.applied])

  useEffect(() => {
    if (previousComplexFiltersKeyRef.current === complexFiltersKey) {
      return
    }
    previousComplexFiltersKeyRef.current = complexFiltersKey
    if (viewportTimerRef.current !== null) {
      window.clearTimeout(viewportTimerRef.current)
      viewportTimerRef.current = null
    }
    setViewportRefreshPending(false)
    cancelComplexListRequest()
    if (viewport === null) {
      return
    }
    refreshListAfterMapRef.current = complexListRequestKey(
      viewport.bounds,
      complexFilters,
    )
    if (clusterTransitionRef.current
      && transitionWaitingForIdleRef.current) {
      return
    }
    requestServerMapWithListIntent(viewport, complexFilters, true)
  }, [
    complexFiltersKey,
    applyViewport,
    cancelComplexListRequest,
    complexFilters,
    requestServerMapWithListIntent,
    viewport,
  ])

  useEffect(() => {
    if (serverMapState.applied === null) {
      return
    }
    if (handledServerMapResultRef.current === serverMapState.applied) {
      return
    }
    handledServerMapResultRef.current = serverMapState.applied
    const representation = serverMapState.applied.result.representation
    const previousRepresentation = previousServerRepresentationRef.current
    previousServerRepresentationRef.current = representation
    const query = serverMapState.applied.query
    const appliedListRefreshKey = complexListRequestKey(
      query.bounds,
      query.filters ?? {},
    )
    const listRefreshRequested = refreshListAfterMapRef.current
      === appliedListRefreshKey
    if (representation === 'AGGREGATE') {
      if (listRefreshRequested) {
        refreshListAfterMapRef.current = null
      }
      cancelComplexListRequest()
      return
    }
    if (listRefreshRequested) {
      refreshListAfterMapRef.current = null
    }
    if (!listRefreshRequested
      && previousRepresentation !== 'AGGREGATE'
      && hasAppliedViewport()) {
      return
    }
    applyViewport(
      viewportForMapQuery(query.bounds, query.zoom),
      false,
      query.filters ?? {},
    )
  }, [applyViewport, cancelComplexListRequest, hasAppliedViewport, serverMapState.applied])

  const finishClusterTransition = useCallback(() => {
    clusterTransitionRef.current = false
    transitionWaitingForIdleRef.current = false
    setClusterTransitioning(false)
  }, [])

  useEffect(() => {
    if (!clusterTransitionRef.current
      || transitionWaitingForIdleRef.current) {
      return
    }
    if (serverMapState.status === 'ready'
      || serverMapState.status === 'error') {
      finishClusterTransition()
    }
  }, [finishClusterTransition, serverMapState])

  const selectAggregateMarker = useCallback((
    marker: NaverMapAggregateMarker,
  ) => {
    if (clusterTransitionRef.current) {
      return
    }
    clusterTransitionRef.current = true
    transitionWaitingForIdleRef.current = true
    setClusterTransitioning(true)
    cancelServerMapRequest()
    setMapCameraTarget({
      latitude: marker.latitude,
      longitude: marker.longitude,
      zoom: marker.expansionZoom,
    })
    setCameraRequestId((current) => current + 1)
  }, [cancelServerMapRequest])

  const interruptClusterTransition = useCallback(() => {
    if (!clusterTransitionRef.current) {
      return
    }
    transitionWaitingForIdleRef.current = true
    cancelServerMapRequest()
  }, [cancelServerMapRequest])

  const handleViewportChange = useCallback((nextViewport: ViewportSnapshot) => {
    const previous = viewportRef.current
    if (previous !== null
      && createBoundsSignature(previous.bounds) === createBoundsSignature(nextViewport.bounds)
      && previous.zoom === nextViewport.zoom
      && !transitionWaitingForIdleRef.current) {
      return
    }
    if (viewportRef.current !== null) {
      viewportRevisionRef.current += 1
    }
    viewportRef.current = nextViewport
    if (clusterTransitionRef.current) {
      transitionWaitingForIdleRef.current = false
    }
    setViewport(nextViewport)
    if (viewportTimerRef.current !== null) {
      window.clearTimeout(viewportTimerRef.current)
    }
    cancelServerMapRequest()
    cancelComplexListRequest()
    setViewportRefreshPending(true)
    viewportTimerRef.current = window.setTimeout(() => {
      viewportTimerRef.current = null
      setViewportRefreshPending(false)
      if (clusterTransitionRef.current) {
        transitionWaitingForIdleRef.current = false
      }
      const outcome = requestServerMapWithListIntent(nextViewport, complexFilters, true)
      if (clusterTransitionRef.current && (outcome === 'applied' || outcome === 'ignored')) {
        finishClusterTransition()
      }
    }, 100)
  }, [cancelComplexListRequest, cancelServerMapRequest, complexFilters,
    finishClusterTransition, requestServerMapWithListIntent])

  const handleIntegratedSearchSelect = useCallback((item: SearchResultItem) => {
    if (item.type === 'COMPLEX') {
      trackEvent('select_search_result', { result_type: 'complex', complex_id: item.id })
    } else if (item.type === 'ANNOUNCEMENT') {
      trackEvent('select_search_result', { result_type: 'announcement', announcement_id: item.id })
    } else if (item.type === 'REGION') {
      trackEvent('select_search_result', { result_type: 'region' })
    } else if (item.type === 'PLACE') {
      trackEvent('select_search_result', { result_type: 'place' })
    }
    if (item.type === 'PLACE' || (item.type === 'REGION' && item.regionCode === null)) {
      if (item.latitude !== null && item.longitude !== null) {
        boundarySelectionRef.current = null
        setSelectedSearchRegion(null)
        changeBoundarySelection(null)
        setMapCameraTarget({ latitude: item.latitude, longitude: item.longitude, zoom: 14 })
        setCameraRequestId((current) => current + 1)
      }
      return
    }
    if (item.type === 'REGION') {
      const code = item.regionCode ?? item.id
      if (!findRegionBoundaryMetadata(code) && (item.latitude === null || item.longitude === null)) {
        return
      }
      boundarySelectionRef.current = item
      setSelectedSearchRegion(item)
      if (code === boundaryRegionCode) {
        focusBoundary(code)
      } else {
        changeBoundarySelection(code)
      }
      return
    }
    if (item.type === 'ANNOUNCEMENT') {
      if (item.latitude !== null && item.longitude !== null) {
        setMapCameraTarget({ latitude: item.latitude, longitude: item.longitude, zoom: 14 })
        setCameraRequestId((current) => current + 1)
      }
      openAnnouncementDetail(item.id, 'search')
      return
    }
    setSelectedSearchComplex(item)
    openComplexDetail(item.id, 'search')
  }, [boundaryRegionCode, changeBoundarySelection, focusBoundary, openAnnouncementDetail, openComplexDetail])

  useEffect(() => {
    return () => {
      if (viewportTimerRef.current !== null) {
        window.clearTimeout(viewportTimerRef.current)
      }
    }
  }, [])

  const serverMapResult = serverMapState.applied?.result ?? null
  const aggregateMapResult = serverMapResult?.representation === 'AGGREGATE'
    ? serverMapResult
    : null
  const aggregateMapEmpty = aggregateMapResult !== null
    && aggregateMapResult.nodes.length === 0
  const mapItems = serverMapResult?.representation === 'INDIVIDUAL'
    ? serverMapResult.nodes
    : EMPTY_MAP_ITEMS
  const aggregateMapActive = aggregateMapResult !== null
  const viewportDecision = viewport
    ? evaluateServerMapRequest(viewport)
    : null
  const requestBlocked = viewportDecision !== null && !viewportDecision.allowed
  const listViewportDecision = viewport
    ? evaluateViewportRequest(viewport)
    : null
  const highlightedComplexIds = useMemo(() => new Set([
    cardHighlightedComplexId,
    markerHighlightedComplexId,
  ].filter((complexId): complexId is string => complexId !== null)), [
    cardHighlightedComplexId,
    markerHighlightedComplexId,
  ])
  const markers = useMemo(
    () => requestBlocked
      ? []
      : toNaverMapMarkers(
          mapItems,
          selectedComplexId,
          highlightedComplexIds,
          complexDetail.detail,
          selectedSearchComplex,
        ),
    [
      complexDetail.detail,
      highlightedComplexIds,
      mapItems,
      requestBlocked,
      selectedComplexId,
      selectedSearchComplex,
    ],
  )
  const resultCount = resultCountLabel(
    activeResultTab,
    complexResults,
    announcementResults.state,
    requestBlocked,
    aggregateMapActive,
    aggregateMapEmpty,
    serverMapState.status === 'loading',
  )
  const complexFilterResultCountLabel = mapFilterResultCountLabel({
    aggregateMapActive,
    requestBlocked,
    serverMapResult,
  })
  const naverMapProps: NaverMapProps = aggregateMapResult !== null
    ? {
        aggregateMarkers: aggregateMapResult.nodes,
        cameraRequestId,
        cameraTarget: mapCameraTarget,
        dataBusy: serverMapState.status === 'loading',
        onAggregateMarkerSelect: selectAggregateMarker,
        onTransitionInterrupt: interruptClusterTransition,
        onViewportChange: handleViewportChange,
        representation: 'AGGREGATE',
        transitioning: clusterTransitioning,
      }
    : {
        cameraRequestId,
        cameraTarget: mapCameraTarget,
        dataBusy: serverMapState.status === 'loading',
        markers,
        onMarkerHighlight: setMarkerHighlightedComplexId,
        onMarkerSelect: openComplexMarker,
        onTransitionInterrupt: interruptClusterTransition,
        onViewportChange: handleViewportChange,
        representation: 'INDIVIDUAL',
        transitioning: clusterTransitioning,
      }
  const hasDetail = complexDetail.status !== 'closed'
    || announcementDetail.status !== 'closed'
  const selectedAnnouncementId = detailLocation.kind === 'announcement'
    ? detailLocation.announcementId
    : null
  const resultSummary = (
    <>
      <span>조회 결과</span>
      <span
        className="housing-results__count"
        aria-label={resultCount.accessibleLabel}
      >
        {resultCount.visibleLabel}
      </span>
    </>
  )
  const listHeader = (
    <div className="housing-results__list-header">
      {resultSummary}
      {recentComplexes.length > 0 && (
        <button type="button" className="housing-recent__toggle"
          aria-label={`최근 본 단지 ${recentComplexes.length}곳`}
          aria-expanded={recentExpanded} aria-controls="recent-complexes"
          onClick={() => {
            if (!recentExpanded && complexResultsScrollRef.current) {
              complexResultsScrollRef.current.scrollTop = 0
            }
            setRecentExpanded((current) => !current)
          }}>
          <span>최근 본 단지 <span className="housing-recent__count">{recentComplexes.length}곳</span></span>
          <svg aria-hidden="true" viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5">
            <path d="m3 4.5 3 3 3-3" />
          </svg>
        </button>
      )}
    </div>
  )

  return (
    <div className={!hasDetail
      ? 'housing-explorer'
      : 'housing-explorer has-detail'}>
      <aside className="housing-results" aria-label="공공임대주택 검색 결과">
        <IntegratedSearch
          onActiveChange={setIntegratedSearchActive}
          onSelect={handleIntegratedSearchSelect}
          repository={searchRepository}
          selectionControl={boundaryRegionCode !== null && (
            <RegionBoundaryControl
              name={findRegionBoundaryName(boundaryRegionCode) ?? selectedBoundarySearchItem?.title ?? `지역 ${boundaryRegionCode}`}
              status={boundaryState.status}
              supported={boundaryMetadata !== null}
              canRecenter={boundaryMetadata !== null || (selectedBoundarySearchItem?.latitude != null && selectedBoundarySearchItem?.longitude != null)}
              onRecenter={() => focusBoundary(boundaryRegionCode)}
              onClear={() => changeBoundarySelection(null)}
              onRetry={boundaryState.retry}
            />
          )}
        />

        <div className="housing-results__browse" hidden={integratedSearchActive}>
          <ResultTabs activeTab={activeResultTab} onSelect={setActiveResultTab} />

          <ViewportAction
            announcementsActive={activeResultTab === 'announcements'}
            decision={listViewportDecision}
          />

          {!requestBlocked && !aggregateMapActive && complexResults.status !== 'error' && (
            <ComplexRequestFeedback state={complexResults} onRetry={retryComplexResults} />
          )}

          <div
            className="housing-results__panel"
            id="complex-results-panel"
            role="tabpanel"
            aria-labelledby="complex-results-tab"
            hidden={activeResultTab !== 'complexes'}
          >
            {activeResultTab === 'complexes' && listHeader}
            <div
              ref={complexResultsScrollRef}
              className="housing-results__scroll"
              aria-busy={complexResults.status === 'loading'}
            >
              {recentComplexes.length > 0 && (
                <section className="housing-recent" aria-label="최근 본 단지" hidden={!recentExpanded}>
                  <ul id="recent-complexes">
                    {recentComplexes.map((recent) => {
                      const listed = complexResults.items.find((item) => item.complexId === recent.complexId)
                      const agencyCode = (recent.agencyCode ?? listed?.agency?.code)?.trim().toUpperCase() || null
                      const agency = agencyCode ?? recent.agencyName ?? listed?.agency?.name ?? MISSING_DATA_LABEL
                      const rental = rentalTypeLabel(recent.rentalType ?? listed?.rentalType ?? null) ?? MISSING_DATA_LABEL
                      return (
                        <li key={recent.complexId}>
                          <button type="button" aria-current={selectedComplexId === recent.complexId ? 'true' : undefined}
                            onClick={() => openComplexDetail(recent.complexId, 'recent')}>
                            <strong className="housing-recent__name">{recent.name}</strong>
                            <span className="housing-recent__meta" aria-label={`공급기관 ${agency}, 임대유형 ${rental}`}>
                              <strong data-agency={agencyCode}>{agency}</strong>
                              {!(agency === MISSING_DATA_LABEL && rental === MISSING_DATA_LABEL) && <>
                                <i aria-hidden="true">·</i>
                                <span>{rental}</span>
                              </>}
                            </span>
                            {recent.address && <span className="housing-recent__address">{recent.address}</span>}
                          </button>
                        </li>
                      )
                    })}
                  </ul>
                </section>
              )}
              {activeResultTab === 'complexes' && (
                <HousingMapRequestFeedback
                  errorMessage={serverMapState.errorMessage}
                  onRetry={retryServerMap}
                  status={serverMapState.status}
                />
              )}
              {!requestBlocked && !aggregateMapActive && complexResults.status === 'error' && (
                <ComplexRequestFeedback state={complexResults} onRetry={retryComplexResults} />
              )}
              {aggregateMapActive && (
                <div className="housing-results__state" role="status">
                  <strong>{aggregateMapEmpty
                    ? '현재 지도 영역에 표시할 지역 마커가 없습니다.'
                    : '지역 마커를 선택해 지도를 확대해 주세요.'}</strong>
                </div>
              )}
              {!requestBlocked && !aggregateMapActive && <ComplexResultContent
                state={complexResults}
                selectedComplexId={selectedComplexId}
                highlightedComplexIds={highlightedComplexIds}
                onSelect={openComplexDetail}
                onOpenAnnouncement={openAnnouncementDetail}
                onHover={setCardHighlightedComplexId}
                onCardRef={(complexId, node) => {
                  setComplexCardRef(complexCardRefsRef.current, complexId, node)
                }}
              />}
            </div>

            {!requestBlocked
              && !aggregateMapActive
              && complexResults.hasNext
              && (complexResults.status === 'ready'
                || complexResults.status === 'loading-more') && (
              <button
                className="housing-results__more"
                type="button"
                onClick={() => loadMore()}
                disabled={complexResults.status === 'loading-more'
                  || viewportRefreshPending
                  || serverMapState.status === 'loading'}
              >
                <span>{complexResults.status === 'loading-more'
                  ? '불러오는 중'
                  : '단지 더 보기'}</span>
                <span className="housing-results__progress"
                  aria-label={`현재 표시 ${complexResults.items.length}곳, 전체 ${complexResults.totalCount ?? '집계 중'}${complexResults.totalCount === null ? '' : '곳'}`}>
                  ({complexResults.items.length.toLocaleString('ko-KR')} | {complexResults.totalCount?.toLocaleString('ko-KR') ?? '—'})
                </span>
                <svg aria-hidden="true" viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5">
                  <path d="m3 4.5 3 3 3-3" />
                </svg>
              </button>
            )}
          </div>

          <div
            className="housing-results__panel"
            id="announcement-results-panel"
            role="tabpanel"
            aria-labelledby="announcement-results-tab"
            hidden={activeResultTab !== 'announcements'}
          >
            <div className="housing-results__body">
              <AnnouncementFilterPanel
                filters={announcementFilters}
                onApply={applyAnnouncementFilters}
                regionRepository={regionRepository}
                resultSummary={activeResultTab === 'announcements' ? resultSummary : null}
              />
              <div
                ref={announcementResultsScrollRef}
                className="housing-results__scroll"
                aria-busy={announcementResults.state.status === 'loading'
                  || announcementResults.state.status === 'loading-more'}
              >
                {!requestBlocked && !aggregateMapActive
                  && activeResultTab === 'announcements'
                  && complexResults.status === 'error' && (
                  <ComplexRequestFeedback state={complexResults} onRetry={retryComplexResults} />
                )}
                {activeResultTab === 'announcements' && (
                  <HousingMapRequestFeedback
                    errorMessage={serverMapState.errorMessage}
                    onRetry={retryServerMap}
                    status={serverMapState.status}
                  />
                )}
                <AnnouncementResultContent
                  state={announcementResults.state}
                  selectedAnnouncementId={selectedAnnouncementId}
                  onSelect={openAnnouncementDetail}
                  onCardRef={(announcementId, node) => {
                    setAnnouncementCardRef(
                      announcementCardRefsRef.current,
                      announcementId,
                      node,
                    )
                  }}
                  onRetry={announcementResults.retry}
                />
              </div>
            </div>
            {announcementResults.state.hasNext && (
              <button
                className="housing-results__more"
                type="button"
                onClick={announcementResults.loadMore}
                disabled={announcementResults.state.status === 'loading-more'}
              >
                {announcementResults.state.status === 'loading-more'
                  ? '불러오는 중'
                  : '공고 더 보기'}
              </button>
            )}
          </div>
        </div>
      </aside>

      <main ref={mapWorkspaceRef} className="housing-map-workspace">
        <div className="housing-map-filter">
          <ComplexFilterToolbar
            filters={complexFilters}
            onApply={applyComplexFilters}
            regionRepository={regionRepository}
            resultCountLabel={complexFilterResultCountLabel}
          />
        </div>
        <NaverMap {...naverMapProps} regionBoundary={boundaryState.boundary} />
        {boundaryState.boundary !== null && (
          <a className="housing-map-credits" href="/map-data-credits.html" target="_blank" rel="noreferrer">저작권</a>
        )}
        <ComplexDetailLayer
          state={complexDetail}
          onClose={closeDetail}
          backTarget={detailReturnFocusStack.at(-1)}
          onBack={returnToDetail}
          onOpenAnnouncement={(id) => openAnnouncementDetail(id, 'detail')}
          onRetry={() => setDetailRetryRevision((current) => current + 1)}
        />
        <AnnouncementDetailLayer
          state={announcementDetail}
          onClose={closeDetail}
          backTarget={detailReturnFocusStack.at(-1)}
          onBack={returnToDetail}
          onOpenComplex={(id) => openComplexDetail(id, 'detail')}
          onRetry={() => setDetailRetryRevision((current) => current + 1)}
        />
      </main>
    </div>
  )
}

interface DetailBackProps {
  readonly backTarget?: DetailReturnFocus
  readonly onBack: () => void
}

function DetailBackButton({ backTarget, onBack }: DetailBackProps) {
  if (!backTarget) {
    return null
  }
  return (
    <button type="button" className="housing-detail-back" onClick={onBack}
      aria-label={`← ${backTarget.kind === 'announcement' ? '공고' : '단지'}로 돌아가기`}>
      <span aria-hidden="true">←</span>
    </button>
  )
}

function ComplexDetailLayer({ state, onClose, onOpenAnnouncement, onRetry, backTarget, onBack }: {
  state: ComplexDetailState
  onClose: () => void
  onOpenAnnouncement: (announcementId: string) => void
  onRetry: () => void
} & DetailBackProps) {
  if (state.status === 'closed') {
    return null
  }
  return (
    <div className="housing-detail-layer">
      {state.status === 'ready' && state.detail
        ? <HousingComplexDetailPanel
            backButton={<DetailBackButton backTarget={backTarget} onBack={onBack} />}
            detail={toHousingComplexDetailData(state.detail)}
            onClose={onClose} onOpenAnnouncement={onOpenAnnouncement} />
        : <HousingDetailStatePanel kind="complex" id={state.complexId} backButton={<DetailBackButton backTarget={backTarget} onBack={onBack} />}
            status={state.status} errorMessage={state.errorMessage}
            onClose={onClose} onRetry={onRetry} />}
    </div>
  )
}

function AnnouncementDetailLayer({ state, onClose, onOpenComplex, onRetry, backTarget, onBack }: {
  state: AnnouncementDetailState
  onClose: () => void
  onOpenComplex: (complexId: string) => void
  onRetry: () => void
} & DetailBackProps) {
  if (state.status === 'closed') {
    return null
  }
  return (
    <div className="housing-detail-layer">
      {state.status === 'ready' && state.detail
        ? <HousingAnnouncementDetailPanel
            backButton={<DetailBackButton backTarget={backTarget} onBack={onBack} />}
            detail={toHousingAnnouncementDetailData(state.detail)}
            onClose={onClose} onOpenComplex={onOpenComplex} />
        : <HousingDetailStatePanel kind="announcement" id={state.announcementId} backButton={<DetailBackButton backTarget={backTarget} onBack={onBack} />}
            status={state.status} errorMessage={state.errorMessage} onClose={onClose} onRetry={onRetry} />}
    </div>
  )
}

function ResultTabs({
  activeTab,
  onSelect,
}: {
  activeTab: ResultTab
  onSelect: (tab: ResultTab) => void
}) {
  const complexTabRef = useRef<HTMLButtonElement>(null)
  const announcementTabRef = useRef<HTMLButtonElement>(null)

  function selectFromKeyboard(
    event: KeyboardEvent<HTMLButtonElement>,
    currentTab: ResultTab,
  ) {
    const targetTab = keyboardResultTab(event.key, currentTab)
    if (targetTab === null) {
      return
    }
    event.preventDefault()
    onSelect(targetTab)
    const target = targetTab === 'complexes'
      ? complexTabRef.current
      : announcementTabRef.current
    target?.focus({ preventScroll: true })
  }

  return (
    <div className="housing-results__tabs" role="tablist" aria-label="결과 종류">
      <button
        ref={complexTabRef}
        id="complex-results-tab"
        type="button"
        role="tab"
        aria-controls="complex-results-panel"
        aria-selected={activeTab === 'complexes'}
        className={activeTab === 'complexes' ? 'is-active' : undefined}
        tabIndex={activeTab === 'complexes' ? 0 : -1}
        onClick={() => onSelect('complexes')}
        onKeyDown={(event) => selectFromKeyboard(event, 'complexes')}
      >
        단지 목록
      </button>
      <button
        ref={announcementTabRef}
        id="announcement-results-tab"
        type="button"
        role="tab"
        aria-controls="announcement-results-panel"
        aria-selected={activeTab === 'announcements'}
        className={activeTab === 'announcements' ? 'is-active' : undefined}
        tabIndex={activeTab === 'announcements' ? 0 : -1}
        onClick={() => onSelect('announcements')}
        onKeyDown={(event) => selectFromKeyboard(event, 'announcements')}
      >
        공고 목록
      </button>
    </div>
  )
}

function AnnouncementResultContent({
  state,
  selectedAnnouncementId,
  onSelect,
  onCardRef,
  onRetry,
}: {
  state: AnnouncementResultsState
  selectedAnnouncementId: string | null
  onSelect: (announcementId: string) => void
  onCardRef: (announcementId: string, node: HTMLElement | null) => void
  onRetry: () => void
}) {
  if (state.status === 'idle') {
    return (
      <div className="housing-results__state" role="status">
        <strong>공고 목록을 준비하고 있습니다.</strong>
      </div>
    )
  }

  if (state.status === 'loading' && state.items.length === 0) {
    return (
      <div className="housing-results__state" role="status">
        <strong>공고를 불러오고 있습니다.</strong>
        <span>현재 제공되는 최신 공고를 확인하고 있습니다.</span>
      </div>
    )
  }

  if (state.status === 'error' && state.items.length === 0) {
    return (
      <div className="housing-results__state housing-results__state--error" role="alert">
        <strong>공고 목록을 불러오지 못했습니다.</strong>
        <span>{state.errorMessage}</span>
        <button type="button" onClick={onRetry}>다시 시도</button>
      </div>
    )
  }

  if (state.status === 'ready' && state.items.length === 0) {
    return (
      <div className="housing-results__state" role="status">
        <strong>현재 확인되는 공고가 없습니다.</strong>
        <span>새 공고가 등록되면 이 목록에서 확인할 수 있습니다.</span>
      </div>
    )
  }

  return (
    <>
      {state.status === 'error' && (
        <div className="housing-results__inline-error" role="alert">
          <span>{state.errorMessage}</span>
          <button type="button" onClick={onRetry}>다시 시도</button>
        </div>
      )}
      <ul className="housing-results__list">
        {state.items.map((announcement) => (
          <li key={announcement.announcementId}>
            <HousingAnnouncementCard
              announcement={toHousingAnnouncementCardData(announcement)}
              selected={selectedAnnouncementId === announcement.announcementId}
              cardRef={(node) => onCardRef(announcement.announcementId, node)}
              onSelect={onSelect}
            />
          </li>
        ))}
      </ul>
    </>
  )
}

function keyboardResultTab(key: string, currentTab: ResultTab) {
  if (key === 'Home') {
    return 'complexes' as const
  }
  if (key === 'End') {
    return 'announcements' as const
  }
  if (key !== 'ArrowLeft' && key !== 'ArrowRight') {
    return null
  }
  return currentTab === 'complexes' ? 'announcements' : 'complexes'
}

function resultCountLabel(
  activeTab: ResultTab,
  complexes: ComplexResultsState,
  announcements: AnnouncementResultsState,
  requestBlocked: boolean,
  aggregateMapActive: boolean,
  aggregateMapEmpty: boolean,
  mapRefreshing: boolean,
) {
  if (
    activeTab === 'announcements'
    && (announcements.status === 'idle' || announcements.status === 'loading')
  ) {
    return {
      accessibleLabel: '공고 목록 불러오는 중',
      visibleLabel: '불러오는 중',
    }
  }
  if (
    activeTab === 'announcements'
    && announcements.status === 'error'
    && announcements.items.length === 0
  ) {
    return {
      accessibleLabel: '공고 목록 불러오기 실패',
      visibleLabel: '불러오기 실패',
    }
  }
  if (activeTab === 'announcements') {
    const count = announcements.items.length
    const suffix = announcements.hasNext ? '건 이상' : '건'
    return {
      accessibleLabel: `현재 불러온 공고 ${count}${suffix}`,
      visibleLabel: `${count}${suffix}`,
    }
  }
  if (aggregateMapActive) {
    if (aggregateMapEmpty) {
      return {
        accessibleLabel: '현재 지도 영역에 표시할 지역 마커 없음',
        visibleLabel: '0곳',
      }
    }
    return {
      accessibleLabel: '지역 마커를 선택해 지도 확대',
      visibleLabel: '지역 선택',
    }
  }
  if (requestBlocked) {
    return {
      accessibleLabel: '단지 조회를 위한 지도 확대 필요',
      visibleLabel: '확대 필요',
    }
  }
  if (complexes.status === 'idle' || (
    complexes.status === 'loading' && complexes.items.length === 0
  )) {
    return {
      accessibleLabel: '단지 목록 불러오는 중',
      visibleLabel: '불러오는 중',
    }
  }
  if (complexes.status === 'error' && complexes.items.length === 0) {
    return {
      accessibleLabel: '단지 목록 불러오기 실패',
      visibleLabel: '불러오기 실패',
    }
  }
  const count = complexes.totalCount === null ? '집계 중' : `${complexes.totalCount.toLocaleString('ko-KR')}곳`
  if (complexes.status === 'loading' || mapRefreshing) {
    return {
      accessibleLabel: `단지 목록 갱신 중, 이전 결과 ${count}`,
      visibleLabel: `${count} · 갱신 중`,
    }
  }
  return {
    accessibleLabel: `조회된 단지 ${count}`,
    visibleLabel: count,
  }
}

function mapFilterResultCountLabel({
  aggregateMapActive,
  requestBlocked,
  serverMapResult,
}: {
  aggregateMapActive: boolean
  requestBlocked: boolean
  serverMapResult: HousingMapResult | null
}) {
  if (requestBlocked || aggregateMapActive) {
    return undefined
  }
  return serverMapResult?.representation === 'INDIVIDUAL'
    ? `${serverMapResult.nodes.length}곳`
    : undefined
}

function ViewportAction({
  announcementsActive,
  decision,
}: {
  announcementsActive: boolean
  decision: ReturnType<typeof evaluateViewportRequest> | null
}) {
  if (decision && !decision.allowed) {
    return (
      <div className="housing-viewport-action housing-viewport-action--blocked" role="status">
        <span>{viewportGuidance(decision.reason, announcementsActive)}</span>
      </div>
    )
  }

  return null
}

function HousingMapRequestFeedback({
  errorMessage,
  onRetry,
  status,
}: {
  errorMessage: string | null
  onRetry: () => boolean
  status: 'idle' | 'loading' | 'ready' | 'error'
}) {
  if (status !== 'error') {
    return null
  }
  return (
    <div className="housing-results__inline-error" role="alert">
      <strong>지도 정보를 불러오지 못했습니다.</strong>
      <span>{errorMessage}</span>
      <button type="button" onClick={onRetry}>지도 다시 시도</button>
    </div>
  )
}

function ComplexRequestFeedback({
  state,
  onRetry,
}: {
  state: ComplexResultsState
  onRetry: () => void
}) {
  if (state.status === 'error') {
    return (
      <div className="housing-results__inline-error" role="alert">
        <strong>단지 목록을 불러오지 못했습니다.</strong>
        <span>{state.errorMessage}</span>
        <button type="button" onClick={onRetry}>다시 시도</button>
      </div>
    )
  }

  return (
    <p
      className="visually-hidden"
      role="status"
      aria-live="polite"
      aria-atomic="true"
    >
      {complexRequestStatusMessage(state)}
    </p>
  )
}

function complexRequestStatusMessage(state: ComplexResultsState) {
  if (state.status === 'idle') {
    return '지도와 단지 목록을 준비하고 있습니다.'
  }
  if (state.status === 'loading') {
    return state.items.length === 0
      ? '단지 목록을 불러오고 있습니다.'
      : '기존 결과를 유지하면서 새 지역을 확인하고 있습니다.'
  }
  if (state.status === 'loading-more') {
    return '단지 목록을 추가로 불러오고 있습니다.'
  }
  const total = state.totalCount === null ? '전체 수 집계 중' : `전체 ${state.totalCount.toLocaleString('ko-KR')}곳`
  return `단지 목록 갱신 완료, ${total}, 현재 ${state.items.length.toLocaleString('ko-KR')}곳 표시`
}

function ComplexResultContent({
  state,
  selectedComplexId,
  highlightedComplexIds,
  onSelect,
  onOpenAnnouncement,
  onHover,
  onCardRef,
}: {
  state: ComplexResultsState
  selectedComplexId: string | null
  highlightedComplexIds: ReadonlySet<string>
  onSelect: (complexId: string) => void
  onOpenAnnouncement: (announcementId: string) => void
  onHover: (complexId: string | null) => void
  onCardRef: (complexId: string, node: HTMLElement | null) => void
}) {
  if (state.status === 'idle') {
    return (
      <div className="housing-results__state">
        <strong>지도를 준비하고 있습니다.</strong>
        <span>지도가 열리면 현재 영역의 단지를 확인할 수 있습니다.</span>
      </div>
    )
  }

  if (state.status === 'loading' && state.items.length === 0) {
    return (
      <div className="housing-results__state">
        <strong>단지를 불러오고 있습니다.</strong>
        <span>현재 지도 영역을 확인하고 있습니다.</span>
      </div>
    )
  }

  if (state.status === 'error' && state.items.length === 0) {
    return null
  }

  if (state.status === 'ready' && state.items.length === 0) {
    return (
      <div className="housing-results__state">
        <strong>이 지역에서 확인되는 단지가 없습니다.</strong>
        <span>지도를 다른 지역으로 옮기거나 조금 더 넓게 확인해 주세요.</span>
      </div>
    )
  }

  return (
    <ul className="housing-results__list">
      {state.items.map((complex) => (
        <li key={complex.complexId}>
          <HousingComplexCard
            complex={toHousingComplexCardData(complex)}
            selected={selectedComplexId === complex.complexId}
            hovered={highlightedComplexIds.has(complex.complexId)}
            cardRef={(node) => onCardRef(complex.complexId, node)}
            onSelect={onSelect}
            onOpenAnnouncement={onOpenAnnouncement}
            onHover={onHover}
          />
        </li>
      ))}
    </ul>
  )
}

function toNaverMapMarkers(
  complexes: readonly MapComplex[],
  selectedComplexId: string | null,
  highlightedComplexIds: ReadonlySet<string>,
  detail: ComplexDetail | null,
  searchComplex: SearchResultItem | null,
): NaverMapMarker[] {
  const markers = complexes.map((complex) => ({
    ...presentMapComplexMarker(complex),
    highlighted: highlightedComplexIds.has(complex.complexId),
    id: complex.complexId,
    latitude: complex.latitude,
    longitude: complex.longitude,
    name: complex.name ?? MISSING_DATA_LABEL,
    selected: complex.complexId === selectedComplexId,
  }))
  const target = toDetailMapTarget(detail)
  const markersWithDetail = detail !== null
    && target !== undefined
    && !markers.some((marker) => marker.id === detail.complexId)
    ? [
        ...markers,
        {
          ...presentComplexDetailMarker(detail),
          highlighted: highlightedComplexIds.has(detail.complexId),
          id: detail.complexId,
          latitude: target.latitude,
          longitude: target.longitude,
          name: detail.name ?? MISSING_DATA_LABEL,
          selected: true,
        },
      ]
    : markers
  if (
    searchComplex?.type !== 'COMPLEX'
    || searchComplex.id !== selectedComplexId
    || searchComplex.latitude === null
    || searchComplex.longitude === null
    || markersWithDetail.some((marker) => marker.id === searchComplex.id)
  ) {
    return markersWithDetail
  }

  return [
    ...markersWithDetail,
    {
      agencyLabel: MISSING_DATA_LABEL,
      agencyName: MISSING_DATA_LABEL,
      deposit: null,
      highlighted: false,
      id: searchComplex.id,
      latitude: searchComplex.latitude,
      longitude: searchComplex.longitude,
      monthlyRent: null,
      name: searchComplex.title,
      rentalTypeLabel: MISSING_DATA_LABEL,
      rentalTypeName: MISSING_DATA_LABEL,
      selected: true,
    },
  ]
}

function toDetailMapTarget(
  detail: ComplexDetail | null,
): NaverMapCameraTarget | undefined {
  const latitude = detail?.address?.latitude
  const longitude = detail?.address?.longitude
  if (
    latitude === null ||
    latitude === undefined ||
    longitude === null ||
    longitude === undefined ||
    !Number.isFinite(latitude) ||
    !Number.isFinite(longitude) ||
    latitude < -90 ||
    latitude > 90 ||
    longitude < -180 ||
    longitude > 180
  ) {
    return undefined
  }
  return { latitude, longitude }
}

function detailErrorMessage(error: unknown) {
  if (error instanceof Error && error.message.trim()) {
    return error.message
  }
  return '잠시 후 다시 시도해 주세요.'
}

function isNotFoundError(error: unknown) {
  return error instanceof PublicHousingHttpError && error.status === 404
}

function toSearchString(searchParams: URLSearchParams) {
  const search = searchParams.toString()
  return search ? `?${search}` : ''
}

function pickDetailLocationQuery(search: string) {
  const detailSearch = new URLSearchParams()
  new URLSearchParams(search).forEach((value, key) => {
    if (key === 'complexId' || key === 'announcementId') {
      detailSearch.append(key, value)
    }
  })
  return detailSearch.toString()
}

function boundaryScreenPadding(workspace: HTMLElement | null) {
  const padding = { top: 24, right: 24, bottom: 24, left: 24 }
  const map = workspace?.querySelector('.map-surface')?.getBoundingClientRect()
  if (!map || map.width <= 0 || map.height <= 0) {
    return padding
  }
  const panels = workspace?.querySelectorAll('.housing-detail-layer, .housing-map-filter [role="toolbar"], .housing-map-filter [data-topic], .housing-map-filter [role="dialog"]') ?? []
  for (const panel of panels) {
    const style = window.getComputedStyle(panel)
    if (style.visibility === 'hidden' || style.display === 'none') {
      continue
    }
    const rect = panel.getBoundingClientRect()
    if (rect.width <= 0 || rect.height <= 0 || rect.right <= map.left || rect.left >= map.right
      || rect.bottom <= map.top || rect.top >= map.bottom) {
      continue
    }
    if (rect.height > map.height / 2 && rect.width < map.width * 0.8) {
      if (rect.left < map.left + map.width / 2) {
        padding.left = Math.max(padding.left, rect.right - map.left + 16)
      } else {
        padding.right = Math.max(padding.right, map.right - rect.left + 16)
      }
    } else if (rect.top < map.top + map.height / 2) {
      padding.top = Math.max(padding.top, rect.bottom - map.top + 16)
    } else {
      padding.bottom = Math.max(padding.bottom, map.bottom - rect.top + 16)
    }
  }
  // Only reduce padding when the overlays leave no usable map area.
  const horizontalScale = Math.min(1, Math.max(0, map.width - 48) / (padding.left + padding.right))
  const verticalScale = Math.min(1, Math.max(0, map.height - 48) / (padding.top + padding.bottom))
  padding.left *= horizontalScale
  padding.right *= horizontalScale
  padding.top *= verticalScale
  padding.bottom *= verticalScale
  return padding
}

function detailScreenOffset(workspace: HTMLElement | null) {
  if (window.innerWidth < 768) {
    return undefined
  }
  const map = workspace?.querySelector('.map-surface')?.getBoundingClientRect()
  const panel = workspace?.querySelector('.housing-detail-layer')?.getBoundingClientRect()
  if (!map || !panel || map.width <= 0) {
    return undefined
  }
  const left = Math.max(0, panel.right - map.left + 16)
  const right = map.width - 24
  if (left >= right) {
    return undefined
  }
  return { x: (left + right - map.width) / 2, y: 0 }
}

function detailResultTab(
  detail: ReturnType<typeof parseDetailLocation>,
): ResultTab | null {
  if (detail.kind === 'complex') {
    return 'complexes'
  }
  if (detail.kind === 'announcement') {
    return 'announcements'
  }
  return null
}

function detailKind(tab: ResultTab) {
  return tab === 'complexes' ? 'complex' as const : 'announcement' as const
}

function isReadyDetailReturnTarget({
  announcementDetail,
  complexDetail,
  location,
  pending,
}: {
  announcementDetail: AnnouncementDetailState
  complexDetail: ComplexDetailState
  location: ReturnType<typeof parseDetailLocation>
  pending: DetailReturnFocus
}) {
  if (pending.kind === 'complex') {
    return location.kind === 'complex'
      && location.complexId === pending.id
      && complexDetail.status === 'ready'
      && complexDetail.complexId === pending.id
  }
  return location.kind === 'announcement'
    && location.announcementId === pending.id
    && announcementDetail.status === 'ready'
    && announcementDetail.announcementId === pending.id
}

function findDetailReturnFocusTarget(actionKey: string) {
  return [...document.querySelectorAll<HTMLElement>(
    '[data-detail-return-focus]',
  )].find((element) => element.dataset.detailReturnFocus === actionKey)
}

function setComplexCardRef(
  cards: Map<string, HTMLElement>,
  complexId: string,
  node: HTMLElement | null,
) {
  if (node === null) {
    cards.delete(complexId)
    return
  }
  cards.set(complexId, node)
}

function setAnnouncementCardRef(
  cards: Map<string, HTMLElement>,
  announcementId: string,
  node: HTMLElement | null,
) {
  if (node === null) {
    cards.delete(announcementId)
    return
  }
  cards.set(announcementId, node)
}

function clearDetailOpenerRefs(refs: {
  announcementDetailOpenerIdRef: { current: string | null }
  announcementDetailOpenerRef: { current: HTMLElement | null }
  complexDetailOpenerIdRef: { current: string | null }
  complexDetailOpenerRef: { current: HTMLElement | null }
  complexDetailOpenerWasMarkerRef: { current: boolean }
}) {
  refs.announcementDetailOpenerIdRef.current = null
  refs.announcementDetailOpenerRef.current = null
  refs.complexDetailOpenerIdRef.current = null
  refs.complexDetailOpenerRef.current = null
  refs.complexDetailOpenerWasMarkerRef.current = false
}

function restoreDetailListFocus({
  announcementCards,
  announcementId,
  announcementOpener,
  complexCards,
  complexId,
  complexOpener,
  kind,
  openerWasMarker,
}: {
  announcementCards: ReadonlyMap<string, HTMLElement>
  announcementId: string | null
  announcementOpener: HTMLElement | null
  complexCards: ReadonlyMap<string, HTMLElement>
  complexId: string | null
  complexOpener: HTMLElement | null
  kind: ResultTab
  openerWasMarker: boolean
}) {
  const opener = announcementOpener ?? complexOpener
  if (isAvailableFocusTarget(opener)) {
    opener.focus({ preventScroll: true })
    return
  }
  if (kind === 'announcements') {
    restoreAnnouncementFocus(
      announcementCards,
      announcementId,
      announcementOpener,
    )
    return
  }
  restoreComplexFocus({
    cards: complexCards,
    complexId,
    opener: complexOpener,
    openerWasMarker,
  })
}

function restoreAnnouncementFocus(
  cards: ReadonlyMap<string, HTMLElement>,
  announcementId: string | null,
  opener: HTMLElement | null,
) {
  if (isAvailableFocusTarget(opener)) {
    opener.focus({ preventScroll: true })
    return
  }
  const button = announcementId === null
    ? null
    : cards.get(announcementId)?.querySelector<HTMLButtonElement>(
        'button[data-announcement-detail-trigger]',
      )
  if (isAvailableFocusTarget(button)) {
    button.focus({ preventScroll: true })
    return
  }
  focusActiveResultTab()
}

function restoreComplexFocus({
  cards,
  complexId,
  opener,
  openerWasMarker,
}: {
  cards: ReadonlyMap<string, HTMLElement>
  complexId: string | null
  opener: HTMLElement | null
  openerWasMarker: boolean
}) {
  if (isAvailableFocusTarget(opener)) {
    opener.focus({ preventScroll: true })
    return
  }
  if (complexId === null) {
    focusActiveResultTab()
    return
  }
  if (openerWasMarker) {
    const marker = findComplexMarker(complexId)
    if (isAvailableFocusTarget(marker)) {
      marker.focus({ preventScroll: true })
      return
    }
  }
  if (focusComplexCard(cards.get(complexId))) {
    return
  }
  focusActiveResultTab()
}

function findComplexMarker(complexId: string) {
  return [...document.querySelectorAll<HTMLButtonElement>(
    '[data-map-complex-marker][data-complex-id]',
  )].find((marker) => marker.dataset.complexId === complexId)
}

function focusComplexCard(card: HTMLElement | undefined) {
  const button = card?.querySelector<HTMLButtonElement>(
    'button[data-complex-detail-trigger]',
  )
  if (!isAvailableFocusTarget(button)) {
    return false
  }
  button.focus({ preventScroll: true })
  return true
}

function focusActiveResultTab() {
  document.querySelector<HTMLButtonElement>(
    '[role="tab"][aria-selected="true"]',
  )?.focus({ preventScroll: true })
}

function isAvailableFocusTarget(
  element: HTMLElement | null | undefined,
): element is HTMLElement {
  return Boolean(element?.isConnected && !element.closest('[hidden]'))
}

function viewportForBounds(bounds: MapBounds): ViewportSnapshot {
  return {
    bounds,
    center: {
      latitude: (bounds.southWestLat + bounds.northEastLat) / 2,
      longitude: (bounds.southWestLng + bounds.northEastLng) / 2,
    },
    zoom: 14,
  }
}

function viewportForMapQuery(
  bounds: MapBounds,
  zoom: number,
): ViewportSnapshot {
  return { ...viewportForBounds(bounds), zoom }
}

function viewportGuidance(
  reason: ViewportBlockReason,
  announcementsActive: boolean,
) {
  const subject = announcementsActive ? '지도 마커를' : '단지를'
  if (reason === 'zoom-too-low') {
    return `${subject} 불러오려면 지도를 조금 더 확대해 주세요.`
  }
  if (
    reason === 'latitude-span-too-large' ||
    reason === 'longitude-span-too-large'
  ) {
    return '요청 범위가 넓습니다. 지도를 조금 더 확대해 주세요.'
  }
  return '현재 지도 범위를 확인하지 못했습니다. 지도를 다시 움직여 주세요.'
}

function isAbortError(error: unknown) {
  return error instanceof DOMException && error.name === 'AbortError'
}
