import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import type { AnalyticsStopReason } from '../privacy/consentStore'

const consent = vi.hoisted(() => ({ allowed: true,
  stops: [] as Array<(reason?: AnalyticsStopReason) => void>,
  listeners: [] as Array<() => void>,
}))
vi.mock('../privacy/consentStore', () => ({ analyticsCollectionAllowed: () => consent.allowed,
  consentStore: {
    getSnapshot: () => ({ context: { consent: { scopeVersion: 'analytics-scope-3' }, requiredScopeVersion: 'analytics-scope-3' } }),
    remainingConsentLifetime: () => 180 * 24 * 60 * 60 * 1000,
    transition: () => { consent.allowed = false; consent.stops.forEach(listener => listener('context-change')); consent.listeners.forEach(listener => listener()) },
    refresh: async () => {},
    onStop: (listener: (reason?: AnalyticsStopReason) => void) => { consent.stops.push(listener); return () => {} },
    subscribe: (listener: () => void) => { consent.listeners.push(listener); return () => {} },
  },
}))
const network = vi.fn<typeof fetch>()
function stop(reason: AnalyticsStopReason = 'pause') {
  consent.allowed = false
  consent.stops.forEach(listener => listener(reason))
}
function allow() { consent.allowed = true; consent.listeners.forEach(listener => listener()) }
async function settled() { await vi.dynamicImportSettled(); await vi.advanceTimersByTimeAsync(0) }
function payload(index: number) {
  const body = JSON.parse(String(network.mock.calls[index]?.[1]?.body))
  return body.batch[0] as { event: string; uuid: string; timestamp: string; properties: Record<string, unknown> }
}

beforeEach(() => {
  vi.resetModules()
  vi.useFakeTimers()
  consent.allowed = true; consent.stops = []; consent.listeners = []
  network.mockReset().mockResolvedValue(new Response('{}', { status: 200 }))
  vi.stubGlobal('fetch', network)
  vi.stubEnv('VITE_POSTHOG_KEY', 'phc_testpublictoken')
  vi.stubEnv('VITE_POSTHOG_HOST', 'https://us.i.posthog.com')
  vi.stubEnv('VITE_ANALYTICS_ENV', 'dev')
  vi.stubEnv('VITE_POSTHOG_LOCAL_ENABLED', 'true')
  vi.stubEnv('MODE', 'development')
  Object.defineProperty(navigator, 'locks', { configurable: true,
    value: { request: vi.fn(async (_name: string, callback: () => unknown) => callback()) },
  })
  localStorage.clear(); sessionStorage.clear()
  window.history.replaceState({}, '', '/explore')
})
afterEach(() => {
  stop('reset')
  vi.clearAllTimers(); vi.useRealTimers(); vi.unstubAllGlobals(); vi.unstubAllEnvs()
})

it('preserves only core events while a paused SDK import has not finished and resumes without a new action', async () => {
  const analytics = await import('./productAnalytics')
  analytics.captureProductEvent('view_complex', { complex_id: '17' }, { dedupeKey: 'visit:17' })
  analytics.captureProductEvent('page_view')
  expect(sessionStorage.getItem('toadzip.posthog.sent-actions.v1')).toBeNull()
  stop()
  await settled()
  expect(network).not.toHaveBeenCalled()
  allow()
  await settled()
  expect(network).toHaveBeenCalledOnce()
  expect(payload(0).event).toBe('view_complex')
  expect(sessionStorage.getItem('toadzip.posthog.sent-actions.v1')).toContain('visit:17')
})

it('retries an aborted core event with the same event ID, uuid and occurrence time', async () => {
  let complete: (value: Response) => void = () => { throw new Error('no request') }
  network.mockImplementationOnce(() => new Promise(resolve => { complete = resolve }))
  const analytics = await import('./productAnalytics')
  analytics.captureProductEvent('notification_preregistration_completed', {
    target_type: 'COMPLEX', target_id: '17', occurred_at: '2026-10-10T12:00:00Z',
  }, { dedupeKey: 'receipt:1' })
  await settled()
  expect(sessionStorage.getItem('toadzip.posthog.sent-actions.v1')).toBeNull()
  const first = payload(0)
  expect(first.uuid).toMatch(/^[a-f0-9-]{36}$/i)
  expect(first.timestamp).toBe('2026-10-10T12:00:00.000Z')
  const signal = network.mock.calls[0][1]?.signal
  stop()
  expect(signal?.aborted).toBe(true)
  complete(new Response('{}', { status: 200 }))
  await settled()
  expect(sessionStorage.getItem('toadzip.posthog.sent-actions.v1')).toBeNull()
  allow(); await settled()
  const second = payload(1)
  expect(second.uuid).toBe(first.uuid)
  expect(second.properties.event_id).toBe(first.properties.event_id)
  expect(second.timestamp).toBe(first.timestamp)
  expect(second.properties.distinct_id).toBe(first.properties.distinct_id)
  expect(second.properties.$device_id).toBe(first.properties.$device_id)
  expect(second.properties.occurred_at).toBe('2026-10-10T12:00:00Z')
  expect(sessionStorage.getItem('toadzip.posthog.sent-actions.v1')).toContain('receipt:1')
})

it('does not race the SDK retry queue when consent updates or the app retry timer runs', async () => {
  network.mockResolvedValueOnce(new Response('{}', { status: 503 }))
  const analytics = await import('./productAnalytics')
  analytics.captureProductEvent('view_announcement', { announcement_id: '22' })
  await settled()
  allow(); allow()
  expect(network).toHaveBeenCalledOnce()
  await vi.advanceTimersByTimeAsync(120_000)
  expect(network).toHaveBeenCalledTimes(2)
  expect(payload(1).uuid).toBe(payload(0).uuid)
})

it('captures an already permitted notification completion during pause and sanitizes it before retention', async () => {
  const analytics = await import('./productAnalytics')
  analytics.setProductAuthState('member')
  const completion = analytics.prepareNotificationCompletion()
  stop()
  expect(completion({ target_type: 'COMPLEX', target_id: '17', email: 'private@example.test', user_id: 'private',
    server_event_id: crypto.randomUUID() }, { dedupeKey: 'receipt:1' })).toBe(true)
  expect(network).not.toHaveBeenCalled()
  allow(); await settled()
  expect(network).toHaveBeenCalledOnce()
  expect(payload(0).properties).toMatchObject({ target_type: 'COMPLEX', target_id: '17', auth_state: 'member' })
  expect(String(network.mock.calls[0][1]?.body)).not.toMatch(/private|user_id|server_event_id/)
})

it('never upgrades a pre-consent action token after grant', async () => {
  consent.allowed = false
  const analytics = await import('./productAnalytics')
  const completion = analytics.prepareNotificationCompletion()
  allow()
  expect(completion({ target_type: 'COMPLEX', target_id: '17' })).toBe(false)
  await settled()
  expect(network).not.toHaveBeenCalled()
})

it.each(['reset', 'context-change'] as const)('drops queued core events and invalidates completion tokens on %s', async reason => {
  const analytics = await import('./productAnalytics')
  const completion = analytics.prepareNotificationCompletion()
  analytics.captureProductEvent('view_complex', { complex_id: '17' })
  stop(reason)
  allow()
  expect(completion({ target_type: 'COMPLEX', target_id: '17' })).toBe(false)
  await settled()
  expect(network).not.toHaveBeenCalled()
})

it('keeps the browser identity through authentication changes but replaces it after withdrawal', async () => {
  const analytics = await import('./productAnalytics')
  analytics.captureProductEvent('view_complex', { complex_id: '17' })
  await settled()
  const original = payload(0).properties.distinct_id
  stop('context-change'); allow()
  analytics.captureProductEvent('view_announcement', { announcement_id: '22' })
  await settled()
  expect(payload(1).properties.distinct_id).toBe(original)
  expect(payload(1).properties.$is_identified).toBe(false)
  expect(payload(1).properties.$process_person_profile).toBe(false)
  stop('reset'); allow()
  analytics.captureProductEvent('view_complex', { complex_id: '17' })
  await settled()
  expect(payload(2).properties.distinct_id).not.toBe(original)
})

it('bounds paused core retention to 100 events and expires both events and action permissions in 24 hours', async () => {
  const analytics = await import('./productAnalytics')
  const completion = analytics.prepareNotificationCompletion()
  stop()
  for (let index = 0; index < 100; index++) expect(completion({ target_type: 'COMPLEX', target_id: String(index + 1) })).toBe(true)
  expect(completion({ target_type: 'COMPLEX', target_id: '101' })).toBe(false)
  await vi.advanceTimersByTimeAsync(24 * 60 * 60 * 1000)
  expect(completion({ target_type: 'COMPLEX', target_id: '102' })).toBe(false)
  allow(); await settled()
  expect(network).not.toHaveBeenCalled()
})


it('does not keep retrying a final non-retriable rejection or mark it as accepted', async () => {
  network.mockResolvedValue(new Response('{}', { status: 400 }))
  const analytics = await import('./productAnalytics')
  analytics.captureProductEvent('view_complex', { complex_id: '17' }, { dedupeKey: 'visit:invalid' })
  await settled()
  expect(sessionStorage.getItem('toadzip.posthog.sent-actions.v1')).toBeNull()
  allow()
  await vi.advanceTimersByTimeAsync(120_000)
  expect(network).toHaveBeenCalledOnce()
})

it.each([408, 429])('retries a temporary HTTP %i rejection with the original UUID', async status => {
  network.mockResolvedValueOnce(new Response('{}', { status }))
  const analytics = await import('./productAnalytics')
  analytics.captureProductEvent('view_complex', { complex_id: '17' }, { dedupeKey: 'visit:temporary' })
  await settled()
  expect(sessionStorage.getItem('toadzip.posthog.sent-actions.v1')).toBeNull()
  const first = payload(0)
  await vi.advanceTimersByTimeAsync(30_000)
  expect(network).toHaveBeenCalledTimes(2)
  expect(payload(1).uuid).toBe(first.uuid)
  expect(sessionStorage.getItem('toadzip.posthog.sent-actions.v1')).toContain('visit:temporary')
})

function hoverProperties() {
  return { exploration_id: crypto.randomUUID(), hover_id: crypto.randomUUID(), complex_id: '17',
    measurement_version: 'marker_hover_v1', duration_ms: 124, reason: 'document_hidden',
    hover_started_at: '2026-10-23T01:00:00.000Z', occurred_at: '2026-10-23T01:00:00.124Z' }
}

it('retains an eligible opportunity and paused hover completion with occurrence-time metadata', async () => {
  const analytics = await import('./productAnalytics')
  analytics.setProductAuthState('guest')
  const properties = hoverProperties()
  analytics.captureProductEvent('map_marker_hover_available', {
    exploration_id: properties.exploration_id, measurement_version: properties.measurement_version,
  })
  const completion = analytics.prepareMarkerHoverCompletion()
  expect(completion).not.toBeNull()
  stop()
  expect(analytics.captureProductEvent('map_marker_hover_ended', properties)).toBe(false)
  expect(completion?.complete({ ...properties, email: 'private@example.test', latitude: 37.1 })).toBe(true)
  await settled()
  expect(network).not.toHaveBeenCalled()
  window.history.replaceState({}, '', '/feedback')
  allow(); await settled()
  expect(network).toHaveBeenCalledTimes(2)
  expect(payload(0).event).toBe('map_marker_hover_available')
  expect(payload(1)).toMatchObject({ event: 'map_marker_hover_ended', timestamp: properties.occurred_at,
    properties: { ...properties, identity_basis: 'browser_v1', environment: 'dev', auth_state: 'guest', page_name: 'explorer', $pathname: '/explore' } })
  expect(String(network.mock.calls[1][1]?.body)).not.toMatch(/private|latitude/)
})

it('retries an interrupted raw hover with the same event, hover and browser IDs', async () => {
  let complete: (response: Response) => void = () => { throw new Error('request missing') }
  network.mockImplementationOnce(() => new Promise(resolve => { complete = resolve }))
  const analytics = await import('./productAnalytics')
  const completion = analytics.prepareMarkerHoverCompletion()
  expect(completion?.complete(hoverProperties())).toBe(true)
  await settled()
  const first = payload(0), signal = network.mock.calls[0][1]?.signal
  stop()
  expect(signal?.aborted).toBe(true)
  complete(new Response('{}', { status: 200 })); await settled()
  allow(); await settled()
  const second = payload(1)
  expect(second).toMatchObject({ uuid: first.uuid, timestamp: first.timestamp,
    properties: { event_id: first.properties.event_id, hover_id: first.properties.hover_id,
      exploration_id: first.properties.exploration_id, distinct_id: first.properties.distinct_id } })
  expect(completion?.complete(hoverProperties())).toBe(false)
})

it('never creates a pre-consent hover permission that could be upgraded later', async () => {
  consent.allowed = false
  const analytics = await import('./productAnalytics')
  expect(analytics.prepareMarkerHoverCompletion()).toBeNull()
  expect(analytics.captureProductEvent('map_marker_hover_available', hoverProperties())).toBe(false)
  allow(); await settled()
  expect(network).not.toHaveBeenCalled()
})

it.each(['reset', 'context-change', 'consent-replaced'] as const)('discards raw hover queues and captured permissions on %s', async reason => {
  const analytics = await import('./productAnalytics')
  const completion = analytics.prepareMarkerHoverCompletion()
  analytics.captureProductEvent('map_marker_hover_available', hoverProperties())
  completion?.complete(hoverProperties())
  stop(reason)
  expect(completion?.isCurrent()).toBe(false)
  expect(completion?.complete(hoverProperties())).toBe(false)
  allow(); await settled()
  expect(network).not.toHaveBeenCalled()
})

it('limits raw hover retention independently of the core outcome capacity', async () => {
  const analytics = await import('./productAnalytics')
  const completions = Array.from({ length: 101 }, () => analytics.prepareMarkerHoverCompletion())
  const core = analytics.prepareNotificationCompletion()
  stop()
  completions.slice(0, 100).forEach(completion => expect(completion?.complete(hoverProperties())).toBe(true))
  expect(completions[100]?.complete(hoverProperties())).toBe(false)
  expect(core({ target_type: 'COMPLEX', target_id: '17' })).toBe(true)
  await settled()
  expect(network).not.toHaveBeenCalled()
})

it('expires both raw hover records and their completion permissions after 24 hours', async () => {
  const analytics = await import('./productAnalytics')
  const completion = analytics.prepareMarkerHoverCompletion()
  stop()
  expect(completion?.complete(hoverProperties())).toBe(true)
  await vi.advanceTimersByTimeAsync(24 * 60 * 60 * 1000)
  expect(completion?.isCurrent()).toBe(false)
  expect(completion?.complete(hoverProperties())).toBe(false)
  allow(); await settled()
  expect(network).not.toHaveBeenCalled()
})
