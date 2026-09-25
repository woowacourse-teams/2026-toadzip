import { useCallback, useEffect, useState } from 'react'
import type { RegionBoundary } from './regionBoundary.ts'
import type { RegionBoundaryRepository } from './regionBoundaryRepository.ts'

type Status = 'idle' | 'loading' | 'ready' | 'error'
interface BoundaryState {
  readonly code: string | null
  readonly revision: number
  readonly boundary: RegionBoundary | null
  readonly status: Status
}

export function useRegionBoundary(code: string | null, repository: RegionBoundaryRepository) {
  const [revision, setRevision] = useState(0)
  const [state, setState] = useState<BoundaryState>({ code: null, revision: 0, boundary: null, status: 'idle' })
  const retry = useCallback(() => setRevision((current) => current + 1), [])

  useEffect(() => {
    if (code === null) {
      return
    }
    const controller = new AbortController()
    setState({ code, revision, boundary: null, status: 'loading' })
    void repository.find(code, controller.signal).then((boundary) => {
      if (!controller.signal.aborted) {
        setState({ code, revision, boundary, status: 'ready' })
      }
    }).catch(() => {
      if (!controller.signal.aborted) {
        setState({ code, revision, boundary: null, status: 'error' })
      }
    })
    return () => controller.abort()
  }, [code, repository, revision])

  const current = code !== null && state.code === code && state.revision === revision
  return {
    boundary: current ? state.boundary : null,
    status: code === null ? 'idle' : current ? state.status : 'loading',
    retry,
  } as const
}
