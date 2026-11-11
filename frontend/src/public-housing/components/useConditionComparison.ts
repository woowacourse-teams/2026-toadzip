import { useCallback, useEffect, useState } from 'react'
import { useConditions } from '../../user/conditions/ConditionsContext'
import type { AnnouncementRef, ComparisonView } from '../../user/conditions/conditionsModel'

interface ComparisonState {
  readonly key: string
  readonly revision: number
  readonly view: ComparisonView | null
  readonly status: 'loading' | 'ready' | 'error'
}

/** Only the response for the current member, input revision and requested notice is visible. */
export function useConditionComparison(ref: AnnouncementRef | null) {
  const { repository, revision, mode, sessionKey, stored } = useConditions()
  const active = ref !== null && mode === 'member'
  const key = `${sessionKey ?? ''}:${ref?.announcementId ?? ''}:${ref?.region ?? ''}:${stored?.version ?? 0}`
  const [state, setState] = useState<ComparisonState | null>(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    if (!active || !ref) return
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout>
    setState({ key, revision, view: null, status: 'loading' })
    const refresh = async () => {
      try {
        const view = await repository.loadComparison(ref, controller.signal)
        if (controller.signal.aborted) return
        // A corrected announcement may have a different ID. The request key owns this response.
        setState({ key, revision, view, status: 'ready' })
        timer = setTimeout(() => { void refresh() }, view.freshness === 'RECHECKING' ? 1500 : 30_000)
      } catch {
        if (!controller.signal.aborted) setState({ key, revision, view: null, status: 'error' })
      }
    }
    void refresh()
    return () => { controller.abort(); clearTimeout(timer) }
  }, [active, ref, repository, revision, key, attempt])

  const retry = useCallback(() => setAttempt((value) => value + 1), [])
  const current = active && state?.key === key && state.revision === revision ? state : null
  return { status: current?.status ?? 'loading', view: current?.view ?? null, loadedRevision: current?.revision ?? 0, retry }
}
