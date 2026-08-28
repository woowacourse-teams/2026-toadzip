import { useCallback, useEffect, useRef, useState } from 'react'
import type { PublicHousingRepository } from '../api/publicHousingRepository.ts'
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
) {
  const [state, setState] = useState<AnnouncementResultsState>(INITIAL_STATE)
  const initialRequestStartedRef = useRef(false)
  const requestRevisionRef = useRef(0)
  const firstPageAbortRef = useRef<AbortController | null>(null)
  const paginationAbortRef = useRef<AbortController | null>(null)

  const loadFirstPage = useCallback(() => {
    firstPageAbortRef.current?.abort()
    paginationAbortRef.current?.abort()
    const controller = new AbortController()
    const revision = requestRevisionRef.current + 1
    requestRevisionRef.current = revision
    firstPageAbortRef.current = controller
    setState((current) => ({
      ...current,
      errorMessage: null,
      hasNext: false,
      nextCursor: null,
      status: 'loading',
    }))

    repository
      .findAnnouncementPage(null, PAGE_SIZE, controller.signal)
      .then((page) => {
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
        if (isAbortError(error) || requestRevisionRef.current !== revision) {
          return
        }
        setState((current) => ({
          ...current,
          errorMessage: requestErrorMessage(error),
          status: 'error',
        }))
      })
  }, [repository])

  const loadMore = useCallback(() => {
    if (
      !state.hasNext ||
      !state.nextCursor ||
      state.status === 'loading-more'
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

    repository
      .findAnnouncementPage(state.nextCursor, PAGE_SIZE, controller.signal)
      .then((page) => {
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
        if (isAbortError(error) || requestRevisionRef.current !== revision) {
          return
        }
        setState((current) => ({
          ...current,
          errorMessage: requestErrorMessage(error),
          status: 'error',
        }))
      })
  }, [repository, state])

  useEffect(() => {
    if (!enabled || initialRequestStartedRef.current) {
      return
    }
    initialRequestStartedRef.current = true
    loadFirstPage()
  }, [enabled, loadFirstPage])

  useEffect(() => {
    return () => {
      firstPageAbortRef.current?.abort()
      paginationAbortRef.current?.abort()
    }
  }, [])

  const retry = state.items.length > 0 && state.nextCursor
    ? loadMore
    : loadFirstPage

  return { loadMore, retry, state }
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
