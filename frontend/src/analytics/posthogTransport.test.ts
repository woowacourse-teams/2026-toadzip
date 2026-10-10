import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { guardPostHogTransport } from './posthogTransport'
import { productAnalyticsOptions } from './productAnalytics'

const consent = vi.hoisted(() => ({ allowed: true }))
vi.mock('../privacy/consentStore', () => ({
  analyticsCollectionAllowed: () => consent.allowed,
  consentStore: { onStop: () => () => {} },
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
  localStorage.clear()
  sessionStorage.clear()
  window.history.replaceState({}, '', '/?email=private@example.test#secret')
})

afterEach(() => {
  transports.splice(0).forEach(transport => transport.stop())
  vi.clearAllTimers()
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

async function sdk(batch = false) {
  // Installed browser bundle: preserves the transport boundaries without the deep CJS fetch receiver bug.
  const { PostHog } = await import('posthog-js/no-external')
  const instance = new PostHog()
  const transport = guardPostHogTransport(instance, () => consent.allowed)
  transports.push(transport)
  instance.init('phc_localtesttoken', {
    ...productAnalyticsOptions(), request_batching: batch,
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

it('drops a real failed-request retry and never flushes it on online, pagehide or a later grant', async () => {
  network.mockResolvedValue(new Response('{}', { status: 503 }))
  const { instance, transport } = await sdk()
  instance.capture('view_complex', { complex_id: '17' })
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

it('aborts an in-flight fetch and prevents its late failure from recreating a retry queue', async () => {
  let reject: (error: Error) => void = () => { throw new Error('request missing') }
  network.mockImplementation(() => new Promise((_resolve, rejectRequest) => { reject = rejectRequest }))
  const { instance, transport } = await sdk()
  instance.capture('view_complex', { complex_id: '17' })
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
