import { useEffect, useMemo, useState } from 'react'
import { useConditions } from '../../user/conditions/ConditionsContext'
import { fromProfile, missingFields } from '../../user/conditions/conditionDraft'
import type { AnnouncementRef, ListConditionResult, ProfileField } from '../../user/conditions/conditionsModel'
import type { AnnouncementListItem } from '../model/publicHousing'
import { rentalTypeLabel } from '../presentation/rentalTypeLabel'

const EMPTY = new Map<string, ListConditionResult>()

/** 불러온 공고 목록의 내 조건 결과. 목록이 늘거나 내 조건을 저장하면 다시 계산한다. */
export function useConditionListResults(items: readonly AnnouncementListItem[]) {
  const { repository, revision, mode, hasProfile } = useConditions()
  const active = mode === 'member' && hasProfile
  const key = items.map((item) => item.announcementId).join(',')
  const refs = useMemo<AnnouncementRef[]>(() => items.map((item) => ({
    announcementId: item.announcementId,
    title: item.title,
    rentalType: rentalTypeLabel(item.rentalType) ?? item.rentalType,
    applicationStatus: item.applicationStatus,
    targets: [],
  })), [items])
  const [state, setState] = useState<{ key: string; revision: number; results: ReadonlyMap<string, ListConditionResult>; status: 'loading' | 'ready' | 'error' } | null>(null)

  useEffect(() => {
    if (!active || !refs.length) return
    const controller = new AbortController()
    repository.loadListResults(refs, controller.signal).then(
      (results) => { if (!controller.signal.aborted) setState({ key, revision, results, status: 'ready' }) },
      () => { if (!controller.signal.aborted) setState({ key, revision, results: EMPTY, status: 'error' }) },
    )
    return () => controller.abort()
  }, [active, key, refs, repository, revision])

  return useMemo(() => {
    if (!active) return { results: EMPTY, matchedIds: new Set<string>(), status: 'idle' as const }
    if (!items.length) return { results: EMPTY, matchedIds: new Set<string>(), status: 'ready' as const }
    const results = state?.results ?? EMPTY
    // 지금 보이는 공고 안에서만 센다. 새 목록의 비교가 끝나기 전에는 불러오는 중으로 둔다.
    const matchedIds = new Set(items.filter((item) => results.get(item.announcementId)?.generalMatched).map((item) => item.announcementId))
    const status = state && state.key === key && state.revision === revision ? state.status : 'loading' as const
    return { results, matchedIds, status }
  }, [active, items, key, revision, state])
}

/** 저장된 내 조건에서 아직 값이 없는 항목 */
export function useMissingConditionFields(): ProfileField[] {
  const { stored, hasProfile } = useConditions()
  return useMemo(() => hasProfile && stored ? missingFields(fromProfile(stored.profile)) : [], [hasProfile, stored])
}
