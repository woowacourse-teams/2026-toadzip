import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import type {
  AnnouncementSearchFilters,
  PublicHousingRepository,
} from '../api/publicHousingRepository.ts'
import { searchFiltersSignature } from '../filters/searchFilterLocation.ts'
import type { AnnouncementListItem, AnnouncementPage } from '../model/publicHousing.ts'
import { searchScopeSignature } from '../navigation/searchScopeLocation.ts'

const PAGE_SIZE = 20
const MAX_CACHED_SEARCHES = 30

export type AnnouncementRequestStatus =
  | 'idle'
  | 'loading'
  | 'loading-more'
  | 'ready'
  | 'error'

export interface AnnouncementResultsState {
  readonly totalCount?: number
  readonly appliedFiltersKey?: string
  readonly restored?: boolean
  readonly errorMessage: string | null
  readonly hasNext: boolean
  readonly items: readonly AnnouncementListItem[]
  readonly nextCursor: string | null
  readonly status: AnnouncementRequestStatus
}

export interface PreparedAnnouncementPage {
  readonly filtersKey: string
  readonly page: AnnouncementPage
  readonly restore?: boolean
}

interface CachedAnnouncementResultsState extends AnnouncementResultsState {
  readonly requestKey?: string
}

const INITIAL_STATE: AnnouncementResultsState = {
  errorMessage: null,
  hasNext: false,
  items: [],
  nextCursor: null,
  status: 'idle',
}

export function useAnnouncementResults(
  repository: PublicHousingRepository,
  enabled: boolean,
  filters: AnnouncementSearchFilters = {},
  filtersKey = searchFiltersSignature(filters),
  preparedPage?: PreparedAnnouncementPage,
) {
  const requestKey = `${filtersKey}|${searchScopeSignature(filters.scope)}`
  const [state, setState] = useState<CachedAnnouncementResultsState>(INITIAL_STATE)
  const cacheRef = useRef(new Map<string, CachedAnnouncementResultsState>())
  const appliedPreparedPageRef = useRef<AnnouncementPage | null>(null)
  const requestedFiltersKeyRef = useRef<string | null>(null)
  const appliedRequestKeyRef = useRef<string | null>(null)
  const requestRevisionRef = useRef(0)
  const firstPageAbortRef = useRef<AbortController | null>(null)
  const paginationAbortRef = useRef<AbortController | null>(null)
  const observedViewCountsRef = useRef(new Map<string, number>())
  const retryFirstPageRef = useRef(true)

  useEffect(() => {
    if (state.status !== 'ready' || state.requestKey === undefined) return
    cacheRef.current.delete(state.requestKey)
    cacheRef.current.set(state.requestKey, state)
    if (cacheRef.current.size > MAX_CACHED_SEARCHES) {
      const oldest = cacheRef.current.keys().next().value
      if (oldest !== undefined) cacheRef.current.delete(oldest)
    }
  }, [state])

  const mergeViewCounts = useCallback((items: readonly AnnouncementListItem[]) => (
    items.map((item) => {
      const viewCount = Math.max(item.viewCount, observedViewCountsRef.current.get(item.announcementId) ?? 0)
      return viewCount === item.viewCount ? item : { ...item, viewCount, raw: { ...item.raw, viewCount } }
    })
  ), [])

  const updateViewCount = useCallback((announcementId: string, viewCount: number) => {
    const previous = observedViewCountsRef.current.get(announcementId) ?? 0
    observedViewCountsRef.current.set(announcementId, Math.max(previous, viewCount))
    setState((current) => ({ ...current, items: mergeViewCounts(current.items) }))
  }, [mergeViewCounts])

  const cancelInFlightRequests = useCallback((restorePaginationStatus = false) => {
    const firstPageController = firstPageAbortRef.current
    const paginationController = paginationAbortRef.current
    if (firstPageController === null && paginationController === null) {
      return false
    }

    requestRevisionRef.current += 1
    firstPageController?.abort()
    paginationController?.abort()
    firstPageAbortRef.current = null
    paginationAbortRef.current = null
    if (restorePaginationStatus && paginationController !== null) {
      setState((current) => current.status === 'loading-more'
        ? { ...current, status: 'ready' }
        : current)
    }
    return firstPageController !== null
  }, [])

  const loadFirstPage = useCallback(() => {
    cancelInFlightRequests()
    const controller = new AbortController()
    const revision = requestRevisionRef.current + 1
    requestRevisionRef.current = revision
    firstPageAbortRef.current = controller
    requestedFiltersKeyRef.current = requestKey
    retryFirstPageRef.current = true
    setState((current) => ({
      ...current,
      errorMessage: null,
      status: 'loading',
    }))

    const request = repository.findAnnouncementPage(
      null,
      PAGE_SIZE,
      controller.signal,
      activeAnnouncementFilters(filters),
    )

    request
      .then((page) => {
        if (firstPageAbortRef.current === controller) {
          firstPageAbortRef.current = null
        }
        if (requestRevisionRef.current !== revision) {
          return
        }
        appliedRequestKeyRef.current = requestKey
        setState({
          requestKey,
          restored: false,
          totalCount: page.totalCount,
          appliedFiltersKey: filtersKey,
          errorMessage: null,
          hasNext: page.hasNext,
          items: mergeViewCounts(page.items),
          nextCursor: page.nextCursor,
          status: 'ready',
        })
      })
      .catch((error: unknown) => {
        if (firstPageAbortRef.current === controller) {
          firstPageAbortRef.current = null
        }
        if (isAbortError(error) || requestRevisionRef.current !== revision) {
          return
        }
        setState((current) => ({
          ...current,
          errorMessage: requestErrorMessage(error),
          status: 'error',
        }))
      })
  }, [cancelInFlightRequests, filters, filtersKey, mergeViewCounts, repository, requestKey])

  const loadMore = useCallback(() => {
    if (
      !enabled
      || !state.hasNext
      || !state.nextCursor
      || state.status === 'loading-more'
      || state.status === 'loading'
      || requestedFiltersKeyRef.current !== requestKey
      || appliedRequestKeyRef.current !== requestKey
      || paginationAbortRef.current !== null
    ) {
      return
    }

    const controller = new AbortController()
    const revision = requestRevisionRef.current
    paginationAbortRef.current = controller
    retryFirstPageRef.current = false
    setState((current) => ({
      ...current,
      errorMessage: null,
      status: 'loading-more',
    }))

    const request = repository.findAnnouncementPage(
      state.nextCursor,
      PAGE_SIZE,
      controller.signal,
      activeAnnouncementFilters(filters),
    )

    request
      .then((page) => {
        if (paginationAbortRef.current === controller) {
          paginationAbortRef.current = null
        }
        if (requestRevisionRef.current !== revision) {
          return
        }
        setState((current) => ({
          requestKey,
          restored: current.restored,
          totalCount: page.totalCount ?? current.totalCount,
          appliedFiltersKey: current.appliedFiltersKey,
          errorMessage: null,
          hasNext: page.hasNext,
          items: mergeViewCounts(appendUniqueAnnouncements(current.items, page.items)),
          nextCursor: page.nextCursor,
          status: 'ready',
        }))
      })
      .catch((error: unknown) => {
        if (paginationAbortRef.current === controller) {
          paginationAbortRef.current = null
        }
        if (isAbortError(error) || requestRevisionRef.current !== revision) {
          return
        }
        setState((current) => ({
          ...current,
          errorMessage: requestErrorMessage(error),
          status: 'error',
        }))
      })
  }, [enabled, filters, mergeViewCounts, repository, requestKey, state])

  // Apply an atomic map/list response before paint so the old announcement
  // list cannot appear alongside the newly committed map scope.
  useLayoutEffect(() => {
    if (!enabled || preparedPage?.filtersKey !== filtersKey) return
    if (requestedFiltersKeyRef.current === requestKey
      && appliedPreparedPageRef.current === preparedPage.page) return
    cancelInFlightRequests()
    requestedFiltersKeyRef.current = requestKey
    appliedRequestKeyRef.current = requestKey
    appliedPreparedPageRef.current = preparedPage.page
    retryFirstPageRef.current = true
    const cached = preparedPage.restore ? cacheRef.current.get(requestKey) : undefined
    if (cached !== undefined) {
      setState({
        ...cached,
        items: mergeViewCounts(cached.items),
        errorMessage: null,
        status: 'ready',
        restored: true,
      })
      return
    }
    setState({
      requestKey,
      restored: false,
      totalCount: preparedPage.page.totalCount,
      appliedFiltersKey: filtersKey,
      errorMessage: null,
      hasNext: preparedPage.page.hasNext,
      items: mergeViewCounts(preparedPage.page.items),
      nextCursor: preparedPage.page.nextCursor,
      status: 'ready',
    })
  }, [cancelInFlightRequests, enabled, filtersKey, mergeViewCounts, preparedPage, requestKey])

  useEffect(() => {
    if (!enabled) {
      const firstPageWasInFlight = cancelInFlightRequests(true)
      if (firstPageWasInFlight) {
        requestedFiltersKeyRef.current = null
      }
      return
    }
    if (requestedFiltersKeyRef.current === requestKey) return
    loadFirstPage()
  }, [cancelInFlightRequests, enabled, loadFirstPage, requestKey])

  useEffect(() => {
    return () => {
      cancelInFlightRequests()
    }
  }, [cancelInFlightRequests])

  const retry = retryFirstPageRef.current ? loadFirstPage : loadMore

  return { loadMore, retry, state, updateViewCount }
}

export function activeAnnouncementFilters(
  filters: AnnouncementSearchFilters,
): AnnouncementSearchFilters {
  const selectedStatuses = filters.applicationStatuses?.filter(
    (status) => status !== 'CLOSED',
  )

  // 서버가 페이지를 나누기 전에 마감 공고를 제외한다.
  return {
    ...filters,
    applicationStatuses: selectedStatuses?.length
      ? selectedStatuses
      : ['BEFORE_APPLICATION', 'APPLYING'],
  }
}

function appendUniqueAnnouncements(
  current: readonly AnnouncementListItem[],
  next: readonly AnnouncementListItem[],
) {
  const knownIds = new Set(current.map((announcement) => (
    announcement.announcementId
  )))
  return [
    ...current,
    ...next.filter((announcement) => !knownIds.has(
      announcement.announcementId,
    )),
  ]
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
