import { describe, expect, it } from 'vitest'
import {
  createStreetViewInitMessage,
  parseStreetViewChildMessage,
  parseStreetViewParentMessage,
  STREET_VIEW_CHANNEL,
  STREET_VIEW_VERSION,
} from './protocol'

const attemptId = '7dce1fe0-6728-47f6-b9d8-806920929a3a'
const envelope = { channel: STREET_VIEW_CHANNEL, version: STREET_VIEW_VERSION }
const initialization = {
  searchPosition: { latitude: 37.561443, longitude: 126.962715 },
  lookAtPosition: { latitude: 37.561443, longitude: 126.962715 },
  tilt: 0,
  fov: 90,
}

describe('street-view message protocol', () => {
  it('bootstraps without an attempt ID and requires one after initialization', () => {
    expect(parseStreetViewChildMessage({ ...envelope, type: 'BOOT_READY' })).toEqual({ ...envelope, type: 'BOOT_READY' })
    expect(parseStreetViewChildMessage({ ...envelope, type: 'READY', aligned: true })).toBeNull()
    expect(parseStreetViewChildMessage({ ...envelope, type: 'READY', aligned: true, attemptId })).toEqual({ ...envelope, type: 'READY', aligned: true, attemptId })
  })

  it('rejects mismatched versions, malformed IDs, coordinates and values', () => {
    const message = createStreetViewInitMessage(attemptId, initialization)
    expect(parseStreetViewParentMessage(message)).toEqual(message)
    expect(parseStreetViewParentMessage({ ...message, version: 2 })).toBeNull()
    expect(parseStreetViewParentMessage({ ...message, channel: 'other-feature' })).toBeNull()
    expect(parseStreetViewParentMessage({ ...message, attemptId: 'unsafe-id' })).toBeNull()
    expect(parseStreetViewParentMessage({ ...message, initialization: { ...initialization, fov: Infinity } })).toBeNull()
    expect(parseStreetViewParentMessage({ ...message, initialization: { ...initialization, searchPosition: { latitude: 999, longitude: 126 } } })).toBeNull()
  })

  it('accepts only defined phase and failure pairs', () => {
    expect(parseStreetViewChildMessage({ ...envelope, attemptId, type: 'FAILED', phase: 'SDK', reasonCode: 'SDK_AUTH_FAILED' })).not.toBeNull()
    expect(parseStreetViewChildMessage({ ...envelope, attemptId, type: 'FAILED', phase: 'PANORAMA', reasonCode: 'SDK_AUTH_FAILED' })).toBeNull()
    expect(parseStreetViewChildMessage({ ...envelope, attemptId, type: 'PHASE', phase: 'UNKNOWN' })).toBeNull()
  })

  it('bounds optional metadata and strips fields outside the message contract', () => {
    expect(parseStreetViewChildMessage({ ...envelope, attemptId, type: 'LOCATION', photodate: null, rawError: 'private' })).toEqual({ ...envelope, attemptId, type: 'LOCATION', photodate: null })
    expect(parseStreetViewChildMessage({ ...envelope, attemptId, type: 'LOCATION', photodate: 'x'.repeat(101) })).toBeNull()
    expect(parseStreetViewChildMessage({ ...envelope, attemptId, type: 'READY', aligned: 'true' })).toBeNull()
  })
})
