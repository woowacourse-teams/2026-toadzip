import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import NaverMap, {
  type NaverMapMarker,
} from '../maps/naver/NaverMap.tsx'
import { publicHousingRepository } from './api/publicHousingRepository.ts'
import type { PublicHousingRepository } from './api/publicHousingRepository.ts'
import {
  HousingComplexCard,
  type HousingComplexCardData,
} from './components/HousingComplexCard.tsx'
import {
  evaluateViewportRequest,
  type ViewportBlockReason,
  type ViewportSnapshot,
} from './map/viewportPolicy.ts'
import type {
  ComplexListItem,
  MapBounds,
  MapComplex,
} from './model/publicHousing.ts'

const PAGE_SIZE = 20

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

export function PublicHousingExplorer({
  repository = publicHousingRepository,
}: PublicHousingExplorerProps) {
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
  const firstRequestStartedRef = useRef(false)
  const requestRevisionRef = useRef(0)
  const searchAbortRef = useRef<AbortController | null>(null)
  const paginationAbortRef = useRef<AbortController | null>(null)

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
  const markers = useMemo(
    () => toNaverMapMarkers(mapResults.items, selectedComplexId),
    [mapResults.items, selectedComplexId],
  )

  return (
    <div className="housing-explorer">
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
          onSelect={setSelectedComplexId}
          onHover={setHoveredComplexId}
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
          markers={markers}
          onMarkerSelect={setSelectedComplexId}
          onViewportChange={handleViewportChange}
        />
        {mapResults.status === 'error' && (
          <div className="housing-map-notice" role="alert">
            <strong>단지 마커를 갱신하지 못했습니다.</strong>
            <span>{mapResults.errorMessage}</span>
          </div>
        )}
      </main>
    </div>
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
  onRetry,
}: {
  state: ComplexResultsState
  selectedComplexId: string | null
  hoveredComplexId: string | null
  onSelect: (complexId: string) => void
  onHover: (complexId: string | null) => void
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
): NaverMapMarker[] {
  return complexes.map((complex) => ({
    id: complex.complexId,
    latitude: complex.latitude,
    longitude: complex.longitude,
    name: complex.name ?? '단지명 정보 확인 중',
    selected: complex.complexId === selectedComplexId,
  }))
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
