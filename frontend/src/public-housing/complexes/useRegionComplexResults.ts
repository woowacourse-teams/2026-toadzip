import { captureProductEvent } from '../../analytics/productAnalytics'
import { listRequestProperties } from '../analytics/useListMeasurement'
import { useCallback, useEffect, useRef, useState } from 'react'
import type { ComplexSearchFilters, PublicHousingRepository } from '../api/publicHousingRepository'
import { searchFiltersSignature } from '../filters/searchFilterLocation'
import type { ComplexListItem } from '../model/publicHousing'

export interface ComplexResultsState {
  readonly errorMessage: string | null
  readonly hasNext: boolean
  readonly items: readonly ComplexListItem[]
  readonly nextCursor: string | null
  readonly status: 'idle' | 'loading' | 'loading-more' | 'ready' | 'error'
  readonly totalCount: number | null
}

const EMPTY: ComplexResultsState = {
  errorMessage: null, hasNext: false, items: [], nextCursor: null,
  status: 'idle', totalCount: null,
}

export function useRegionComplexResults(
  repository: PublicHousingRepository,
  regionCode: string | null,
  filters: ComplexSearchFilters,
) {
  const { regionCode: _explicitRegion, ...listFilters } = filters
  const key = `${regionCode ?? ''}|${searchFiltersSignature(listFilters)}`
  const optionsRef = useRef(listFilters)
  optionsRef.current = listFilters
  const [result, setResult] = useState({ key, state: EMPTY })
  const state = regionCode === null ? EMPTY : result.key === key
    ? result.state : { ...EMPTY, status: 'loading' as const }
  const stateRef = useRef(state)
  stateRef.current = state
  const requestRef = useRef<AbortController | null>(null)
  const failedCursorRef = useRef<string | null>(null)

  const request = useCallback((cursor: string | null) => {
    if (regionCode === null) return
    requestRef.current?.abort()
    const controller = new AbortController()
    requestRef.current = controller
    failedCursorRef.current = cursor
    const measurement = cursor === null ? null : listRequestProperties('region_complex')
    const previousIds = new Set(stateRef.current.items.map(item => item.complexId))
    if (measurement) captureProductEvent('list_more_requested', measurement)
    setResult((current) => ({ key, state: {
      ...(cursor === null ? EMPTY : current.state),
      errorMessage: null, status: cursor === null ? 'loading' : 'loading-more',
    } }))
    repository.findComplexPage(null, cursor, 20, controller.signal, {
      ...optionsRef.current, regionCode,
    }).then((page) => {
      if (controller.signal.aborted) return
      if (measurement) captureProductEvent('list_more_succeeded', { ...measurement, appended_count: new Set(page.items.filter(item => !previousIds.has(item.complexId)).map(item => item.complexId)).size })
      setResult((current) => {
        const previous = cursor === null ? [] : current.state.items
        const ids = new Set(previous.map((item) => item.complexId))
        const items = [...previous, ...page.items.filter((item) => {
          if (ids.has(item.complexId)) return false
          ids.add(item.complexId)
          return true
        })]
        return { key, state: {
          errorMessage: null, hasNext: page.hasNext, items, nextCursor: page.nextCursor,
          status: 'ready', totalCount: page.hasNext ? null : items.length,
        } }
      })
      requestRef.current = null
    }).catch((error: unknown) => {
      if (controller.signal.aborted) return
      requestRef.current = null
      if (measurement) captureProductEvent('list_more_failed', measurement)
      setResult((current) => ({ key, state: {
        ...current.state, status: 'error',
        errorMessage: error instanceof Error ? error.message : '잠시 후 다시 시도해 주세요.',
      } }))
    })
  }, [key, regionCode, repository])

  useEffect(() => {
    request(null)
    return () => { requestRef.current?.abort(); requestRef.current = null }
  }, [request])

  const loadMore = useCallback(() => {
    const current = stateRef.current
    if (requestRef.current || !current.hasNext || current.status !== 'ready' || !current.nextCursor) return
    request(current.nextCursor)
  }, [request])
  const retry = useCallback(() => request(failedCursorRef.current), [request])
  return { state, loadMore, retry, key }
}
