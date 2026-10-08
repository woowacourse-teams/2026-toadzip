import { getApiBaseUrl } from '../api/apiBaseUrl'
import {
  isRecord,
  type StreetViewCancelReason,
  type StreetViewEvent,
  type StreetViewFailureReason,
  type StreetViewPhase,
} from './types'

const REPORT_TIMEOUT_MS = 3_000
const PHASE_ORDER: Record<StreetViewPhase, number> = { DOCUMENT: 0, SDK: 1, PANORAMA: 2 }
type Csrf = { readonly token: string; readonly headerName: string }
let csrfPreparation: Promise<Csrf | null> | null = null

/** Collection is best effort and has a separate lifetime from the modal. */
export async function reportStreetViewEvent(event: StreetViewEvent): Promise<void> {
  const controller = new AbortController()
  const timeout = window.setTimeout(() => controller.abort(), REPORT_TIMEOUT_MS)
  try {
    const csrf = await getCsrf()
    if (!csrf) return
    controller.signal.throwIfAborted()
    const response = await fetch(`${getApiBaseUrl()}/api/v1/street-view/events`, {
      method: 'POST', credentials: 'include', cache: 'no-store',
      headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
      body: JSON.stringify(event), signal: controller.signal,
    })
    if (response.status !== 204) return
  } catch {
    // Collection failure must not interrupt a usable street view or trigger retries.
  } finally {
    window.clearTimeout(timeout)
  }
}

function getCsrf(): Promise<Csrf | null> {
  if (csrfPreparation) return csrfPreparation
  const preparation = prepareCsrf().finally(() => { csrfPreparation = null })
  csrfPreparation = preparation
  return preparation
}

async function prepareCsrf(): Promise<Csrf | null> {
  const controller = new AbortController()
  const timeout = window.setTimeout(() => controller.abort(), REPORT_TIMEOUT_MS)
  try {
    const response = await fetch(`${getApiBaseUrl()}/api/auth/csrf`, {
      credentials: 'include', cache: 'no-store', signal: controller.signal,
    })
    if (!response.ok) return null
    const value: unknown = await response.json()
    if (!isRecord(value) || typeof value.token !== 'string' || value.token.length === 0
      || typeof value.headerName !== 'string' || !/^[!#$%&'*+.^_`|~\w-]+$/.test(value.headerName)) return null
    return { token: value.token, headerName: value.headerName }
  } catch {
    return null
  } finally {
    window.clearTimeout(timeout)
  }
}

export interface StreetViewAttempt {
  readonly id: string
  readonly phase: StreetViewPhase
  readonly terminal: boolean
  start(): void
  setPhase(phase: StreetViewPhase): void
  ready(): void
  fail(reason: StreetViewFailureReason): void
  cancel(reason: StreetViewCancelReason): void
}

/** Own one instance per user-triggered opening, not per React effect registration. */
export function createStreetViewAttempt(
  configuration: { readonly complexId: number; readonly policyRevision: number },
  report: (event: StreetViewEvent) => void = event => { void reportStreetViewEvent(event) },
): StreetViewAttempt {
  const identity = { attemptId: crypto.randomUUID(), complexId: configuration.complexId, policyRevision: configuration.policyRevision }
  let startedAt: number | null = null
  let phase: StreetViewPhase = 'DOCUMENT'
  let terminal = false
  function durationMs(): number {
    return Math.min(600_000, Math.max(0, Math.floor(performance.now() - (startedAt ?? performance.now()))))
  }
  function finish(event: StreetViewEvent): void {
    if (startedAt === null || terminal) return
    terminal = true
    report(event)
  }
  return {
    id: identity.attemptId,
    get phase() { return phase },
    get terminal() { return terminal },
    start() {
      if (startedAt !== null) return
      startedAt = performance.now()
      report({ ...identity, type: 'STARTED', phase: 'DOCUMENT', reasonCode: null, durationMs: 0 })
    },
    setPhase(next) {
      if (startedAt !== null && !terminal && PHASE_ORDER[next] > PHASE_ORDER[phase]) phase = next
    },
    ready() {
      finish({ ...identity, type: 'READY', phase: 'PANORAMA', reasonCode: null, durationMs: durationMs() })
    },
    fail(reasonCode) {
      finish({ ...identity, type: 'FAILED', phase: failurePhase(reasonCode, phase), reasonCode, durationMs: durationMs() })
    },
    cancel(reasonCode) {
      finish({ ...identity, type: 'CANCELLED', phase, reasonCode, durationMs: durationMs() })
    },
  }
}

function failurePhase(reason: StreetViewFailureReason, current: StreetViewPhase): StreetViewPhase {
  switch (reason) {
    case 'DOCUMENT_TIMEOUT': return 'DOCUMENT'
    case 'SDK_LOAD_FAILED':
    case 'SDK_AUTH_FAILED':
    case 'SDK_UNAVAILABLE': return 'SDK'
    case 'PANORAMA_QUERY_FAILED': return 'PANORAMA'
    case 'INITIALIZATION_TIMEOUT': return current
  }
}
