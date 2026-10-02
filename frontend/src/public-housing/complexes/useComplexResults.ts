import { useCallback, useEffect, useRef, useState } from 'react'
import type { ComplexSearchFilters, PublicHousingRepository } from '../api/publicHousingRepository.ts'
import { searchFiltersSignature } from '../filters/searchFilterLocation.ts'
import type { AppliedHousingMapResult } from '../map/useHousingMapResults.ts'
import { createBoundsSignature, evaluateViewportRequest, type ViewportSnapshot } from '../map/viewportPolicy.ts'
import type { ComplexListItem, MapBounds } from '../model/publicHousing.ts'

const PAGE_SIZE = 20

type ComplexRequestStatus = 'idle' | 'loading' | 'loading-more' | 'ready' | 'error'

export interface ComplexResultsState {
  readonly errorMessage: string | null
  readonly hasNext: boolean
  readonly items: readonly ComplexListItem[]
  readonly nextCursor: string | null
  readonly status: ComplexRequestStatus
  readonly totalCount: number | null
}

interface AppliedViewport {
  readonly bounds: MapBounds
  readonly options: ComplexSearchFilters
  readonly signature: string
}

const INITIAL_STATE: ComplexResultsState = {
  errorMessage: null,
  hasNext: false,
  items: [],
  nextCursor: null,
  status: 'idle',
  totalCount: null,
}

interface ComplexResultsOptions {
  readonly filters: ComplexSearchFilters
  readonly appliedMap: AppliedHousingMapResult | null
  readonly paginationPaused: boolean
  readonly onViewportApplied?: () => void
}

export function useComplexResults(
  repository: Pick<PublicHousingRepository, 'findComplexPage'>,
  { filters, appliedMap, paginationPaused, onViewportApplied }: ComplexResultsOptions,
) {
  const [state, setState] = useState<ComplexResultsState>(INITIAL_STATE)
  const [appliedViewport, setAppliedViewport] = useState<AppliedViewport | null>(null)
  const appliedViewportRef = useRef<AppliedViewport | null>(null)
  const failedViewportRef = useRef<ViewportSnapshot | null>(null)
  const failedViewportOptionsRef = useRef<ComplexSearchFilters>({})
  const failedPaginationCursorRef = useRef<string | null>(null)
  const pendingViewportSignatureRef = useRef<string | null>(null)
  const requestRevisionRef = useRef(0)
  const searchAbortRef = useRef<AbortController | null>(null)
  const paginationAbortRef = useRef<AbortController | null>(null)

  // Map-result effects need the accepted request immediately, before the next render.
  const hasAppliedViewport = useCallback(() => appliedViewportRef.current !== null, [])

  const cancel = useCallback(() => {
    searchAbortRef.current?.abort()
    paginationAbortRef.current?.abort()
    searchAbortRef.current = null
    paginationAbortRef.current = null
    requestRevisionRef.current += 1
    pendingViewportSignatureRef.current = null
    const restoredStatus = appliedViewportRef.current === null ? 'idle' : 'ready'
    setState((current) => current.status === 'loading' || current.status === 'loading-more'
      ? { ...current, errorMessage: null, status: restoredStatus }
      : current)
  }, [])

  const request = useCallback((
    nextViewport: ViewportSnapshot,
    force = false,
    options: ComplexSearchFilters = filters,
  ) => {
    const decision = evaluateViewportRequest(nextViewport)
    if (!decision.allowed) {
      return
    }
    const boundsSignature = decision.boundsSignature
    const signature = `${boundsSignature}|${searchFiltersSignature(options)}`
    const totalCount = appliedMap?.result.representation === 'INDIVIDUAL'
      && complexListRequestKey(appliedMap.query.bounds, appliedMap.query.filters ?? {})
        === complexListRequestKey(nextViewport.bounds, options)
      ? new Set(appliedMap.result.nodes.map((item) => item.complexId)).size
      : null
    if (!force && pendingViewportSignatureRef.current === signature) {
      return
    }
    if (!force && appliedViewportRef.current?.signature === signature) {
      setState((current) => ({ ...current, errorMessage: null, status: 'ready' }))
      return
    }

    searchAbortRef.current?.abort()
    paginationAbortRef.current?.abort()
    const controller = new AbortController()
    const revision = requestRevisionRef.current + 1
    requestRevisionRef.current = revision
    searchAbortRef.current = controller
    pendingViewportSignatureRef.current = signature
    failedPaginationCursorRef.current = null
    setState((current) => ({
      ...current,
      errorMessage: null,
      status: 'loading',
    }))

    findComplexPage(
      repository,
      nextViewport.bounds,
      null,
      PAGE_SIZE,
      controller.signal,
      options,
    )
      .then((page) => {
        if (requestRevisionRef.current !== revision) {
          return
        }
        const applied = { bounds: nextViewport.bounds, options, signature }
        appliedViewportRef.current = applied
        failedViewportRef.current = null
        pendingViewportSignatureRef.current = null
        setAppliedViewport(applied)
        setState({
          errorMessage: null,
          hasNext: page.hasNext,
          items: page.items,
          nextCursor: page.nextCursor,
          status: 'ready',
          totalCount: page.hasNext ? totalCount : page.items.length,
        })
        onViewportApplied?.()
      })
      .catch((error: unknown) => {
        if (isAbortError(error) || requestRevisionRef.current !== revision) {
          return
        }
        failedViewportRef.current = nextViewport
        failedViewportOptionsRef.current = options
        pendingViewportSignatureRef.current = null
        setState((current) => ({
          ...current,
          errorMessage: requestErrorMessage(error),
          status: 'error',
        }))
      })
  }, [appliedMap, filters, onViewportApplied, repository])

  const loadMore = useCallback((retryFailedCursor = false) => {
    const cursor = retryFailedCursor
      ? failedPaginationCursorRef.current
      : state.nextCursor
    if (
      paginationPaused ||
      !appliedViewport ||
      !state.hasNext ||
      !cursor ||
      (!retryFailedCursor && state.status !== 'ready') ||
      (retryFailedCursor && state.status !== 'error')
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

    findComplexPage(
      repository,
      appliedViewport.bounds,
      cursor,
      PAGE_SIZE,
      controller.signal,
      appliedViewport.options,
    )
      .then((page) => {
        if (requestRevisionRef.current !== revision) {
          return
        }
        setState((current) => {
          const items = appendUniqueComplexes(current.items, page.items)
          return {
            errorMessage: null,
            hasNext: page.hasNext,
            items,
            nextCursor: page.nextCursor,
            status: 'ready',
            totalCount: page.hasNext ? current.totalCount : items.length,
          }
        })
        failedPaginationCursorRef.current = null
      })
      .catch((error: unknown) => {
        if (isAbortError(error) || requestRevisionRef.current !== revision) {
          return
        }
        failedPaginationCursorRef.current = cursor
        setState((current) => ({
          ...current,
          errorMessage: requestErrorMessage(error),
          status: 'error',
        }))
      })
  }, [appliedViewport, state, repository,
    paginationPaused])

  const retry = useCallback(() => {
    if (failedPaginationCursorRef.current) {
      loadMore(true)
      return
    }
    const failedViewport = failedViewportRef.current
    if (failedViewport) {
      request(failedViewport, true, failedViewportOptionsRef.current)
    }
  }, [request, loadMore])

  useEffect(() => {
    return () => {
      searchAbortRef.current?.abort()
      paginationAbortRef.current?.abort()
      requestRevisionRef.current += 1
    }
  }, [])

  return { cancel, hasAppliedViewport, loadMore, request, retry, state }
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

function findComplexPage(
  repository: Pick<PublicHousingRepository, 'findComplexPage'>,
  bounds: MapBounds,
  cursor: string | null,
  size: number,
  signal: AbortSignal,
  options: ComplexSearchFilters,
) {
  if (Object.keys(options).length === 0) {
    return repository.findComplexPage(bounds, cursor, size, signal)
  }
  return repository.findComplexPage(bounds, cursor, size, signal, options)
}

export function complexListRequestKey(
  bounds: MapBounds,
  filters: ComplexSearchFilters,
) {
  const boundsSignature = createBoundsSignature(bounds)
  if (boundsSignature === null) {
    return null
  }
  return `${boundsSignature}|${searchFiltersSignature(filters)}`
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
