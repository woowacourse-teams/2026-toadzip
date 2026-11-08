import { useCallback, useEffect, useMemo, useState } from 'react'
import { useConditions } from '../../user/conditions/ConditionsContext'
import { fromProfile, missingFields } from '../../user/conditions/conditionDraft'
import type { AnnouncementRef, ListConditionResult, ProfileField } from '../../user/conditions/conditionsModel'
import type { AnnouncementListItem } from '../model/publicHousing'
import { rentalTypeLabel } from '../presentation/rentalTypeLabel'

const EMPTY = new Map<string, ListConditionResult>()

/** Loaded notices only. The selected region scopes eligible options, not the whole-notice decision. */
export function useConditionListResults(items: readonly AnnouncementListItem[], region?: string | null) {
  const { repository, revision, mode, hasProfile, sessionKey, stored } = useConditions()
  const active = mode === 'member' && hasProfile
  const key = `${sessionKey ?? ''}:${stored?.version ?? 0}:${region ?? ''}:${items.map((item) => item.announcementId).join(',')}`
  const refs = useMemo<AnnouncementRef[]>(() => items.map((item) => ({
    announcementId: item.announcementId, title: item.title, rentalType: rentalTypeLabel(item.rentalType) ?? item.rentalType,
    applicationStatus: item.applicationStatus, targets: [], region,
  })), [items, region])
  const [state, setState] = useState<{ key: string; revision: number; results: ReadonlyMap<string, ListConditionResult>; status: 'ready' | 'error' } | null>(null)
  const [attempt, setAttempt] = useState(0)
  const retry = useCallback(() => setAttempt((value) => value + 1), [])

  useEffect(() => {
    if (!active || !refs.length) return
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout>
    const refresh = async () => {
      try {
        const results = await repository.loadListResults(refs, controller.signal)
        if (controller.signal.aborted) return
        setState({ key, revision, results, status: 'ready' })
        const pending = [...results.values()].some((item) => item.freshness === 'RECHECKING')
        timer = setTimeout(() => { void refresh() }, pending ? 3000 : 30_000)
      } catch {
        if (!controller.signal.aborted) setState({ key, revision, results: EMPTY, status: 'error' })
      }
    }
    setState(null)
    void refresh()
    return () => { controller.abort(); clearTimeout(timer) }
  }, [active, key, refs, repository, revision, attempt])

  return useMemo(() => {
    if (!active) return { results: EMPTY, matchedIds: new Set<string>(), status: 'idle' as const, rechecking: false, retry }
    if (!items.length) return { results: EMPTY, matchedIds: new Set<string>(), status: 'ready' as const, rechecking: false, retry }
    const current = state?.key === key && state.revision === revision ? state : null
    const results = current?.results ?? EMPTY
    const matchedIds = new Set(items.filter((item) => results.get(item.announcementId)?.eligibleInRegion).map((item) => item.announcementId))
    return { results, matchedIds, status: current?.status ?? 'loading' as const,
      rechecking: [...results.values()].some((item) => item.freshness === 'RECHECKING'), retry }
  }, [active, items, key, revision, state, retry])
}

export function useMissingConditionFields(): ProfileField[] {
  const { stored, hasProfile } = useConditions()
  return useMemo(() => hasProfile && stored ? missingFields(fromProfile(stored.profile)) : [], [hasProfile, stored])
}
