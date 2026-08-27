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
import {
  publicHousingRepository,
  PublicHousingHttpError,
} from './api/publicHousingRepository.ts'
import type { PublicHousingRepository } from './api/publicHousingRepository.ts'
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
import type {
  ComplexDetail,
  ComplexListItem,
  MapBounds,
  MapComplex,
} from './model/publicHousing.ts'
import {
  clearComplexIdQuery,
  parseComplexIdQuery,
  setComplexIdQuery,
} from './navigation/detailLocation.ts'
import { toHousingComplexDetailData } from './presentation/complexDetailPresentation.ts'

const PAGE_SIZE = 20
const DETAIL_HISTORY_STATE_KEY = 'toadzipComplexDetailEntry'

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

type ComplexDetailStatus =
  | 'closed'
  | 'loading'
  | 'ready'
  | 'not-found'
  | 'error'

interface ComplexDetailState {
  readonly complexId: string | null
  readonly detail: ComplexDetail | null
  readonly errorMessage: string | null
  readonly status: ComplexDetailStatus
}

export interface PublicHousingExplorerProps {
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

export function PublicHousingExplorer({
  repository = publicHousingRepository,
}: PublicHousingExplorerProps) {
  const location = useLocation()
  const navigate = useNavigate()
  const [viewport, setViewport] = useState<ViewportSnapshot | null>(null)
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
  const [complexDetail, setComplexDetail] =
    useState<ComplexDetailState>(INITIAL_COMPLEX_DETAIL)
  const [detailRetryRevision, setDetailRetryRevision] = useState(0)
  const firstRequestStartedRef = useRef(false)
  const requestRevisionRef = useRef(0)
  const searchAbortRef = useRef<AbortController | null>(null)
  const paginationAbortRef = useRef<AbortController | null>(null)
  const detailOpenerRef = useRef<HTMLElement | null>(null)
  const detailOpenerComplexIdRef = useRef<string | null>(null)
  const detailOpenerWasMarkerRef = useRef(false)
  const complexCardRefsRef = useRef(new Map<string, HTMLElement>())
  const complexIdQuery = useMemo(
    () => parseComplexIdQuery(new URLSearchParams(location.search)),
    [location.search],
  )

  useEffect(() => {
    if (complexIdQuery.kind === 'absent') {
      setComplexDetail(INITIAL_COMPLEX_DETAIL)
      const opener = detailOpenerRef.current
      const openerComplexId = detailOpenerComplexIdRef.current
      const openerWasMarker = detailOpenerWasMarkerRef.current
      detailOpenerRef.current = null
      detailOpenerComplexIdRef.current = null
      detailOpenerWasMarkerRef.current = false
      setTimeout(() => restoreComplexFocus({
        cards: complexCardRefsRef.current,
        complexId: openerComplexId,
        opener,
        openerWasMarker,
      }), 0)
      return
    }

    if (complexIdQuery.kind === 'invalid') {
      setComplexDetail(INITIAL_COMPLEX_DETAIL)
      const nextSearch = clearComplexIdQuery(
        new URLSearchParams(location.search),
      )
      navigate({
        hash: location.hash,
        pathname: location.pathname,
        search: toSearchString(nextSearch),
      }, { replace: true, state: location.state })
      return
    }

    const complexId = complexIdQuery.complexId
    const controller = new AbortController()
    let active = true
    setSelectedComplexId(complexId)
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

    return () => {
      active = false
      controller.abort()
    }
  }, [
    complexIdQuery,
    detailRetryRevision,
    location.hash,
    location.pathname,
    location.search,
    location.state,
    navigate,
    repository,
  ])

  const openComplexDetail = useCallback((complexId: string) => {
    const currentSearch = new URLSearchParams(location.search)
    const currentComplexId = parseComplexIdQuery(currentSearch)
    const activeElement = document.activeElement
    if (activeElement instanceof HTMLElement) {
      detailOpenerRef.current = activeElement
      detailOpenerWasMarkerRef.current = activeElement.classList.contains(
        'housing-map-marker',
      )
    }
    detailOpenerComplexIdRef.current = complexId
    const internalState = currentComplexId.kind === 'valid'
      ? location.state
      : withDetailHistoryState(location.state)
    setSelectedComplexId(complexId)
    navigate({
      hash: location.hash,
      pathname: location.pathname,
      search: toSearchString(setComplexIdQuery(currentSearch, complexId)),
    }, {
      replace: currentComplexId.kind === 'valid',
      state: internalState,
    })
  }, [
    location.hash,
    location.pathname,
    location.search,
    location.state,
    navigate,
  ])

  const closeComplexDetail = useCallback(() => {
    if (isDetailHistoryState(location.state)) {
      navigate(-1)
      return
    }

    const nextSearch = clearComplexIdQuery(
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
    () => toNaverMapMarkers(
      mapResults.items,
      selectedComplexId,
      complexDetail.detail,
    ),
    [complexDetail.detail, mapResults.items, selectedComplexId],
  )

  return (
    <div className={complexDetail.status === 'closed'
      ? 'housing-explorer'
      : 'housing-explorer has-complex-detail'}>
      <aside className="housing-results" aria-label="공공임대주택 검색 결과">
        <header className="housing-results__header">
          <div>
            <p className="housing-results__eyebrow">지도 기반 탐색</p>
            <h1>공공임대주택</h1>
          </div>
          <span className="housing-results__count" aria-live="polite">
            {complexResults.items.length}곳
          </span>
        </header>

        <ViewportAction
          decision={viewportDecision}
          pending={pendingViewport}
          onApply={() => {
            if (viewport) {
              firstRequestStartedRef.current = true
              applyViewport(viewport)
            }
          }}
        />

        <div className="housing-results__tabs" role="tablist" aria-label="결과 종류">
          <button
            type="button"
            role="tab"
            aria-selected="true"
            className="is-active"
          >
            단지 목록
          </button>
          <button type="button" role="tab" aria-selected="false" disabled>
            공고 목록
          </button>
        </div>

        <ComplexResultContent
          state={complexResults}
          selectedComplexId={selectedComplexId}
          hoveredComplexId={hoveredComplexId}
          onSelect={openComplexDetail}
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
      </aside>

      <main className="housing-map-workspace">
        <NaverMap
          cameraTarget={detailMapTarget}
          markers={markers}
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
          onClose={closeComplexDetail}
          onRetry={() => setDetailRetryRevision((current) => current + 1)}
        />
      </main>
    </div>
  )
}

function ComplexDetailLayer({
  state,
  onClose,
  onRetry,
}: {
  state: ComplexDetailState
  onClose: () => void
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

function ViewportAction({
  decision,
  pending,
  onApply,
}: {
  decision: ReturnType<typeof evaluateViewportRequest> | null
  pending: boolean
  onApply: () => void
}) {
  if (decision && !decision.allowed) {
    return (
      <div className="housing-viewport-action housing-viewport-action--blocked" role="status">
        <span>{viewportGuidance(decision.reason)}</span>
      </div>
    )
  }

  if (!pending) {
    return null
  }

  return (
    <div className="housing-viewport-action">
      <span>지도를 움직였습니다.</span>
      <button type="button" onClick={onApply}>
        이 지역에서 다시 찾기
      </button>
    </div>
  )
}

function ComplexResultContent({
  state,
  selectedComplexId,
  hoveredComplexId,
  onSelect,
  onHover,
  onCardRef,
  onRetry,
}: {
  state: ComplexResultsState
  selectedComplexId: string | null
  hoveredComplexId: string | null
  onSelect: (complexId: string) => void
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

function withDetailHistoryState(state: unknown) {
  const currentState = isRecord(state) ? state : {}
  return { ...currentState, [DETAIL_HISTORY_STATE_KEY]: true }
}

function isDetailHistoryState(state: unknown) {
  return isRecord(state) && state[DETAIL_HISTORY_STATE_KEY] === true
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
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
  if (opener?.isConnected) {
    opener.focus()
    return
  }
  if (complexId === null) {
    return
  }
  if (openerWasMarker) {
    const marker = findComplexMarker(complexId)
    if (marker) {
      marker.focus()
      return
    }
  }
  focusComplexCard(cards.get(complexId))
}

function findComplexMarker(complexId: string) {
  return [...document.querySelectorAll<HTMLButtonElement>(
    '.housing-map-marker[data-complex-id]',
  )].find((marker) => marker.dataset.complexId === complexId)
}

function focusComplexCard(card: HTMLElement | undefined) {
  card?.querySelector<HTMLButtonElement>('button[aria-haspopup="dialog"]')
    ?.focus()
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

function viewportGuidance(reason: ViewportBlockReason) {
  if (reason === 'zoom-too-low') {
    return '단지를 불러오려면 지도를 조금 더 확대해 주세요.'
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
