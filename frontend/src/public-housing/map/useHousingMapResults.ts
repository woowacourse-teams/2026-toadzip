import { useCallback, useEffect, useRef, useState } from 'react'
import type {
  ComplexSearchFilters,
} from '../api/publicHousingRepository.ts'
import type {
  HousingMapQuery,
  HousingMapRepository,
} from '../api/housingMapRepository.ts'
import {
  hasSearchFilters,
  searchFiltersSignature,
} from '../filters/searchFilterLocation.ts'
import type { HousingMapResult } from '../model/housingMap.ts'
import {
  evaluateServerMapRequest,
  type ViewportSnapshot,
} from './viewportPolicy.ts'

export type HousingMapRequestStatus =
  | 'idle'
  | 'loading'
  | 'ready'
  | 'error'

export interface AppliedHousingMapResult {
  readonly query: HousingMapQuery
  readonly result: HousingMapResult
}

export interface HousingMapResultsState {
  readonly applied: AppliedHousingMapResult | null
  readonly errorMessage: string | null
  readonly status: HousingMapRequestStatus
}

export type HousingMapRequestOutcome =
  | 'applied'
  | 'ignored'
  | 'pending'
  | 'started'

const INITIAL_STATE: HousingMapResultsState = {
  applied: null,
  errorMessage: null,
  status: 'idle',
}

interface PendingRequest {
  readonly controller: AbortController
  readonly signature: string
}

interface RequestCandidate {
  readonly baseSignature: string
  readonly query: HousingMapQuery
  readonly signature: string
}

export function useHousingMapResults(
  repository: HousingMapRepository | null | undefined,
) {
  const [state, setState] = useState<HousingMapResultsState>(INITIAL_STATE)
  const appliedSignatureRef = useRef<string | null>(null)
  const failedRequestRef = useRef<RequestCandidate | null>(null)
  const pendingRequestRef = useRef<PendingRequest | null>(null)
  const requestRevisionRef = useRef(0)
  const resolvedStageRef = useRef<HousingMapResult['resolvedStage'] | null>(null)

  const startRequest = useCallback((candidate: RequestCandidate) => {
    if (repository === null || repository === undefined) {
      return false
    }
    pendingRequestRef.current?.controller.abort()
    const controller = new AbortController()
    const revision = requestRevisionRef.current + 1
    requestRevisionRef.current = revision
    pendingRequestRef.current = { controller, signature: candidate.signature }
    failedRequestRef.current = null
    setState((current) => ({
      ...current,
      errorMessage: null,
      status: 'loading',
    }))
    repository.findMap(candidate.query, controller.signal)
      .then((result) => acceptResult(candidate, controller, revision, result))
      .catch((error: unknown) => rejectResult(
        candidate,
        controller,
        revision,
        error,
      ))
    return true
  }, [repository])

  function acceptResult(
    candidate: RequestCandidate,
    controller: AbortController,
    revision: number,
    result: HousingMapResult,
  ) {
    if (!isCurrentRequest(controller, revision)) {
      return
    }
    pendingRequestRef.current = null
    appliedSignatureRef.current = requestSignature(
      candidate.baseSignature,
      result.resolvedStage,
    )
    resolvedStageRef.current = result.resolvedStage
    setState({
      applied: { query: candidate.query, result },
      errorMessage: null,
      status: 'ready',
    })
  }

  function rejectResult(
    candidate: RequestCandidate,
    controller: AbortController,
    revision: number,
    error: unknown,
  ) {
    if (isAbortError(error) || !isCurrentRequest(controller, revision)) {
      return
    }
    pendingRequestRef.current = null
    failedRequestRef.current = candidate
    setState((current) => ({
      ...current,
      errorMessage: requestErrorMessage(error),
      status: 'error',
    }))
  }

  function isCurrentRequest(controller: AbortController, revision: number) {
    return !controller.signal.aborted
      && requestRevisionRef.current === revision
  }

  const request = useCallback((
    viewport: ViewportSnapshot,
    filters: ComplexSearchFilters = {},
  ): HousingMapRequestOutcome => {
    const candidate = requestCandidate(
      viewport,
      filters,
      resolvedStageRef.current,
    )
    if (candidate === null) {
      return 'ignored' as const
    }
    if (pendingRequestRef.current?.signature === candidate.signature) {
      return 'pending' as const
    }
    if (restoreAppliedResult(candidate.signature)) {
      return 'applied' as const
    }
    if (!startRequest(candidate)) {
      return 'ignored'
    }
    return 'started'
  }, [startRequest])

  function restoreAppliedResult(signature: string) {
    if (appliedSignatureRef.current !== signature) {
      return false
    }
    const pendingRequest = pendingRequestRef.current
    if (pendingRequest !== null) {
      requestRevisionRef.current += 1
      pendingRequest.controller.abort()
      pendingRequestRef.current = null
    }
    failedRequestRef.current = null
    setState((current) => ({
      ...current,
      errorMessage: null,
      status: 'ready',
    }))
    return true
  }

  const retry = useCallback(() => {
    const failedRequest = failedRequestRef.current
    if (failedRequest === null) {
      return false
    }
    return startRequest(failedRequest)
  }, [startRequest])

  const cancel = useCallback(() => {
    const pendingRequest = pendingRequestRef.current
    if (pendingRequest === null) {
      return false
    }
    requestRevisionRef.current += 1
    pendingRequest.controller.abort()
    pendingRequestRef.current = null
    setState((current) => ({
      ...current,
      errorMessage: null,
      status: current.applied === null ? 'idle' : 'ready',
    }))
    return true
  }, [])

  useEffect(() => {
    return () => {
      pendingRequestRef.current?.controller.abort()
      pendingRequestRef.current = null
      requestRevisionRef.current += 1
    }
  }, [])

  return { cancel, request, retry, state }
}

function requestCandidate(
  viewport: ViewportSnapshot,
  filters: ComplexSearchFilters,
  previousResolvedStage: HousingMapResult['resolvedStage'] | null,
): RequestCandidate | null {
  const decision = evaluateServerMapRequest(viewport)
  if (!decision.allowed) {
    return null
  }
  const filtersSignature = searchFiltersSignature(filters)
  const baseSignature = [
    decision.boundsSignature,
    viewport.zoom,
    filtersSignature,
  ].join('|')
  const query = {
    bounds: viewport.bounds,
    zoom: viewport.zoom,
    ...(previousResolvedStage === null ? {} : { previousResolvedStage }),
    ...(hasSearchFilters(filters) ? { filters } : {}),
  }
  const signature = requestSignature(baseSignature, previousResolvedStage)
  return { baseSignature, query, signature }
}

function requestSignature(
  baseSignature: string,
  previousResolvedStage: HousingMapResult['resolvedStage'] | null,
) {
  return `${baseSignature}|${previousResolvedStage ?? 'NONE'}`
}

function requestErrorMessage(error: unknown) {
  if (error instanceof Error && error.message.trim()) {
    return error.message
  }
  return '지도 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

function isAbortError(error: unknown) {
  return error instanceof DOMException && error.name === 'AbortError'
}
