import {
  type KeyboardEvent,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react'
import { useLocation, useNavigate } from 'react-router'
import NaverMap, {
  type NaverMapCameraTarget,
  type NaverMapMarker,
} from '../maps/naver/NaverMap.tsx'
import { defaultPublicHousingRepository } from './api/defaultPublicHousingRepository.ts'
import { PublicHousingHttpError } from './api/publicHousingRepository.ts'
import type { PublicHousingRepository } from './api/publicHousingRepository.ts'
import {
  type AnnouncementResultsState,
  useAnnouncementResults,
} from './announcements/useAnnouncementResults.ts'
import { HousingAnnouncementDetailPanel } from './components/HousingAnnouncementDetailPanel.tsx'
import { HousingAnnouncementCard } from './components/HousingAnnouncementCard.tsx'
import {
  HousingComplexCard,
  type HousingComplexCardData,
} from './components/HousingComplexCard.tsx'
import { HousingComplexDetailPanel } from './components/HousingComplexDetailPanel.tsx'
import {
  evaluateViewportRequest,
  type ViewportBlockReason,
  type ViewportSnapshot,
} from './map/viewportPolicy.ts'
import {
  resolveLocalMapMarkers,
  type LocalMapSnapshot,
} from './map/localMapMarkerResolver.ts'
import type {
  AnnouncementDetail,
  ComplexDetail,
  ComplexListItem,
  MapBounds,
  MapComplex,
} from './model/publicHousing.ts'
import {
  clearDetailQuery,
  parseDetailLocation,
  setAnnouncementIdQuery,
  setComplexIdQuery,
} from './navigation/detailLocation.ts'
import { toHousingAnnouncementDetailData } from './presentation/announcementDetailPresentation.ts'
import { toHousingComplexDetailData } from './presentation/complexDetailPresentation.ts'
import { toHousingAnnouncementCardData } from './presentation/announcementPresentation.ts'

const PAGE_SIZE = 20
const DETAIL_HISTORY_STATE_KEY = 'toadzipDetailEntry'
const DETAIL_RETURN_FOCUS_STACK_KEY = 'toadzipDetailReturnFocusStack'

type ResultTab = 'complexes' | 'announcements'

type RequestStatus = 'idle' | 'loading' | 'loading-more' | 'ready' | 'error'

interface MapResultsState {
  readonly errorMessage: string | null
  readonly items: readonly MapComplex[]
  readonly status: RequestStatus
}

interface ComplexResultsState {
  readonly errorMessage: string | null
  readonly hasNext: boolean
  readonly items: readonly ComplexListItem[]
  readonly nextCursor: string | null
  readonly status: RequestStatus
}

interface AppliedViewport {
  readonly bounds: MapBounds
  readonly signature: string
}

type DetailStatus =
  | 'closed'
  | 'loading'
  | 'ready'
  | 'not-found'
  | 'error'

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

interface DetailReturnFocus {
  readonly actionKey: string
  readonly id: string
  readonly kind: 'announcement' | 'complex'
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
  localMapSnapshot?: LocalMapSnapshot
  repository?: PublicHousingRepository
}

const INITIAL_MAP_RESULTS: MapResultsState = {
  errorMessage: null,
  items: [],
  status: 'idle',
}

const INITIAL_COMPLEX_RESULTS: ComplexResultsState = {
  errorMessage: null,
  hasNext: false,
  items: [],
  nextCursor: null,
  status: 'idle',
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
  localMapSnapshot,
  repository = defaultPublicHousingRepository,
}: PublicHousingExplorerProps) {
  const location = useLocation()
  const navigate = useNavigate()
  const [viewport, setViewport] = useState<ViewportSnapshot | null>(null)
  const [activeResultTab, setActiveResultTab] =
    useState<ResultTab>('complexes')
  const [announcementListRequested, setAnnouncementListRequested] =
    useState(false)
  const activeResultTabRef = useRef<ResultTab>(activeResultTab)
  activeResultTabRef.current = activeResultTab
  const [appliedViewport, setAppliedViewport] =
    useState<AppliedViewport | null>(null)
  const [mapResults, setMapResults] =
    useState<MapResultsState>(INITIAL_MAP_RESULTS)
  const [complexResults, setComplexResults] =
    useState<ComplexResultsState>(INITIAL_COMPLEX_RESULTS)
  const [selectedComplexId, setSelectedComplexId] = useState<string | null>(
    null,
  )
  const [hoveredComplexId, setHoveredComplexId] = useState<string | null>(null)
  const [expandedRegionCode, setExpandedRegionCode] = useState<string | null>(
    null,
  )
  const [clusterFocusRegionCode, setClusterFocusRegionCode] =
    useState<string | null>(null)
  const [complexDetail, setComplexDetail] =
    useState<ComplexDetailState>(INITIAL_COMPLEX_DETAIL)
  const [announcementDetail, setAnnouncementDetail] =
    useState<AnnouncementDetailState>(INITIAL_ANNOUNCEMENT_DETAIL)
  const [detailRetryRevision, setDetailRetryRevision] = useState(0)
  const firstRequestStartedRef = useRef(false)
  const requestRevisionRef = useRef(0)
  const searchAbortRef = useRef<AbortController | null>(null)
  const paginationAbortRef = useRef<AbortController | null>(null)
  const detailTabChangedRef = useRef(false)
  const previousDetailKindRef = useRef<ResultTab | null>(null)
  const complexDetailOpenerRef = useRef<HTMLElement | null>(null)
  const complexDetailOpenerIdRef = useRef<string | null>(null)
  const complexDetailOpenerTabRef = useRef<ResultTab | null>(null)
  const complexDetailOpenerWasMarkerRef = useRef(false)
  const announcementDetailOpenerRef = useRef<HTMLElement | null>(null)
  const announcementDetailOpenerIdRef = useRef<string | null>(null)
  const announcementDetailOpenerTabRef = useRef<ResultTab | null>(null)
  const detailReturnFocusStack = useMemo(
    () => readDetailReturnFocusStack(location.state),
    [location.state],
  )
  const previousDetailReturnFocusStackRef = useRef(detailReturnFocusStack)
  const pendingDetailReturnFocusRef = useRef<DetailReturnFocus | null>(null)
  const pendingListFocusRef = useRef<PendingListFocus | null>(null)
  const complexCardRefsRef = useRef(new Map<string, HTMLElement>())
  const announcementCardRefsRef = useRef(new Map<string, HTMLElement>())
  const detailLocation = useMemo(
    () => parseDetailLocation(new URLSearchParams(location.search)),
    [location.search],
  )
  const announcementResults = useAnnouncementResults(
    repository,
    announcementListRequested
      && activeResultTab === 'announcements'
      && detailLocation.kind !== 'announcement',
  )

  useEffect(() => {
    setExpandedRegionCode(null)
    setClusterFocusRegionCode(null)
  }, [localMapSnapshot])

  useEffect(() => {
    if (localMapSnapshot === undefined || selectedComplexId === null) {
      return
    }
    const selectedComplex = localMapSnapshot.complexes.find(
      (complex) => complex.complexId === selectedComplexId,
    )
    if (selectedComplex !== undefined) {
      setExpandedRegionCode(selectedComplex.regionCode)
      setClusterFocusRegionCode(null)
    }
  }, [localMapSnapshot, selectedComplexId])

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
      const listTab = previousKind === 'announcements'
        ? announcementDetailOpenerTabRef.current
        : complexDetailOpenerTabRef.current
      const nextResultTab = detailTabChangedRef.current
        ? activeResultTabRef.current
        : listTab ?? 'complexes'
      if (!detailTabChangedRef.current) {
        setActiveResultTab(nextResultTab)
      }
      detailTabChangedRef.current = false
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
        announcementDetailOpenerTabRef,
        complexDetailOpenerIdRef,
        complexDetailOpenerRef,
        complexDetailOpenerTabRef,
        complexDetailOpenerWasMarkerRef,
      })
      if (previousKind === null) {
        return
      }
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
        announcementDetailOpenerTabRef,
        complexDetailOpenerIdRef,
        complexDetailOpenerRef,
        complexDetailOpenerTabRef,
        complexDetailOpenerWasMarkerRef,
      })
      const nextSearch = clearDetailQuery(
        new URLSearchParams(location.search),
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
      setActiveResultTab('complexes')
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
      setActiveResultTab('announcements')
      setComplexDetail(INITIAL_COMPLEX_DETAIL)
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
    location.search,
    location.state,
    navigate,
    repository,
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
      target.focus()
      pendingDetailReturnFocusRef.current = null
    }, 0)
    return () => window.clearTimeout(timeout)
  }, [announcementDetail, complexDetail, detailLocation])

  const openDetail = useCallback((kind: ResultTab, id: string) => {
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
    if (kind === 'complexes') {
      complexDetailOpenerRef.current = opener
      complexDetailOpenerIdRef.current = id
      complexDetailOpenerTabRef.current = activeResultTab
      complexDetailOpenerWasMarkerRef.current = Boolean(
        opener?.classList.contains('housing-map-marker'),
      )
      setSelectedComplexId(id)
    }
    if (kind === 'announcements') {
      announcementDetailOpenerRef.current = opener
      announcementDetailOpenerIdRef.current = id
      announcementDetailOpenerTabRef.current = activeResultTab
    }
    detailTabChangedRef.current = false
    const currentKind = detailResultTab(detailLocation)
    const replace = currentKind === kind
    const internalState = replace
      ? location.state
      : withDetailHistoryState(location.state, returnFocus)
    const nextSearch = kind === 'complexes'
      ? setComplexIdQuery(currentSearch, id)
      : setAnnouncementIdQuery(currentSearch, id)
    setActiveResultTab(kind)
    navigate({
      hash: location.hash,
      pathname: location.pathname,
      search: toSearchString(nextSearch),
    }, {
      replace,
      state: internalState,
    })
  }, [
    activeResultTab,
    detailLocation,
    location.hash,
    location.pathname,
    location.search,
    location.state,
    navigate,
  ])

  const selectResultTab = useCallback((tab: ResultTab) => {
    if (detailResultTab(detailLocation) !== null) {
      detailTabChangedRef.current = true
    }
    if (tab === 'announcements') {
      setAnnouncementListRequested(true)
    }
    setActiveResultTab(tab)
  }, [detailLocation])

  const openComplexDetail = useCallback(
    (complexId: string) => openDetail('complexes', complexId),
    [openDetail],
  )

  const openAnnouncementDetail = useCallback(
    (announcementId: string) => openDetail('announcements', announcementId),
    [openDetail],
  )

  const expandRegionCluster = useCallback((regionCode: string) => {
    setExpandedRegionCode(regionCode)
    setClusterFocusRegionCode(regionCode)
  }, [])

  const closeDetail = useCallback(() => {
    if (isDetailHistoryState(location.state)) {
      navigate(-1)
      return
    }

    const nextSearch = clearDetailQuery(
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
    navigate,
  ])

  const applyViewport = useCallback(
    (nextViewport: ViewportSnapshot) => {
      const decision = evaluateViewportRequest(nextViewport)
      if (!decision.allowed) {
        return
      }

      searchAbortRef.current?.abort()
      paginationAbortRef.current?.abort()
      const controller = new AbortController()
      const revision = requestRevisionRef.current + 1
      requestRevisionRef.current = revision
      searchAbortRef.current = controller
      setAppliedViewport({
        bounds: nextViewport.bounds,
        signature: decision.boundsSignature,
      })
      setMapResults((current) => ({
        ...current,
        errorMessage: null,
        status: 'loading',
      }))
      setComplexResults((current) => ({
        ...current,
        errorMessage: null,
        hasNext: false,
        nextCursor: null,
        status: 'loading',
      }))

      repository
        .findMapComplexes(nextViewport.bounds, controller.signal)
        .then((items) => {
          if (requestRevisionRef.current !== revision) {
            return
          }
          setMapResults({ errorMessage: null, items, status: 'ready' })
        })
        .catch((error: unknown) => {
          if (isAbortError(error) || requestRevisionRef.current !== revision) {
            return
          }
          setMapResults((current) => ({
            ...current,
            errorMessage: requestErrorMessage(error),
            status: 'error',
          }))
        })

      repository
        .findComplexPage(
          nextViewport.bounds,
          null,
          PAGE_SIZE,
          controller.signal,
        )
        .then((page) => {
          if (requestRevisionRef.current !== revision) {
            return
          }
          setComplexResults({
            errorMessage: null,
            hasNext: page.hasNext,
            items: page.items,
            nextCursor: page.nextCursor,
            status: 'ready',
          })
        })
        .catch((error: unknown) => {
          if (isAbortError(error) || requestRevisionRef.current !== revision) {
            return
          }
          setComplexResults((current) => ({
            ...current,
            errorMessage: requestErrorMessage(error),
            status: 'error',
          }))
        })
    },
    [repository],
  )

  const handleViewportChange = useCallback(
    (nextViewport: ViewportSnapshot) => {
      setViewport(nextViewport)
      const decision = evaluateViewportRequest(nextViewport)
      if (firstRequestStartedRef.current || !decision.allowed) {
        return
      }
      firstRequestStartedRef.current = true
      applyViewport(nextViewport)
    },
    [applyViewport],
  )

  useEffect(() => {
    return () => {
      searchAbortRef.current?.abort()
      paginationAbortRef.current?.abort()
    }
  }, [])

  const loadMore = useCallback(() => {
    if (
      !appliedViewport ||
      !complexResults.hasNext ||
      !complexResults.nextCursor ||
      complexResults.status === 'loading-more'
    ) {
      return
    }

    paginationAbortRef.current?.abort()
    const controller = new AbortController()
    const revision = requestRevisionRef.current
    paginationAbortRef.current = controller
    setComplexResults((current) => ({
      ...current,
      errorMessage: null,
      status: 'loading-more',
    }))

    repository
      .findComplexPage(
        appliedViewport.bounds,
        complexResults.nextCursor,
        PAGE_SIZE,
        controller.signal,
      )
      .then((page) => {
        if (requestRevisionRef.current !== revision) {
          return
        }
        setComplexResults((current) => ({
          errorMessage: null,
          hasNext: page.hasNext,
          items: appendUniqueComplexes(current.items, page.items),
          nextCursor: page.nextCursor,
          status: 'ready',
        }))
      })
      .catch((error: unknown) => {
        if (isAbortError(error) || requestRevisionRef.current !== revision) {
          return
        }
        setComplexResults((current) => ({
          ...current,
          errorMessage: requestErrorMessage(error),
          status: 'error',
        }))
      })
  }, [appliedViewport, complexResults, repository])

  const viewportDecision = viewport
    ? evaluateViewportRequest(viewport)
    : null
  const pendingViewport = isPendingViewport(
    viewportDecision,
    appliedViewport?.signature ?? null,
  )
  const detailMapTarget = toDetailMapTarget(complexDetail.detail)
  const markers = useMemo(
    () => localMapSnapshot === undefined
      ? toNaverMapMarkers(
          mapResults.items,
          selectedComplexId,
          complexDetail.detail,
        )
      : resolveLocalMapMarkers(
          localMapSnapshot,
          expandedRegionCode,
          selectedComplexId,
        ),
    [
      complexDetail.detail,
      expandedRegionCode,
      localMapSnapshot,
      mapResults.items,
      selectedComplexId,
    ],
  )
  const resultCount = resultCountLabel(
    activeResultTab,
    complexResults,
    announcementResults.state,
  )
  const hasDetail = complexDetail.status !== 'closed'
    || announcementDetail.status !== 'closed'
  const selectedAnnouncementId = detailLocation.kind === 'announcement'
    ? detailLocation.announcementId
    : null

  return (
    <div className={!hasDetail
      ? 'housing-explorer'
      : 'housing-explorer has-detail'}>
      <aside className="housing-results" aria-label="공공임대주택 검색 결과">
        <header className="housing-results__header">
          <div>
            <p className="housing-results__eyebrow">지도 기반 탐색</p>
            <h1>공공임대주택</h1>
            {localMapSnapshot !== undefined && (
              <span className="housing-results__local-badge">로컬 mock</span>
            )}
          </div>
          <span
            className="housing-results__count"
            aria-label={resultCount.accessibleLabel}
            aria-live="polite"
          >
            {resultCount.visibleLabel}
          </span>
        </header>

        <ViewportAction
          announcementsActive={activeResultTab === 'announcements'}
          decision={viewportDecision}
          pending={pendingViewport}
          onApply={() => {
            if (viewport) {
              firstRequestStartedRef.current = true
              applyViewport(viewport)
            }
          }}
        />

        <ResultTabs activeTab={activeResultTab} onSelect={selectResultTab} />

        <div
          className="housing-results__panel"
          id="complex-results-panel"
          role="tabpanel"
          aria-labelledby="complex-results-tab"
          hidden={activeResultTab !== 'complexes'}
        >
            <ComplexResultContent
              state={complexResults}
              selectedComplexId={selectedComplexId}
              hoveredComplexId={hoveredComplexId}
              onSelect={openComplexDetail}
              onOpenAnnouncement={openAnnouncementDetail}
              onHover={setHoveredComplexId}
              onCardRef={(complexId, node) => {
                setComplexCardRef(complexCardRefsRef.current, complexId, node)
              }}
              onRetry={() => {
                if (appliedViewport && viewport) {
                  applyViewport({ ...viewport, bounds: appliedViewport.bounds })
                }
              }}
            />

            {complexResults.hasNext && (
              <button
                className="housing-results__more"
                type="button"
                onClick={loadMore}
                disabled={complexResults.status === 'loading-more'}
              >
                {complexResults.status === 'loading-more'
                  ? '불러오는 중'
                  : '단지 더 보기'}
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
      </aside>

      <main className="housing-map-workspace">
        <NaverMap
          cameraTarget={detailMapTarget}
          focusRegionCode={clusterFocusRegionCode ?? undefined}
          markers={markers}
          onClusterSelect={expandRegionCluster}
          onMarkerSelect={openComplexDetail}
          onViewportChange={handleViewportChange}
        />
        {mapResults.status === 'error' && (
          <div className="housing-map-notice" role="alert">
            <strong>단지 마커를 갱신하지 못했습니다.</strong>
            <span>{mapResults.errorMessage}</span>
          </div>
        )}
        <ComplexDetailLayer
          state={complexDetail}
          onClose={closeDetail}
          onOpenAnnouncement={openAnnouncementDetail}
          onRetry={() => setDetailRetryRevision((current) => current + 1)}
        />
        <AnnouncementDetailLayer
          state={announcementDetail}
          onClose={closeDetail}
          onOpenComplex={openComplexDetail}
          onRetry={() => setDetailRetryRevision((current) => current + 1)}
        />
      </main>
    </div>
  )
}

function ComplexDetailLayer({
  state,
  onClose,
  onOpenAnnouncement,
  onRetry,
}: {
  state: ComplexDetailState
  onClose: () => void
  onOpenAnnouncement: (announcementId: string) => void
  onRetry: () => void
}) {
  if (state.status === 'closed') {
    return null
  }

  if (state.status === 'ready' && state.detail) {
    return (
      <div className="housing-detail-layer">
        <HousingComplexDetailPanel
          detail={toHousingComplexDetailData(state.detail)}
          onClose={onClose}
          onOpenAnnouncement={onOpenAnnouncement}
        />
      </div>
    )
  }

  const content = detailStateContent(state)
  return (
    <ComplexDetailStatePanel
      content={content}
      state={state}
      onClose={onClose}
      onRetry={onRetry}
    />
  )
}

function AnnouncementDetailLayer({
  state,
  onClose,
  onOpenComplex,
  onRetry,
}: {
  state: AnnouncementDetailState
  onClose: () => void
  onOpenComplex: (complexId: string) => void
  onRetry: () => void
}) {
  if (state.status === 'closed') {
    return null
  }

  if (state.status === 'ready' && state.detail) {
    return (
      <div className="housing-detail-layer">
        <HousingAnnouncementDetailPanel
          detail={toHousingAnnouncementDetailData(state.detail)}
          onClose={onClose}
          onOpenComplex={onOpenComplex}
        />
      </div>
    )
  }

  return (
    <AnnouncementDetailStatePanel
      state={state}
      onClose={onClose}
      onRetry={onRetry}
    />
  )
}

function ComplexDetailStatePanel({
  content,
  state,
  onClose,
  onRetry,
}: {
  content: ReturnType<typeof detailStateContent>
  state: ComplexDetailState
  onClose: () => void
  onRetry: () => void
}) {
  const panelRef = useRef<HTMLElement>(null)

  useEffect(() => {
    panelRef.current?.focus()
  }, [state.complexId, state.status])

  function handleKeyDown(event: KeyboardEvent<HTMLElement>) {
    if (event.key !== 'Escape') {
      return
    }
    event.stopPropagation()
    onClose()
  }

  return (
    <aside
      ref={panelRef}
      className="housing-detail-layer housing-detail-state"
      aria-label="단지 상세 정보"
      tabIndex={-1}
      onKeyDown={handleKeyDown}
    >
      <header>
        <div>
          <span>단지 상세 정보</span>
          <strong>{content.title}</strong>
        </div>
        <button type="button" aria-label="단지 상세 닫기" onClick={onClose}>
          <span aria-hidden="true">×</span>
        </button>
      </header>
      <div
        className="housing-detail-state__content"
        role={state.status === 'loading' ? 'status' : 'alert'}
      >
        <strong>{content.heading}</strong>
        <span>{content.description}</span>
        {state.status === 'error' && (
          <button type="button" onClick={onRetry}>다시 시도</button>
        )}
      </div>
    </aside>
  )
}

function AnnouncementDetailStatePanel({
  state,
  onClose,
  onRetry,
}: {
  state: AnnouncementDetailState
  onClose: () => void
  onRetry: () => void
}) {
  const panelRef = useRef<HTMLElement>(null)
  const content = announcementDetailStateContent(state)

  useEffect(() => {
    panelRef.current?.focus()
  }, [state.announcementId, state.status])

  function handleKeyDown(event: KeyboardEvent<HTMLElement>) {
    if (event.key !== 'Escape') {
      return
    }
    event.stopPropagation()
    onClose()
  }

  return (
    <aside
      ref={panelRef}
      className="housing-detail-layer housing-detail-state"
      aria-label="공고 상세 정보"
      tabIndex={-1}
      onKeyDown={handleKeyDown}
    >
      <header>
        <div>
          <span>공고 상세 정보</span>
          <strong>{content.title}</strong>
        </div>
        <button type="button" aria-label="공고 상세 닫기" onClick={onClose}>
          <span aria-hidden="true">×</span>
        </button>
      </header>
      <div
        className="housing-detail-state__content"
        role={state.status === 'loading' ? 'status' : 'alert'}
      >
        <strong>{content.heading}</strong>
        <span>{content.description}</span>
        {state.status === 'error' && (
          <button type="button" onClick={onRetry}>다시 시도</button>
        )}
      </div>
    </aside>
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
    target?.focus()
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
    <div
      className="housing-results__scroll"
      aria-busy={state.status === 'loading-more'}
    >
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
    </div>
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
    return {
      accessibleLabel: `현재 불러온 공고 ${count}건`,
      visibleLabel: `${count}건`,
    }
  }
  const count = complexes.items.length
  return {
    accessibleLabel: `현재 불러온 단지 ${count}곳`,
    visibleLabel: `${count}곳`,
  }
}

function ViewportAction({
  announcementsActive,
  decision,
  pending,
  onApply,
}: {
  announcementsActive: boolean
  decision: ReturnType<typeof evaluateViewportRequest> | null
  pending: boolean
  onApply: () => void
}) {
  if (decision && !decision.allowed) {
    return (
      <div className="housing-viewport-action housing-viewport-action--blocked" role="status">
        <span>{viewportGuidance(decision.reason, announcementsActive)}</span>
      </div>
    )
  }

  if (!pending) {
    return null
  }

  return (
    <div className="housing-viewport-action">
      <span>
        {announcementsActive
          ? '공고는 전국 최신순이며 지도·단지만 이 영역으로 갱신합니다.'
          : '지도를 움직였습니다.'}
      </span>
      <button type="button" onClick={onApply}>
        {announcementsActive ? '지도·단지 다시 찾기' : '이 지역에서 다시 찾기'}
      </button>
    </div>
  )
}

function ComplexResultContent({
  state,
  selectedComplexId,
  hoveredComplexId,
  onSelect,
  onOpenAnnouncement,
  onHover,
  onCardRef,
  onRetry,
}: {
  state: ComplexResultsState
  selectedComplexId: string | null
  hoveredComplexId: string | null
  onSelect: (complexId: string) => void
  onOpenAnnouncement: (announcementId: string) => void
  onHover: (complexId: string | null) => void
  onCardRef: (complexId: string, node: HTMLElement | null) => void
  onRetry: () => void
}) {
  if (state.status === 'idle') {
    return (
      <div className="housing-results__state" role="status">
        <strong>지도를 준비하고 있습니다.</strong>
        <span>지도가 열리면 현재 영역의 단지를 확인할 수 있습니다.</span>
      </div>
    )
  }

  if (state.status === 'loading' && state.items.length === 0) {
    return (
      <div className="housing-results__state" role="status">
        <strong>단지를 불러오고 있습니다.</strong>
        <span>현재 지도 영역을 확인하고 있습니다.</span>
      </div>
    )
  }

  if (state.status === 'error' && state.items.length === 0) {
    return (
      <div className="housing-results__state housing-results__state--error" role="alert">
        <strong>단지 목록을 불러오지 못했습니다.</strong>
        <span>{state.errorMessage}</span>
        <button type="button" onClick={onRetry}>다시 시도</button>
      </div>
    )
  }

  if (state.status === 'ready' && state.items.length === 0) {
    return (
      <div className="housing-results__state" role="status">
        <strong>이 지역에서 확인되는 단지가 없습니다.</strong>
        <span>지도를 다른 지역으로 옮기거나 조금 더 넓게 확인해 주세요.</span>
      </div>
    )
  }

  return (
    <div className="housing-results__scroll" aria-busy={state.status === 'loading'}>
      {state.status === 'loading' && (
        <p className="housing-results__refreshing" role="status">
          기존 결과를 유지하면서 새 지역을 확인하고 있습니다.
        </p>
      )}
      {state.status === 'error' && (
        <div className="housing-results__inline-error" role="alert">
          <span>{state.errorMessage}</span>
          <button type="button" onClick={onRetry}>다시 시도</button>
        </div>
      )}
      <ul className="housing-results__list">
        {state.items.map((complex) => (
          <li key={complex.complexId}>
            <HousingComplexCard
              complex={toComplexCardData(complex)}
              selected={selectedComplexId === complex.complexId}
              hovered={hoveredComplexId === complex.complexId}
              cardRef={(node) => onCardRef(complex.complexId, node)}
              onSelect={onSelect}
              onOpenAnnouncement={onOpenAnnouncement}
              onHover={onHover}
            />
          </li>
        ))}
      </ul>
    </div>
  )
}

function toComplexCardData(complex: ComplexListItem): HousingComplexCardData {
  return {
    agencyName: complex.agency?.name ?? '기관 정보 확인 중',
    complexId: complex.complexId,
    depositMax: complex.depositMax,
    depositMin: complex.depositMin,
    exclusiveAreaMax: complex.exclusiveAreaMax,
    exclusiveAreaMin: complex.exclusiveAreaMin,
    monthlyRentMax: complex.monthlyRentMax,
    monthlyRentMin: complex.monthlyRentMin,
    name: complex.name ?? '단지명 정보 확인 중',
    regionName: complex.regionName ?? '지역 정보 확인 중',
    rentalTypeLabel: rentalTypeLabel(complex.rentalType),
    representativeAnnouncement: complex.representativeAnnouncement
      ? {
          announcementId: complex.representativeAnnouncement.announcementId,
          applicationEndAt:
            complex.representativeAnnouncement.applicationEndAt,
          applicationStatus:
            complex.representativeAnnouncement.applicationStatus ?? 'UNKNOWN',
          dDay: complex.representativeAnnouncement.dDay,
        }
      : null,
  }
}

function rentalTypeLabel(rentalType: string | null) {
  if (rentalType === null) {
    return '임대유형 정보 확인 중'
  }
  const labels: Record<string, string> = {
    ETC: '기타 공공임대',
    HAPPY_HOUSING: '행복주택',
    INTEGRATED_PUBLIC_RENTAL: '통합공공임대',
    NATIONAL_RENTAL: '국민임대',
    PERMANENT_RENTAL: '영구임대',
    PUBLIC_RENTAL_50Y: '50년 공공임대',
    REDEVELOPMENT_RENTAL: '재개발임대',
  }
  return labels[rentalType] ?? '임대유형 정보 확인 중'
}

function toNaverMapMarkers(
  complexes: readonly MapComplex[],
  selectedComplexId: string | null,
  detail: ComplexDetail | null,
): NaverMapMarker[] {
  const markers = complexes.map((complex) => ({
    kind: 'complex' as const,
    id: complex.complexId,
    latitude: complex.latitude,
    longitude: complex.longitude,
    name: complex.name ?? '단지명 정보 확인 중',
    selected: complex.complexId === selectedComplexId,
  }))
  const target = toDetailMapTarget(detail)
  if (
    detail === null ||
    target === undefined ||
    markers.some((marker) => marker.id === detail.complexId)
  ) {
    return markers
  }

  return [
    ...markers,
    {
      kind: 'complex',
      id: detail.complexId,
      latitude: target.latitude,
      longitude: target.longitude,
      name: detail.name ?? '단지명 정보 확인 중',
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

function detailStateContent(state: ComplexDetailState) {
  if (state.status === 'loading') {
    return {
      description: '선택한 단지의 기본 정보와 주택형을 확인하고 있습니다.',
      heading: '단지 상세를 불러오고 있습니다.',
      title: `단지 ${state.complexId ?? ''}`.trim(),
    }
  }
  if (state.status === 'not-found') {
    return {
      description: '삭제되었거나 아직 제공되지 않는 단지일 수 있습니다.',
      heading: '단지를 찾을 수 없습니다.',
      title: `단지 ${state.complexId ?? ''}`.trim(),
    }
  }
  return {
    description: state.errorMessage ?? '잠시 후 다시 시도해 주세요.',
    heading: '단지 상세를 불러오지 못했습니다.',
    title: `단지 ${state.complexId ?? ''}`.trim(),
  }
}

function announcementDetailStateContent(state: AnnouncementDetailState) {
  if (state.status === 'loading') {
    return {
      description: '접수 일정과 공급 단지 정보를 확인하고 있습니다.',
      heading: '공고 상세를 불러오고 있습니다.',
      title: `공고 ${state.announcementId ?? ''}`.trim(),
    }
  }
  if (state.status === 'not-found') {
    return {
      description: '삭제되었거나 아직 제공되지 않는 공고일 수 있습니다.',
      heading: '공고를 찾을 수 없습니다.',
      title: `공고 ${state.announcementId ?? ''}`.trim(),
    }
  }
  return {
    description: state.errorMessage ?? '잠시 후 다시 시도해 주세요.',
    heading: '공고 상세를 불러오지 못했습니다.',
    title: `공고 ${state.announcementId ?? ''}`.trim(),
  }
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

function withDetailHistoryState(
  state: unknown,
  returnFocus: DetailReturnFocus | null,
) {
  const currentState = isRecord(state) ? state : {}
  const currentStack = readDetailReturnFocusStack(state)
  const nextStack = returnFocus === null
    ? currentStack
    : [...currentStack, returnFocus]
  return {
    ...currentState,
    [DETAIL_HISTORY_STATE_KEY]: true,
    [DETAIL_RETURN_FOCUS_STACK_KEY]: nextStack,
  }
}

function isDetailHistoryState(state: unknown) {
  return isRecord(state) && state[DETAIL_HISTORY_STATE_KEY] === true
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
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

function toDetailReturnFocusLocation(
  detail: ReturnType<typeof parseDetailLocation>,
): Omit<DetailReturnFocus, 'actionKey'> | null {
  if (detail.kind === 'complex') {
    return { id: detail.complexId, kind: detail.kind }
  }
  if (detail.kind === 'announcement') {
    return { id: detail.announcementId, kind: detail.kind }
  }
  return null
}

function sameDetailLocation(
  left: Omit<DetailReturnFocus, 'actionKey'>,
  right: Omit<DetailReturnFocus, 'actionKey'>,
) {
  return left.kind === right.kind && left.id === right.id
}

function readDetailReturnFocusStack(state: unknown): readonly DetailReturnFocus[] {
  if (!isRecord(state)) {
    return []
  }
  const value = state[DETAIL_RETURN_FOCUS_STACK_KEY]
  if (!Array.isArray(value)) {
    return []
  }
  return value.filter(isDetailReturnFocus)
}

function isDetailReturnFocus(value: unknown): value is DetailReturnFocus {
  if (!isRecord(value)) {
    return false
  }
  const validKind = value.kind === 'announcement' || value.kind === 'complex'
  return validKind
    && typeof value.id === 'string'
    && typeof value.actionKey === 'string'
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
  announcementDetailOpenerTabRef: { current: ResultTab | null }
  complexDetailOpenerIdRef: { current: string | null }
  complexDetailOpenerRef: { current: HTMLElement | null }
  complexDetailOpenerTabRef: { current: ResultTab | null }
  complexDetailOpenerWasMarkerRef: { current: boolean }
}) {
  refs.announcementDetailOpenerIdRef.current = null
  refs.announcementDetailOpenerRef.current = null
  refs.announcementDetailOpenerTabRef.current = null
  refs.complexDetailOpenerIdRef.current = null
  refs.complexDetailOpenerRef.current = null
  refs.complexDetailOpenerTabRef.current = null
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
    opener.focus()
    return
  }
  const button = announcementId === null
    ? null
    : cards.get(announcementId)?.querySelector<HTMLButtonElement>('button')
  if (isAvailableFocusTarget(button)) {
    button.focus()
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
    opener.focus()
    return
  }
  if (complexId === null) {
    focusActiveResultTab()
    return
  }
  if (openerWasMarker) {
    const marker = findComplexMarker(complexId)
    if (isAvailableFocusTarget(marker)) {
      marker.focus()
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
    '.housing-map-marker[data-complex-id]',
  )].find((marker) => marker.dataset.complexId === complexId)
}

function focusComplexCard(card: HTMLElement | undefined) {
  const button = card?.querySelector<HTMLButtonElement>(
    'button[aria-haspopup="dialog"]',
  )
  if (!isAvailableFocusTarget(button)) {
    return false
  }
  button.focus()
  return true
}

function focusActiveResultTab() {
  document.querySelector<HTMLButtonElement>(
    '[role="tab"][aria-selected="true"]',
  )?.focus()
}

function isAvailableFocusTarget(
  element: HTMLElement | null | undefined,
): element is HTMLElement {
  return Boolean(element?.isConnected && !element.closest('[hidden]'))
}

function isPendingViewport(
  decision: ReturnType<typeof evaluateViewportRequest> | null,
  appliedSignature: string | null,
) {
  if (!decision?.allowed || appliedSignature === null) {
    return false
  }
  return decision.boundsSignature !== appliedSignature
}

function appendUniqueComplexes(
  current: readonly ComplexListItem[],
  next: readonly ComplexListItem[],
): readonly ComplexListItem[] {
  const knownIds = new Set(current.map((complex) => complex.complexId))
  return [
    ...current,
    ...next.filter((complex) => !knownIds.has(complex.complexId)),
  ]
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

function requestErrorMessage(error: unknown) {
  if (error instanceof Error && error.message.trim()) {
    return error.message
  }
  return '잠시 후 다시 시도해 주세요.'
}

function isAbortError(error: unknown) {
  return error instanceof DOMException && error.name === 'AbortError'
}
