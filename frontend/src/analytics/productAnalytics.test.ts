import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CaptureResult, PostHogConfig } from 'posthog-js/no-external'

const sdk = vi.hoisted(() => ({
  init: vi.fn(), capture: vi.fn(), startSessionRecording: vi.fn(), stopSessionRecording: vi.fn(),
  _send_request: vi.fn(), _send_retriable_request: vi.fn(),
  _retryQueue: { retriableRequest: vi.fn(), _enqueue: vi.fn() }, shutdown: vi.fn(),
  opt_in_capturing: vi.fn(), opt_out_capturing: vi.fn(), identify: vi.fn(), alias: vi.fn(), reset: vi.fn(),
}))
vi.mock('posthog-js/no-external', () => ({ PostHog: function () { return sdk } }))
let listeners: EventListenerOrEventListenerObject[] = []

beforeEach(() => {
  consentGate.allowed = true
  consentGate.stops = []
  vi.resetModules()
  vi.clearAllMocks()
  sdk.init.mockImplementation((_key, options) => { options.loaded?.(sdk); return sdk })
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
    ['subway_station', 'naver-subway:테스트역:테스트 주소:37.123:127.456'],
    ['region', 'naver-region:테스트 지역'],
  ])('외부 위치 검색의 %s 식별자와 상세 위치를 제거한다', async (resultType, resultId) => {
    const analytics = await initialized()
    const output = analytics.sanitizeCapture(captured({
      result_type: resultType, result_id: resultId, region_code: resultId,
      title: '테스트역', address: '테스트 주소', latitude: 37.123, longitude: 127.456,
    }, 'select_search_result'))
    expect(output).not.toBeNull()
    expect(output?.properties).not.toHaveProperty('result_id')
    expect(output?.properties).not.toHaveProperty('region_code')
    expect(JSON.stringify(output)).not.toMatch(/naver-|테스트|37\.123|127\.456|latitude|longitude/)
  })

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

  it('collects only safe explicit events on notification settings and never records its replay', async () => {
    window.history.replaceState({}, '', '/mypage/notifications?email=private@example.test#secret')
    const analytics = await initialized()
    analytics.setProductAuthState('member')
    expect(analytics.captureProductEvent('notification_cancel_requested', {
      target_type: 'COMPLEX', target_id: '17', source: 'SETTING', email: 'private@example.test',
    })).toBe(true)
    expect(sdk.capture).toHaveBeenCalledWith('notification_cancel_requested',
      expect.objectContaining({ page_name: 'notification_settings', auth_state: 'member', source: 'SETTING' }), expect.anything())
    const output = analytics.sanitizeCapture(captured({
      page_name: 'notification_settings', target_type: 'COMPLEX', target_id: '17',
      source: 'SETTING', email: 'private@example.test',
    }, 'notification_cancel_requested'))
    expect(output?.properties).toMatchObject({
      page_name: 'notification_settings', $pathname: '/mypage/notifications', $current_url: `${location.origin}/`,
    })
    expect(JSON.stringify(output)).not.toMatch(/private|secret/)
    expect(sdk.startSessionRecording).not.toHaveBeenCalled()
    expect(analytics.sanitizeCapture(captured({ $snapshot_data: [] }, '$snapshot'))).toBeNull()
  })

  it('uses one SDK instance, memory persistence and occurrence-time authentication', async () => {
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
    expect(sdk.opt_out_capturing).toHaveBeenCalled()
    expect(sdk.shutdown).toHaveBeenCalled()
    expect(analytics.captureProductEvent('view_complex', { complex_id: '17' })).toBe(false)
    expect(analytics.sanitizeCapture(captured({ $snapshot_data: [] }, '$snapshot'))).toBeNull()
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked') })
    expect(analytics.captureProductEvent('page_view')).toBe(false)
  })

  it('disables profiles, persistent SDK storage and all session replay', async () => {
    const analytics = await initialized()
    const config = sdk.init.mock.calls[0]?.[1] as Partial<PostHogConfig>
    expect(config).toMatchObject({ autocapture: false, capture_pageview: false, capture_pageleave: false, capture_exceptions: false, ip: false, person_profiles: 'never', persistence: 'memory', opt_out_capturing_by_default: true, opt_out_persistence_by_default: true })
    expect(config.session_recording).toMatchObject({ maskAllInputs: true, maskTextSelector: '*', recordBody: false, recordHeaders: false, recordCrossOriginIframes: false, captureJsonLd: false })
    expect(analytics.sanitizeCapture(captured({ $snapshot_data: [] }, '$snapshot'))).toBeNull()
    analytics.setReplaySensitive('email', true)
    expect(sdk.stopSessionRecording).toHaveBeenCalled()
    expect(analytics.sanitizeCapture(captured({ $snapshot_data: [] }, '$snapshot'))).toBeNull()
    analytics.setReplaySensitive('email', false)
    expect(sdk.startSessionRecording).not.toHaveBeenCalled()
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


const consentGate = vi.hoisted(() => ({ allowed: true, stops: [] as Array<() => void> }))
vi.mock('../privacy/consentStore', () => ({ analyticsCollectionAllowed: () => consentGate.allowed, consentStore: { onStop: (callback: () => void) => { consentGate.stops.push(callback); return () => {} } } }))

it('does not initialize or retain pre-consent events and clears the SDK on withdrawal', async () => {
  consentGate.allowed = false
  const analytics = await import('./productAnalytics')
  expect(analytics.captureProductEvent('view_complex', { complex_id: '17' })).toBe(false)
  await vi.dynamicImportSettled()
  expect(sdk.init).not.toHaveBeenCalled()
  consentGate.allowed = true
  analytics.captureProductEvent('page_view')
  await vi.dynamicImportSettled()
  expect(sdk.capture.mock.calls.some(([name]) => name === 'view_complex')).toBe(false)
  consentGate.allowed = false
  consentGate.stops.forEach(stop => stop())
  expect(sdk.opt_out_capturing).toHaveBeenCalled()
  expect(analytics.captureProductEvent('view_complex', { complex_id: '17' })).toBe(false)
})


it('opts the SDK in only after a valid choice, and starts fresh after withdrawal/regrant', async () => {
  const analytics = await initialized()
  expect(sdk.opt_in_capturing).toHaveBeenCalledWith({ captureEventName: false })
  consentGate.allowed = false
  consentGate.stops.forEach(stop => stop())
  consentGate.allowed = true
  expect(analytics.captureProductEvent('view_complex', { complex_id: '22' })).toBe(true)
  await vi.dynamicImportSettled()
  expect(sdk.init).toHaveBeenCalledTimes(2)
  expect(sdk.opt_in_capturing).toHaveBeenCalledTimes(2)
})


it('drops a revoked import even if permission returns before that old import resolves', async () => {
  const analytics = await import('./productAnalytics')
  analytics.captureProductEvent('view_complex', { complex_id: '17' })
  consentGate.allowed = false
  consentGate.stops.forEach(stop => stop())
  consentGate.allowed = true
  expect(analytics.captureProductEvent('view_complex', { complex_id: '22' })).toBe(true)
  await vi.dynamicImportSettled()
  expect(sdk.init).toHaveBeenCalledOnce()
  expect(sdk.capture.mock.calls.filter(([name]) => name === 'view_complex').map(([, properties]) => properties.complex_id)).toEqual(['22'])
})

it('retains local outcome deduplication without forwarding its member-linked ledger IDs', async () => {
  const analytics = await initialized()
  const ledgerId = '62c3958e-7d67-4cb6-a929-b574989af148'
  const properties = { target_type: 'COMPLEX', target_id: '17', server_event_id: ledgerId, notification_action_id: ledgerId }
  const options = { dedupeKey: `notification-completed:${ledgerId}` }
  expect(analytics.captureProductEvent('notification_preregistration_completed', properties, options)).toBe(true)
  expect(analytics.captureProductEvent('notification_preregistration_completed', properties, options)).toBe(false)
  const outcome = sdk.capture.mock.calls.find(([name]) => name === 'notification_preregistration_completed')
  expect(outcome?.[1]).toMatchObject({ target_type: 'COMPLEX', target_id: '17' })
  expect(JSON.stringify(outcome)).not.toContain(ledgerId)
  expect(outcome?.[1]).not.toHaveProperty('server_event_id')
  expect(outcome?.[1]).not.toHaveProperty('notification_action_id')
})
