import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { guardPostHogTransport } from './posthogTransport'
import { productAnalyticsOptions } from './productAnalytics'

const consent = vi.hoisted(() => ({ allowed: true }))
vi.mock('../privacy/consentStore', () => ({
  analyticsCollectionAllowed: () => consent.allowed,
  consentStore: {
    getSnapshot: () => ({ context: { consent: { scopeVersion: 'analytics-scope-3' }, requiredScopeVersion: 'analytics-scope-3' } }),
    remainingConsentLifetime: () => 180 * 24 * 60 * 60 * 1000,
    transition: () => { consent.allowed = false }, refresh: async () => {},
    onStop: () => () => {}, subscribe: () => () => {},
  },
}))
const network = vi.fn<typeof fetch>()
const transports: Array<ReturnType<typeof guardPostHogTransport>> = []

beforeEach(() => {
  vi.useFakeTimers()
  consent.allowed = true
  network.mockReset().mockImplementation(function (this: unknown) {
    // Native Chromium fetch rejects a CommonJS exports object as its receiver.
    if (this !== undefined && this !== window) throw new TypeError('Illegal invocation')
    return Promise.resolve(new Response('{}', { status: 200 }))
  })
  vi.stubGlobal('fetch', network)
  Object.defineProperty(navigator, 'locks', { configurable: true,
    value: { request: vi.fn(async (_name: string, callback: () => unknown) => callback()) },
  })
  localStorage.clear()
  sessionStorage.clear()
  window.history.replaceState({}, '', '/explore?email=private@example.test#secret')
})

afterEach(() => {
  transports.splice(0).forEach(transport => transport.stop())
  vi.clearAllTimers()
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

async function sdk(batch = false, onResponse?: (eventId: string, statusCode: number) => void) {
  // Installed browser bundle: preserves the transport boundaries without the deep CJS fetch receiver bug.
  const { PostHog } = await import('posthog-js/no-external')
  const instance = new PostHog()
  const transport = guardPostHogTransport(instance, () => consent.allowed, onResponse)
  transports.push(transport)
  instance.init('phc_localtesttoken', {
    ...productAnalyticsOptions(), request_batching: batch,
    bootstrap: { distinctID: crypto.randomUUID(), isIdentifiedID: false },
    loaded: () => { transport.ready(); instance.opt_in_capturing({ captureEventName: false }) },
  })
  return { instance, transport }
}

it('the installed SDK sends explicit sanitized events after opt-in without remote config or automatic events', async () => {
  const { instance } = await sdk()
  expect(network).not.toHaveBeenCalled()
  instance.capture('view_complex', { complex_id: '17', email: 'private@example.test', user_id: '123', query: 'secret' })
  expect(network).toHaveBeenCalledOnce()
  const [url, options] = network.mock.calls[0]
  expect(String(url)).toContain('/e/')
  expect(options).toMatchObject({ credentials: 'omit', referrerPolicy: 'no-referrer', keepalive: false })
  const body = String(options?.body)
  expect(body).toContain('view_complex')
  expect(body).toContain('complex_id')
  expect(body).not.toMatch(/private|secret|user_id|\$set|\$opt_in|\$pageview/)
})

it('never sends notification ledger identifiers that could join an external event to a member', async () => {
  const { instance } = await sdk()
  const ledgerId = '62c3958e-7d67-4cb6-a929-b574989af148'
  instance.capture('notification_preregistration_completed', {
    target_type: 'COMPLEX', target_id: '17',
    server_event_id: ledgerId, notification_action_id: ledgerId,
  })
  expect(network).toHaveBeenCalledOnce()
  const body = String(network.mock.calls[0][1]?.body)
  expect(body).toContain('notification_preregistration_completed')
  expect(body).toContain('target_id')
  expect(body).not.toContain(ledgerId)
  expect(body).not.toMatch(/server_event_id|notification_action_id/)
})

it.each(['view_complex', '$pageview'])('drops a real %s retry and never flushes it on online, pagehide or a later grant', async event => {
  network.mockResolvedValue(new Response('{}', { status: 503 }))
  const { instance, transport } = await sdk()
  instance.capture(event, { complex_id: '17' })
  await vi.advanceTimersByTimeAsync(0)
  expect(instance._retryQueue?.length).toBe(1)
  consent.allowed = false
  transport.stop()
  expect(instance._retryQueue?.length).toBe(0)
  consent.allowed = true
  window.dispatchEvent(new Event('online'))
  window.dispatchEvent(new Event('pagehide'))
  await vi.advanceTimersByTimeAsync(60_000)
  expect(network).toHaveBeenCalledOnce()
  const fresh = await sdk()
  fresh.instance.capture('view_complex', { complex_id: '22' })
  expect(network).toHaveBeenCalledTimes(2)
  expect(String(network.mock.calls[1][1]?.body)).toContain('22')
})

it.each(['view_complex', '$pageview'])('aborts an in-flight %s fetch and prevents its late failure from recreating a retry queue', async event => {
  let reject: (error: Error) => void = () => { throw new Error('request missing') }
  network.mockImplementation(() => new Promise((_resolve, rejectRequest) => { reject = rejectRequest }))
  const { instance, transport } = await sdk()
  instance.capture(event, { complex_id: '17' })
  const signal = network.mock.calls[0][1]?.signal
  expect(signal?.aborted).toBe(false)
  consent.allowed = false
  transport.stop()
  expect(signal?.aborted).toBe(true)
  reject(new TypeError('Network request aborted'))
  await vi.advanceTimersByTimeAsync(60_000)
  expect(instance._retryQueue?.length).toBe(0)
  expect(network).toHaveBeenCalledOnce()
})

it('sends manual pageviews with the existing browser/session fields and no extra URL or automatic data', async () => {
  const { instance } = await sdk()
  expect(network).not.toHaveBeenCalled()
  instance.capture('page_view')
  instance.capture('$pageview', { $pathname: '/explore', query: 'secret', email: 'private@example.test' })
  instance.capture('view_complex', { complex_id: '17' })
  const events = network.mock.calls.map(([, options]) => JSON.parse(String(options?.body)).batch[0])
  expect(events.map(event => event.event)).toEqual(['page_view', '$pageview', 'view_complex'])
  const properties = events[1].properties
  expect(properties).toMatchObject({
    distinct_id: expect.any(String), $session_id: expect.any(String), $window_id: expect.any(String),
    identity_basis: 'browser_v1', $pathname: '/explore', $current_url: `${location.origin}/`,
    $geoip_disable: true, $ip: null,
  })
  for (const key of ['distinct_id', '$device_id', '$session_id', '$window_id']) {
    expect(new Set(events.map(event => event.properties[key])).size).toBe(1)
  }
  expect(JSON.stringify(events)).not.toMatch(/private|secret|\$set|utm_|\$referring_domain|\$geoip_country|\$prev_pageview/)
  instance.capture('$pageleave')
  instance.capture('$autocapture')
  instance.capture('$snapshot')
  window.dispatchEvent(new Event('pagehide'))
  expect(network).toHaveBeenCalledTimes(3)
})

it('discards real SDK batched requests on withdrawal instead of shutdown-flushing them', async () => {
  const { instance, transport } = await sdk(true)
  instance.capture('view_complex', { complex_id: '17' })
  expect(network).not.toHaveBeenCalled()
  consent.allowed = false
  transport.stop()
  consent.allowed = true
  window.dispatchEvent(new Event('pagehide'))
  await vi.advanceTimersByTimeAsync(60_000)
  expect(network).not.toHaveBeenCalled()
})


it('acknowledges the real SDK retry path once after success, not when capture queues a retry', async () => {
  network.mockResolvedValueOnce(new Response('{}', { status: 503 }))
  const response = vi.fn()
  const { instance } = await sdk(false, response)
  const eventId = crypto.randomUUID()
  instance.capture('view_complex', { complex_id: '17', event_id: eventId }, { uuid: eventId })
  await vi.advanceTimersByTimeAsync(0)
  expect(response).not.toHaveBeenCalled()
  expect(instance._retryQueue?.length).toBe(1)
  await vi.advanceTimersByTimeAsync(120_000)
  expect(response).toHaveBeenCalledExactlyOnceWith(eventId, 200)
  expect(network).toHaveBeenCalledTimes(2)
})

it('does not acknowledge a late successful response from a stopped transport', async () => {
  let resolve: (value: Response) => void = () => { throw new Error('request missing') }
  network.mockImplementation(() => new Promise(finish => { resolve = finish }))
  const response = vi.fn()
  const { instance, transport } = await sdk(false, response)
  instance.capture('view_complex', { complex_id: '17', event_id: crypto.randomUUID() })
  transport.stop()
  resolve(new Response('{}', { status: 200 }))
  await vi.advanceTimersByTimeAsync(0)
  expect(response).not.toHaveBeenCalled()
})
