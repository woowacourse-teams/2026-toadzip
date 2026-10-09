export interface StreetViewPosition {
  readonly latitude: number
  readonly longitude: number
}

export interface StreetViewInitialization {
  readonly searchPosition: StreetViewPosition
  readonly lookAtPosition: StreetViewPosition
  readonly tilt: number
  readonly fov: number
}

interface StreetViewConfigurationBase {
  readonly complexId: number
  readonly provider: 'NAVER'
  readonly policyRevision: number
}

export interface EnabledStreetViewConfiguration extends StreetViewConfigurationBase {
  readonly enabled: true
  readonly disabledReason: null
  readonly initialization: StreetViewInitialization
}

export interface DisabledStreetViewConfiguration extends StreetViewConfigurationBase {
  readonly enabled: false
  readonly disabledReason: 'POLICY_DISABLED' | 'INVALID_COORDINATES'
  readonly initialization: null
}

export type StreetViewConfiguration = EnabledStreetViewConfiguration | DisabledStreetViewConfiguration

export type StreetViewPhase = 'DOCUMENT' | 'SDK' | 'PANORAMA'
export type StreetViewFailureReason = 'DOCUMENT_TIMEOUT' | 'SDK_LOAD_FAILED' | 'SDK_AUTH_FAILED'
  | 'SDK_UNAVAILABLE' | 'PANORAMA_QUERY_FAILED' | 'INITIALIZATION_TIMEOUT'
export type StreetViewCancelReason = 'USER_CLOSED' | 'TARGET_CHANGED'

interface StreetViewEventBase {
  readonly attemptId: string
  readonly complexId: number
  readonly policyRevision: number
  readonly durationMs: number
}

export type StreetViewEvent = StreetViewEventBase & (
  | { readonly type: 'STARTED'; readonly phase: 'DOCUMENT'; readonly reasonCode: null }
  | { readonly type: 'READY'; readonly phase: 'PANORAMA'; readonly reasonCode: null }
  | { readonly type: 'FAILED'; readonly phase: StreetViewPhase; readonly reasonCode: StreetViewFailureReason }
  | { readonly type: 'CANCELLED'; readonly phase: StreetViewPhase; readonly reasonCode: StreetViewCancelReason }
)

export function isStreetViewInitialization(value: unknown): value is StreetViewInitialization {
  return isRecord(value)
    && isPosition(value.searchPosition)
    && isPosition(value.lookAtPosition)
    && isFiniteNumberInRange(value.tilt, -90, 90)
    && isFiniteNumberInRange(value.fov, 0, 180) && value.fov > 0
}

function isPosition(value: unknown): value is StreetViewPosition {
  return isRecord(value)
    && isFiniteNumberInRange(value.latitude, -90, 90)
    && isFiniteNumberInRange(value.longitude, -180, 180)
    && (value.latitude !== 0 || value.longitude !== 0)
}

function isFiniteNumberInRange(value: unknown, minimum: number, maximum: number): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value >= minimum && value <= maximum
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
