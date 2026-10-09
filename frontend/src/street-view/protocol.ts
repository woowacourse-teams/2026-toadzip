import { isStreetViewInitialization, type StreetViewInitialization } from './types'

export const STREET_VIEW_CHANNEL = 'toadzip-street-view'
export const STREET_VIEW_VERSION = 2

export type StreetViewMarkerStatus = 'ATTACHED' | 'UNAVAILABLE'

type Envelope = {
  channel: typeof STREET_VIEW_CHANNEL
  version: typeof STREET_VIEW_VERSION
}

export type StreetViewRuntimeFailure =
  | { phase: 'SDK'; reasonCode: 'SDK_LOAD_FAILED' | 'SDK_AUTH_FAILED' | 'SDK_UNAVAILABLE' }
  | { phase: 'PANORAMA'; reasonCode: 'PANORAMA_QUERY_FAILED' }

export type StreetViewRuntimePayload =
  | { type: 'PHASE'; phase: 'SDK' | 'PANORAMA' }
  | { type: 'READY'; aligned: boolean }
  | ({ type: 'FAILED' } & StreetViewRuntimeFailure)
  | { type: 'LOCATION'; photodate: string | null }
  | { type: 'MARKER_STATUS'; status: StreetViewMarkerStatus }
  | { type: 'CLOSE_REQUEST' }

export type StreetViewChildMessage = Envelope & (
  | { type: 'BOOT_READY' }
  | ({ attemptId: string } & StreetViewRuntimePayload)
)

export type StreetViewParentMessage = Envelope & {
  type: 'INIT'
  attemptId: string
  initialization: StreetViewInitialization
  markerLabel: string
}

export function createStreetViewInitMessage(
  attemptId: string,
  initialization: StreetViewInitialization,
  markerLabel: string,
): StreetViewParentMessage {
  return { channel: STREET_VIEW_CHANNEL, version: STREET_VIEW_VERSION, type: 'INIT', attemptId, initialization, markerLabel }
}

export function parseStreetViewParentMessage(value: unknown): StreetViewParentMessage | null {
  if (!isEnvelope(value) || value.type !== 'INIT' || !isAttemptId(value.attemptId) ||
    !isStreetViewInitialization(value.initialization) || !isMarkerLabel(value.markerLabel)) {
    return null
  }

  return createStreetViewInitMessage(value.attemptId, value.initialization, value.markerLabel)
}

export function parseStreetViewChildMessage(value: unknown): StreetViewChildMessage | null {
  if (!isEnvelope(value)) return null
  const envelope = { channel: STREET_VIEW_CHANNEL, version: STREET_VIEW_VERSION } as const
  if (value.type === 'BOOT_READY') return { ...envelope, type: 'BOOT_READY' }
  if (!isAttemptId(value.attemptId)) return null
  const attempt = { ...envelope, attemptId: value.attemptId }

  switch (value.type) {
    case 'PHASE':
      if (value.phase === 'SDK' || value.phase === 'PANORAMA') {
        return { ...attempt, type: 'PHASE', phase: value.phase }
      }
      break
    case 'READY':
      if (typeof value.aligned === 'boolean') return { ...attempt, type: 'READY', aligned: value.aligned }
      break
    case 'LOCATION':
      if (value.photodate === null || (typeof value.photodate === 'string' && value.photodate.length <= 100)) {
        return { ...attempt, type: 'LOCATION', photodate: value.photodate }
      }
      break
    case 'MARKER_STATUS':
      if (value.status === 'ATTACHED' || value.status === 'UNAVAILABLE') {
        return { ...attempt, type: 'MARKER_STATUS', status: value.status }
      }
      break
    case 'CLOSE_REQUEST':
      return { ...attempt, type: 'CLOSE_REQUEST' }
    case 'FAILED':
      if (value.phase === 'SDK' && (value.reasonCode === 'SDK_LOAD_FAILED' ||
        value.reasonCode === 'SDK_AUTH_FAILED' || value.reasonCode === 'SDK_UNAVAILABLE')) {
        return { ...attempt, type: 'FAILED', phase: 'SDK', reasonCode: value.reasonCode }
      }
      if (value.phase === 'PANORAMA' && value.reasonCode === 'PANORAMA_QUERY_FAILED') {
        return { ...attempt, type: 'FAILED', phase: 'PANORAMA', reasonCode: value.reasonCode }
      }
  }
  return null
}

function isEnvelope(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null &&
    'channel' in value && value.channel === STREET_VIEW_CHANNEL &&
    'version' in value && value.version === STREET_VIEW_VERSION
}

function isAttemptId(value: unknown): value is string {
  return typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)
}

function isMarkerLabel(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0 && Array.from(value).length <= 261
}
