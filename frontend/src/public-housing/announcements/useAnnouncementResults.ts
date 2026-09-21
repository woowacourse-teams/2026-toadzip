import { useCallback, useEffect, useRef, useState } from 'react'
import type {
  AnnouncementSearchFilters,
  PublicHousingRepository,
} from '../api/publicHousingRepository.ts'
import { searchFiltersSignature } from '../filters/searchFilterLocation.ts'
import type { AnnouncementListItem } from '../model/publicHousing.ts'

const PAGE_SIZE = 20

export type AnnouncementRequestStatus =
  | 'idle'
  | 'loading'
  | 'loading-more'
  | 'ready'
  | 'error'

export interface AnnouncementResultsState {
  readonly errorMessage: string | null
  readonly hasNext: boolean
  readonly items: readonly AnnouncementListItem[]
  readonly nextCursor: string | null
  readonly status: AnnouncementRequestStatus
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
) {
  const [state, setState] = useState<AnnouncementResultsState>(INITIAL_STATE)
  const requestedFiltersKeyRef = useRef<string | null>(null)
  const requestRevisionRef = useRef(0)
  const firstPageAbortRef = useRef<AbortController | null>(null)
  const paginationAbortRef = useRef<AbortController | null>(null)

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
    requestedFiltersKeyRef.current = filtersKey
    setState((current) => ({
      ...current,
      errorMessage: null,
      hasNext: false,
      items: [],
      nextCursor: null,
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
        setState({
          errorMessage: null,
          hasNext: page.hasNext,
          items: page.items,
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
  }, [cancelInFlightRequests, filters, filtersKey, repository])

  const loadMore = useCallback(() => {
    if (
      !enabled
      || !state.hasNext
      || !state.nextCursor
      || state.status === 'loading-more'
    ) {
      return
    }

    paginationAbortRef.current?.abort()
    const controller = new AbortController()
    const revision = requestRevisionRef.current
    paginationAbortRef.current = controller
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
          errorMessage: null,
          hasNext: page.hasNext,
          items: appendUniqueAnnouncements(current.items, page.items),
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
  }, [enabled, filters, repository, state])

  useEffect(() => {
    if (!enabled) {
      const firstPageWasInFlight = cancelInFlightRequests(true)
      if (firstPageWasInFlight) {
        requestedFiltersKeyRef.current = null
      }
      return
    }
    if (requestedFiltersKeyRef.current === filtersKey) {
      return
    }
    loadFirstPage()
  }, [cancelInFlightRequests, enabled, filtersKey, loadFirstPage])

  useEffect(() => {
    return () => {
      cancelInFlightRequests()
    }
  }, [cancelInFlightRequests])

  const retry = state.items.length > 0 && state.nextCursor
    ? loadMore
    : loadFirstPage

  return { loadMore, retry, state }
}

function activeAnnouncementFilters(
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
