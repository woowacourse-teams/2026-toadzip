import { useCallback, useEffect, useState } from 'react'
import { useConditions } from '../../user/conditions/ConditionsContext'
import type { AnnouncementRef, ComparisonView } from '../../user/conditions/conditionsModel'

interface ComparisonState {
  readonly id: string
  readonly revision: number
  readonly view: ComparisonView | null
  readonly status: 'loading' | 'ready' | 'error'
}

/** 공고 하나의 내 조건 비교. 공고가 바뀌면 이전 요청을 버리고, 저장할 때마다 다시 불러온다. */
export function useConditionComparison(ref: AnnouncementRef | null) {
  const { repository, revision, mode } = useConditions()
  const active = ref !== null && mode !== 'disabled' && mode !== 'loading'
  const [state, setState] = useState<ComparisonState | null>(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    if (!active || !ref) return
    const controller = new AbortController()
    const id = ref.announcementId
    setState((current) => current?.id === id && current.view ? current : { id, revision, view: null, status: 'loading' })
    repository.loadComparison(ref, controller.signal).then(
      (view) => {
        if (controller.signal.aborted || view.announcementId !== id) return
        setState({ id, revision, view, status: 'ready' })
      },
      () => { if (!controller.signal.aborted) setState({ id, revision, view: null, status: 'error' }) },
    )
    return () => controller.abort()
  }, [active, ref, repository, revision, attempt])

  const retry = useCallback(() => setAttempt((value) => value + 1), [])
  const current = active && state?.id === ref?.announcementId ? state : null
  return { status: current?.status ?? 'loading', view: current?.view ?? null, loadedRevision: current?.revision ?? 0, retry }
}
