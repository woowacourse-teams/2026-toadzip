import { useCallback, useEffect, useRef, useState } from 'react'
import type { AnnouncementSearchFilters, ComplexSearchFilters, PublicHousingRepository } from '../api/publicHousingRepository.ts'
import { activeAnnouncementFilters, type PreparedAnnouncementPage } from '../announcements/useAnnouncementResults.ts'
import type { ComplexListItem, ComplexSearchSnapshot, SearchScope } from '../model/publicHousing.ts'
import { searchFiltersSignature } from '../filters/searchFilterLocation.ts'
import { searchScopeSignature } from '../navigation/searchScopeLocation.ts'

const PAGE_SIZE = 20

export interface ComplexResultsState {
  readonly errorMessage: string | null
  readonly hasNext: boolean
  readonly items: readonly ComplexListItem[]
  readonly nextCursor: string | null
  readonly status: 'idle' | 'loading' | 'loading-more' | 'ready' | 'error'
  readonly totalCount: number | null
}

interface SearchResultsState extends ComplexResultsState {
  readonly snapshot: ComplexSearchSnapshot | null
  readonly scope: SearchScope | null
  readonly signature: string
  readonly filtersKey: string
  readonly listRevision: number
  readonly restored: boolean
  readonly announcementPage?: PreparedAnnouncementPage
}

const INITIAL: SearchResultsState = {
  errorMessage: null, hasNext: false, items: [], nextCursor: null,
  status: 'idle', totalCount: null, snapshot: null, scope: null,
  signature: '', filtersKey: '', listRevision: 0, restored: false,
}

/** Camera changes never enter this hook: only committed search scopes do. */
export function useComplexSearchResults(
  repository: PublicHousingRepository,
  scope: SearchScope | null,
  filters: ComplexSearchFilters,
  options: { preserveList: boolean; restore: boolean; revision: number },
  announcementFilters?: AnnouncementSearchFilters,
) {
  const [state, setState] = useState<SearchResultsState>(INITIAL)
  const stateRef = useRef(state)
  stateRef.current = state
  const requestRef = useRef<AbortController | null>(null)
  const paginationRef = useRef<AbortController | null>(null)
  const generationRef = useRef(0)
  const cacheRef = useRef(new Map<string, SearchResultsState>())
  const [retryRevision, setRetryRevision] = useState(0)
  const filtersKey = searchFiltersSignature(filters)
  const announcementKey = announcementFilters ? searchFiltersSignature(announcementFilters) : ''
  const signature = scope ? `${searchScopeSignature(scope)}|${filtersKey}|${announcementKey}` : ''
  const inputsRef = useRef({ scope, filters, options, announcementFilters, announcementKey })
  inputsRef.current = { scope, filters, options, announcementFilters, announcementKey }

  useEffect(() => {
    if (state.status === 'ready') {
      cacheRef.current.set(state.signature, state)
      if (cacheRef.current.size > 30) {
        const oldest = cacheRef.current.keys().next().value
        if (oldest !== undefined) cacheRef.current.delete(oldest)
      }
    }
  }, [state])

  useEffect(() => {
    const { scope: nextScope, filters: nextFilters, options: requestOptions, announcementFilters: nextAnnouncementFilters, announcementKey: nextAnnouncementKey } = inputsRef.current
    if (!nextScope) return
    requestRef.current?.abort()
    paginationRef.current?.abort()
    const generation = ++generationRef.current
    const cached = requestOptions.restore ? cacheRef.current.get(signature) : undefined
    if (cached) {
      setState({ ...cached, listRevision: stateRef.current.listRevision + 1, restored: true })
      return
    }
    const controller = new AbortController()
    requestRef.current = controller
    setState((current) => ({ ...current, errorMessage: null, status: 'loading' }))
    const request = repository.findComplexSearch
      ? repository.findComplexSearch(nextScope, PAGE_SIZE, controller.signal, nextFilters)
      : Promise.reject(new Error('검색 범위 API를 지원하지 않는 저장소입니다.'))
    const announcements = nextAnnouncementFilters ? repository.findAnnouncementPage(null, PAGE_SIZE, controller.signal,
      activeAnnouncementFilters({ ...nextAnnouncementFilters, scope: nextScope,
        regionCode: nextScope.mode === 'region' ? nextScope.regionCode : null })) : Promise.resolve(undefined)
    Promise.all([request, announcements]).then(([snapshot, announcementPage]) => {
      if (controller.signal.aborted || generation !== generationRef.current) return
      setState((current) => {
        const onlyAnnouncementsChanged = current.signature !== signature
          && searchScopeSignature(current.scope) === searchScopeSignature(nextScope)
          && current.filtersKey === filtersKey
        const keepList = (onlyAnnouncementsChanged || requestOptions.preserveList && !requestOptions.restore)
          && current.filtersKey === filtersKey
          && current.snapshot !== null
          && sameIds(current.snapshot.complexIds, snapshot.complexIds)
        return {
          errorMessage: null, status: 'ready', snapshot, scope: nextScope, signature, filtersKey,
          items: keepList ? current.items : snapshot.page.items,
          hasNext: keepList ? current.hasNext : snapshot.page.hasNext,
          nextCursor: keepList ? current.nextCursor : snapshot.page.nextCursor,
          totalCount: snapshot.totalCount,
          listRevision: current.listRevision + (keepList ? 0 : 1), restored: false,
          announcementPage: announcementPage ? {
            page: announcementPage, filtersKey: `${nextAnnouncementKey}|${searchScopeSignature(nextScope)}`,
          } : undefined,
        }
      })
    }).catch((error: unknown) => {
      if (controller.signal.aborted || generation !== generationRef.current) return
      setState((current) => ({ ...current, status: 'error', errorMessage: message(error) }))
    })
    return () => controller.abort()
  }, [repository, signature, filtersKey, options.revision, retryRevision])

  const loadMore = useCallback(() => {
    const current = stateRef.current
    if (!current.scope || !current.hasNext || !current.nextCursor
      || current.signature !== signature || current.status !== 'ready') return
    const appliedScope = current.scope
    const bounds = appliedScope.mode === 'area' ? appliedScope.bounds : current.snapshot?.bounds
    // The REGION endpoint ignores bounds; zero-coordinate regions still paginate.
    const queryBounds = bounds ?? { southWestLat: -90, southWestLng: -180, northEastLat: 90, northEastLng: 180 }
    const controller = new AbortController()
    paginationRef.current?.abort()
    paginationRef.current = controller
    const generation = generationRef.current
    setState((value) => ({ ...value, status: 'loading-more', errorMessage: null }))
    repository.findComplexPage(queryBounds, current.nextCursor, PAGE_SIZE, controller.signal,
      { ...inputsRef.current.filters, scope: appliedScope }).then((page) => {
      if (controller.signal.aborted || generation !== generationRef.current) return
      setState((value) => {
        const ids = new Set(value.items.map((item) => item.complexId))
        return { ...value, status: 'ready', nextCursor: page.nextCursor, hasNext: page.hasNext,
          items: [...value.items, ...page.items.filter((item) => !ids.has(item.complexId))] }
      })
    }).catch((error: unknown) => {
      if (controller.signal.aborted || generation !== generationRef.current) return
      setState((value) => ({ ...value, status: 'ready', errorMessage: message(error) }))
    })
  }, [repository, signature])

  const cancel = useCallback(() => {
    requestRef.current?.abort()
    paginationRef.current?.abort()
    generationRef.current += 1
    setState((current) => ({ ...current, status: current.snapshot ? 'ready' : 'idle' }))
  }, [])

  useEffect(() => () => {
    requestRef.current?.abort()
    paginationRef.current?.abort()
  }, [])

  return { state, signature, loadMore, cancel, retry: () => {
    cacheRef.current.delete(signature)
    setRetryRevision((value) => value + 1)
  } }
}

function sameIds(left: readonly string[], right: readonly string[]) {
  const ids = new Set(right)
  return left.length === right.length && left.every((id) => ids.has(id))
}

function message(error: unknown) {
  return error instanceof Error ? error.message : '검색 결과를 불러오지 못했습니다. 다시 시도해 주세요.'
}
