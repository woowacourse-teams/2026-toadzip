import { useCallback, useEffect, useRef } from 'react'
import { useLocation, useNavigationType } from 'react-router'
import type { ComplexSearchFilters } from '../public-housing/api/publicHousingRepository.ts'
import {
  parseAnnouncementSearchFilters,
  parseComplexSearchFilters,
  searchFiltersSignature,
} from '../public-housing/filters/searchFilterLocation.ts'
import type { DetailLocationResult } from '../public-housing/navigation/detailLocation.ts'
import { type DetailEntryPoint, setAnalyticsPageActive, trackEvent } from './googleAnalytics.ts'
import { useAnalyticsConsent } from './useAnalyticsConsent'

interface HousingAnalyticsState {
  readonly detailLocation: DetailLocationResult
  readonly readyComplexId: string | null
  readonly readyAnnouncementId: string | null
}

interface DetailVisit {
  readonly key: string | null
  readonly entryPoint: DetailEntryPoint
  sent: boolean
}

export function useHousingAnalytics({
  detailLocation,
  readyComplexId,
  readyAnnouncementId,
}: HousingAnalyticsState) {
  const allowed = useAnalyticsConsent()
  const location = useLocation()
  const navigationType = useNavigationType()
  const visitRef = useRef<DetailVisit | null>(null)
  const pendingEntryRef = useRef<{ key: string; entryPoint: DetailEntryPoint } | null>(null)
  const kind = detailLocation.kind
  const id = kind === 'complex' ? detailLocation.complexId
    : kind === 'announcement' ? detailLocation.announcementId : null
  const targetKey = id === null ? null : `${kind}:${id}`

  useEffect(() => {
    if (!allowed) return
    let cancelled = false
    let activated = false
    // StrictMode's discarded setup must not enqueue a page view or clear a real queue.
    queueMicrotask(() => {
      if (cancelled) return
      activated = true
      setAnalyticsPageActive(true)
      trackEvent('page_view', {})
    })
    return () => {
      cancelled = true
      if (activated) setAnalyticsPageActive(false)
    }
  }, [allowed])

  useEffect(() => {
    if (visitRef.current === null || visitRef.current.key !== targetKey) {
      const pending = pendingEntryRef.current
      const entryPoint = pending?.key === targetKey ? pending.entryPoint
        : visitRef.current !== null && navigationType === 'POP' ? 'history' : 'direct'
      visitRef.current = { key: targetKey, entryPoint, sent: false }
    }
    pendingEntryRef.current = null
    const visit = visitRef.current
    if (!allowed) { visit.sent = false; return }
    let cancelled = false
    queueMicrotask(() => {
      if (cancelled || visit.sent || id === null) return
      if (kind === 'complex' && readyComplexId === id) {
        visit.sent = trackEvent('view_complex', {
          complex_id: id,
          entry_point: visit.entryPoint,
        })
      }
      if (kind === 'announcement' && readyAnnouncementId === id) {
        visit.sent = trackEvent('view_announcement', {
          announcement_id: id,
          entry_point: visit.entryPoint,
        })
      }
    })
    return () => { cancelled = true }
  }, [allowed, id, kind, location.key, navigationType, readyAnnouncementId, readyComplexId, targetKey])

  const prepareDetailVisit = useCallback((
    detailKind: 'complex' | 'announcement',
    detailId: string,
    entryPoint: DetailEntryPoint,
  ) => {
    const key = `${detailKind}:${detailId}`
    pendingEntryRef.current = key === targetKey ? null : { key, entryPoint }
  }, [targetKey])

  return prepareDetailVisit
}

export function trackAppliedFilters(
  target: 'complex' | 'announcement',
  currentSearch: URLSearchParams,
  nextSearch: URLSearchParams,
) {
  const parse = target === 'complex' ? parseComplexSearchFilters : parseAnnouncementSearchFilters
  const current = parse(currentSearch)
  const next: ComplexSearchFilters = parse(nextSearch)
  if (canonicalFilterSignature(current) === canonicalFilterSignature(next)) return

  const categories = [
    ['region', Boolean(next.regionCode)],
    ['rental', Boolean(next.rentalTypes?.length)],
    ['status', Boolean(next.applicationStatuses?.length)],
    ['agency', Boolean(next.agencyCodes?.length)],
    ['recruitment', Boolean(next.recruitmentTypes?.length)],
    ['deposit', next.minDeposit != null || next.maxDeposit != null],
    ['rent', next.minMonthlyRent != null || next.maxMonthlyRent != null],
    ['area', next.minExclusiveArea != null || next.maxExclusiveArea != null],
    ['built_year', next.builtYearFrom != null || next.builtYearTo != null],
  ] as const
  const active = categories.filter(([, enabled]) => enabled).map(([category]) => category)
  trackEvent('apply_filter', {
    filter_target: target,
    filter_types: active.length === 0 ? 'none' : active.join(','),
    filter_count: active.length,
  })
}

function canonicalFilterSignature(filters: ComplexSearchFilters) {
  return searchFiltersSignature({
    ...filters,
    rentalTypes: filters.rentalTypes?.toSorted(),
    applicationStatuses: filters.applicationStatuses?.toSorted(),
    agencyCodes: filters.agencyCodes?.toSorted(),
    recruitmentTypes: filters.recruitmentTypes?.toSorted(),
  })
}
