import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useConditions } from '../../user/conditions/ConditionsContext'
import { fromProfile, missingFields } from '../../user/conditions/conditionDraft'
import type { AnnouncementRef, ListConditionResult, ProfileField } from '../../user/conditions/conditionsModel'
import type { AnnouncementListItem } from '../model/publicHousing'
import { rentalTypeLabel } from '../presentation/rentalTypeLabel'

const EMPTY = new Map<string, ListConditionResult>()

/**
 * Loaded notices only. The selected region scopes eligible options, not the whole-notice decision.
 * 받은 판정은 사용자·정보 버전·지역·갱신 번호·목록 요청(listKey)이 같은 동안 들고 있다가, 목록이 늘면 새 공고만, 재판정 중이면 그 공고만 다시 묻는다.
 * 판정이 바뀌는 계기(정보 저장, 재판정 완료)는 갱신 번호가 알려 주므로 완료된 판정을 주기적으로 다시 묻지 않는다.
 * 목록을 새 조건으로 다시 불러오면 같은 공고도 다시 묻는다.
 */
export function useConditionListResults(items: readonly AnnouncementListItem[], region?: string | null, listKey = '') {
  const { repository, revision, mode, hasProfile, sessionKey, stored } = useConditions()
  const active = mode === 'member' && hasProfile
  const scope = `${sessionKey ?? ''}:${stored?.version ?? 0}:${region ?? ''}:${revision}:${listKey}`
  const refs = useMemo<AnnouncementRef[]>(() => items.map((item) => ({
    announcementId: item.announcementId, title: item.title, rentalType: rentalTypeLabel(item.rentalType) ?? item.rentalType,
    applicationStatus: item.applicationStatus, targets: [], region,
  })), [items, region])
  const cache = useRef<{ scope: string; results: Map<string, ListConditionResult> } | null>(null)
  const [loaded, setLoaded] = useState<{ scope: string; results: ReadonlyMap<string, ListConditionResult>; error: boolean } | null>(null)
  const [attempt, setAttempt] = useState(0)
  const retry = useCallback(() => setAttempt((value) => value + 1), [])

  useEffect(() => {
    if (!active || !refs.length) return
    if (cache.current?.scope !== scope) cache.current = { scope, results: new Map() }
    const store = cache.current.results
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout>
    const load = async (targets: AnnouncementRef[]) => {
      try {
        if (targets.length) {
          const fresh = await repository.loadListResults(targets, controller.signal)
          if (controller.signal.aborted) return
          fresh.forEach((result, id) => store.set(id, result))
        }
        setLoaded({ scope, results: new Map(store), error: false })
        const rechecking = refs.filter((ref) => store.get(ref.announcementId)?.freshness === 'RECHECKING')
        if (rechecking.length) timer = setTimeout(() => { void load(rechecking) }, 3000)
      } catch {
        if (!controller.signal.aborted) setLoaded({ scope, results: new Map(store), error: true })
      }
    }
    void load(refs.filter((ref) => !store.has(ref.announcementId)))
    return () => { controller.abort(); clearTimeout(timer) }
  }, [active, scope, refs, repository, attempt])

  return useMemo(() => {
    if (!active) return { results: EMPTY, matchedIds: new Set<string>(), status: 'idle' as const, rechecking: false, retry }
    if (!items.length) return { results: EMPTY, matchedIds: new Set<string>(), status: 'ready' as const, rechecking: false, retry }
    const current = loaded?.scope === scope ? loaded : null
    // 앞서 받았지만 지금 목록에 없는 공고의 판정은 화면에 넘기지 않는다.
    const results = new Map(items.flatMap((item) => {
      const result = current?.results.get(item.announcementId)
      return result ? [[item.announcementId, result] as const] : []
    }))
    const status = current?.error ? 'error' as const : results.size === new Set(items.map((item) => item.announcementId)).size ? 'ready' as const : 'loading' as const
    const matchedIds = new Set([...results].filter(([, result]) => result.eligibleInRegion).map(([id]) => id))
    return { results, matchedIds, status, rechecking: [...results.values()].some((item) => item.freshness === 'RECHECKING'), retry }
  }, [active, items, scope, loaded, retry])
}

export function useMissingConditionFields(): ProfileField[] {
  const { stored, hasProfile } = useConditions()
  return useMemo(() => hasProfile && stored ? missingFields(fromProfile(stored.profile)) : [], [hasProfile, stored])
}
