import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CaptureResult, PostHogConfig } from 'posthog-js'

const sdk = vi.hoisted(() => ({
  init: vi.fn(), capture: vi.fn(), startSessionRecording: vi.fn(), stopSessionRecording: vi.fn(),
  opt_out_capturing: vi.fn(), identify: vi.fn(), alias: vi.fn(), reset: vi.fn(),
}))
vi.mock('posthog-js', () => ({ default: sdk }))
let listeners: EventListenerOrEventListenerObject[] = []

beforeEach(() => {
  vi.resetModules()
  vi.clearAllMocks()
  sdk.init.mockReturnValue(sdk)
  vi.stubEnv('VITE_POSTHOG_KEY', 'phc_testpublictoken')
  vi.stubEnv('VITE_POSTHOG_HOST', 'https://us.i.posthog.com')
  vi.stubEnv('VITE_ANALYTICS_ENV', 'dev')
  vi.stubEnv('VITE_POSTHOG_LOCAL_ENABLED', 'true')
  vi.stubEnv('MODE', 'development')
  localStorage.clear()
  sessionStorage.clear()
  window.history.replaceState({}, '', '/')
  listeners = []
  const add = window.addEventListener.bind(window)
  vi.spyOn(window, 'addEventListener').mockImplementation((type, listener, options) => {
    if (type === 'storage' && listener) listeners.push(listener)
    add(type, listener, options)
  })
})

afterEach(() => {
  for (const listener of listeners) window.removeEventListener('storage', listener)
  vi.unstubAllEnvs()
  vi.restoreAllMocks()
})

async function initialized() {
  const analytics = await import('./productAnalytics')
  analytics.captureProductEvent('page_view')
  await vi.dynamicImportSettled()
  return analytics
}

function captured(properties: Record<string, unknown>, event = 'view_complex'): CaptureResult {
  return { event, properties, timestamp: new Date(), uuid: crypto.randomUUID() }
}

describe('PostHog privacy and identity boundary', () => {
  it.each([
    ['MODE', 'test'],
    ['VITE_POSTHOG_KEY', ''], ['VITE_POSTHOG_HOST', 'https://unexpected.example'],
    ['VITE_ANALYTICS_ENV', 'prod'], ['VITE_POSTHOG_LOCAL_ENABLED', 'false'],
  ])('does not initialize for unsafe/missing local config %s=%s', async (key, value) => {
    vi.stubEnv(key, value)
    const analytics = await import('./productAnalytics')
    expect(analytics.captureProductEvent('page_view')).toBe(false)
    await vi.dynamicImportSettled()
    expect(sdk.init).not.toHaveBeenCalled()
  })

  it.each(['/admin', '/admin/complexes/1', '/login', '/not-found'])('does not initialize on %s', async path => {
    window.history.replaceState({}, '', path)
    await initialized()
    expect(sdk.init).not.toHaveBeenCalled()
  })

  it('uses one SDK instance, stable browser identity and occurrence-time authentication', async () => {
    const analytics = await import('./productAnalytics')
    analytics.setProductAuthState('guest')
    analytics.captureProductEvent('view_complex', { complex_id: '17' })
    analytics.setProductAuthState('member')
    await vi.dynamicImportSettled()
    expect(sdk.init).toHaveBeenCalledOnce()
    expect(sdk.capture).toHaveBeenCalledWith('view_complex', expect.objectContaining({ auth_state: 'guest' }), expect.anything())
    const view = sdk.capture.mock.calls.find(([name]) => name === 'view_complex')
    expect(analytics.sanitizeCapture(captured(view?.[1]))?.properties.auth_state).toBe('guest')
    expect(sdk.capture.mock.calls.filter(([name]) => name === 'auth_state_changed')).toHaveLength(1)
    analytics.setProductAuthState('unknown')
    analytics.setProductAuthState('member')
    expect(sdk.identify).not.toHaveBeenCalled()
    expect(sdk.alias).not.toHaveBeenCalled()
    expect(sdk.reset).not.toHaveBeenCalled()
  })

  it('deduplicates server outcomes through retries and module reloads without suppressing separate visits', async () => {
    let analytics = await initialized()
    const properties = { target_type: 'COMPLEX', target_id: '17', occurred_at: '2026-10-07T23:59:00Z' }
    expect(analytics.captureProductEvent('notification_preregistration_completed', properties, { dedupeKey: 'server:1' })).toBe(true)
    expect(analytics.captureProductEvent('notification_preregistration_completed', properties, { dedupeKey: 'server:1' })).toBe(false)
    vi.resetModules()
    analytics = await import('./productAnalytics')
    expect(analytics.captureProductEvent('notification_preregistration_completed', properties, { dedupeKey: 'server:1' })).toBe(false)
    expect(analytics.captureProductEvent('view_complex', { complex_id: '17' }, { dedupeKey: 'visit:2' })).toBe(true)
    await vi.dynamicImportSettled()
    expect(sdk.capture.mock.calls.filter(([name]) => name === 'notification_preregistration_completed')).toHaveLength(1)
    expect(sdk.capture).toHaveBeenCalledWith('notification_preregistration_completed', expect.anything(), { timestamp: new Date(properties.occurred_at) })
  })

  it('removes query/hash, person properties, contact, precise location and arbitrary fields', async () => {
    window.history.replaceState({}, '', '/?email=private@example.test&lat=37.123#secret')
    const analytics = await initialized()
    vi.spyOn(document, 'referrer', 'get').mockReturnValue('https://user:password@search.example/private?q=secret')
    const unsafe = captured({
      complex_id: '17', entry_point: 'map', email: 'private@example.test', user_id: 'member-1', query: 'secret',
      latitude: 37.123, $set: { email: 'private@example.test' }, $set_once: { $initial_current_url: 'secret' },
      $current_url: window.location.href, $referrer: 'secret', event_id: crypto.randomUUID(),
    })
    Object.assign(unsafe, { $set_once: { $initial_current_url: 'https://example.test/?email=private@example.test' }, $set: { email: 'private@example.test' }, $unset: ['private'] })
    const output = analytics.sanitizeCapture(unsafe)
    expect(output?.properties).toMatchObject({ complex_id: '17', entry_point: 'map', $current_url: `${location.origin}/`, $referrer: 'https://search.example/', $geoip_disable: true, $ip: null })
    expect(JSON.stringify(output)).not.toMatch(/private|password|secret|latitude|user_id|\$set/)
    expect(analytics.captureProductEvent('arbitrary_private_event', { email: 'secret' })).toBe(false)
  })

  it('fails closed on team exclusion and unavailable storage and stops cross-tab capture', async () => {
    const analytics = await initialized()
    localStorage.setItem('toadzip.analytics.disabled', 'true')
    window.dispatchEvent(new StorageEvent('storage', { key: 'toadzip.analytics.disabled', newValue: 'true' }))
    expect(sdk.stopSessionRecording).toHaveBeenCalled()
    expect(sdk.opt_out_capturing).not.toHaveBeenCalled()
    expect(analytics.captureProductEvent('view_complex', { complex_id: '17' })).toBe(false)
    expect(analytics.sanitizeCapture(captured({ $snapshot_data: [] }, '$snapshot'))).toBeNull()
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked') })
    expect(analytics.captureProductEvent('page_view')).toBe(false)
  })

  it('disables automatic collection and prevents sensitive replay while preserving safe snapshots', async () => {
    const analytics = await initialized()
    const config = sdk.init.mock.calls[0]?.[1] as Partial<PostHogConfig>
    expect(config).toMatchObject({ autocapture: false, capture_pageview: false, capture_pageleave: false, capture_exceptions: false, ip: false, person_profiles: 'always' })
    expect(config.session_recording).toMatchObject({ maskAllInputs: true, maskTextSelector: '*', recordBody: false, recordHeaders: false, recordCrossOriginIframes: false, captureJsonLd: false })
    expect(analytics.sanitizeCapture(captured({ $snapshot_data: [] }, '$snapshot'))).not.toBeNull()
    analytics.setReplaySensitive('email', true)
    expect(sdk.stopSessionRecording).toHaveBeenCalled()
    expect(analytics.sanitizeCapture(captured({ $snapshot_data: [] }, '$snapshot'))).toBeNull()
    analytics.setReplaySensitive('email', false)
    expect(sdk.startSessionRecording).toHaveBeenCalled()
    expect(analytics.maskReplayAttribute('href', 'https://secret.example/?email=private')).toBe('')
    expect(analytics.maskReplayAttribute('aria-label', 'private@example.test')).toBe('')
    expect(analytics.maskReplayAttribute('style', 'background-image:url(secret)')).toBe('')
  })

  it('preserves queued page views across StrictMode cleanup while the SDK loads', async () => {
    const analytics = await import('./productAnalytics')
    analytics.captureProductEvent('page_view', {}, { dedupeKey: 'page:strict-mode' })
    analytics.setProductPageActive(false)
    analytics.setProductPageActive(true)
    analytics.captureProductEvent('page_view', {}, { dedupeKey: 'page:strict-mode' })
    await vi.dynamicImportSettled()
    expect(sdk.capture.mock.calls.filter(([name]) => name === 'page_view')).toHaveLength(1)
  })

  it('recovers after leaving the public page before the SDK import finishes', async () => {
    const analytics = await import('./productAnalytics')
    analytics.captureProductEvent('page_view')
    window.history.replaceState({}, '', '/admin')
    analytics.setProductPageActive(false)
    await vi.dynamicImportSettled()
    expect(sdk.init).not.toHaveBeenCalled()

    window.history.replaceState({}, '', '/')
    analytics.setProductPageActive(true)
    analytics.captureProductEvent('view_complex', { complex_id: '17' })
    await vi.dynamicImportSettled()
    expect(sdk.init).toHaveBeenCalledOnce()
    expect(sdk.capture).toHaveBeenCalledWith('view_complex', expect.objectContaining({ complex_id: '17' }), expect.anything())
    expect(sdk.capture.mock.calls.some(([name]) => name === 'page_view')).toBe(false)
  })

  it('retains sanitized recorder page metadata but rejects network payloads', async () => {
    const analytics = await initialized()
    const mask = analytics.productAnalyticsOptions().session_recording?.maskCapturedNetworkRequestFn
    // The recorder also invokes this typed network hook with bare Meta URL data.
    const meta = { name: 'https://example.test/private?email=private@example.test#token' } as Parameters<NonNullable<typeof mask>>[0]
    expect(mask?.(meta)).toEqual({ name: 'https://example.test/' })
    expect(mask?.({ name: 'https://example.test/api', method: 'POST', startTime: 0, duration: 1, entryType: 'resource' })).toBeNull()
  })
})
