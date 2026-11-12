import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import type { AnalyticsStopReason } from '../privacy/consentStore'

const day = 24 * 60 * 60 * 1000
const key = 'toadzip.posthog.browser.v1:phc_testpublictoken'
const consent = vi.hoisted(() => ({ allowed: true, scope: 'analytics-scope-4', expiresAt: 0,
  stops: [] as Array<(reason: AnalyticsStopReason) => void>, listeners: [] as Array<() => void>,
}))
function stop(reason: AnalyticsStopReason) {
  consent.allowed = false
  consent.stops.forEach(listener => listener(reason))
}
function allow() { consent.allowed = true; consent.listeners.forEach(listener => listener()) }
vi.mock('../privacy/consentStore', () => ({ analyticsCollectionAllowed: () => consent.allowed,
  consentStore: {
    getSnapshot: () => ({ context: { consent: { scopeVersion: consent.scope }, requiredScopeVersion: consent.scope } }),
    remainingConsentLifetime: () => Math.max(0, consent.expiresAt - Date.now()),
    onStop: (listener: (reason: AnalyticsStopReason) => void) => { consent.stops.push(listener); return () => {} },
    subscribe: (listener: () => void) => { consent.listeners.push(listener); return () => {} },
    transition: () => stop('context-change'),
    refresh: async () => { if (Date.now() < consent.expiresAt) allow(); else stop('reset') },
  },
}))
const network = vi.fn<typeof fetch>()
const lockRequest = vi.fn<(name: string, callback: () => unknown) => Promise<unknown>>()
let storageListeners: EventListenerOrEventListenerObject[] = []
function removeStorageListeners() { storageListeners.forEach(listener => window.removeEventListener('storage', listener)); storageListeners = [] }
async function settled() { await vi.dynamicImportSettled(); await vi.advanceTimersByTimeAsync(0) }
function payload(index: number) { return JSON.parse(String(network.mock.calls[index]?.[1]?.body)).batch[0] }
function record() { return JSON.parse(localStorage.getItem(key)!) as { id: string; createdAt: number; expiresAt: number } }
async function start() {
  const analytics = await import('./productAnalytics')
  analytics.captureProductEvent('view_complex', { complex_id: '17' })
  await settled()
  return analytics
}
function unload() {
  stop('pause'); vi.clearAllTimers(); removeStorageListeners()
  consent.stops = []; consent.listeners = []; vi.resetModules()
}

beforeEach(() => {
  vi.resetModules(); vi.useFakeTimers(); vi.setSystemTime(new Date('2026-10-11T00:00:00Z'))
  consent.allowed = true; consent.scope = 'analytics-scope-4'; consent.expiresAt = Date.now() + 180 * day
  consent.stops = []; consent.listeners = []
  network.mockReset().mockResolvedValue(new Response('{}', { status: 200 }))
  vi.stubGlobal('fetch', network)
  vi.stubEnv('VITE_POSTHOG_KEY', 'phc_testpublictoken'); vi.stubEnv('VITE_POSTHOG_HOST', 'https://us.i.posthog.com')
  vi.stubEnv('VITE_ANALYTICS_ENV', 'dev'); vi.stubEnv('VITE_POSTHOG_LOCAL_ENABLED', 'true'); vi.stubEnv('MODE', 'development')
  lockRequest.mockReset().mockImplementation(async (_name, callback) => callback())
  Object.defineProperty(navigator, 'locks', { configurable: true, value: { request: lockRequest } })
  localStorage.clear(); sessionStorage.clear(); window.history.replaceState({}, '', '/explore')
  const add = window.addEventListener.bind(window)
  vi.spyOn(window, 'addEventListener').mockImplementation((type, listener, options) => {
    if (type === 'storage' && listener) storageListeners.push(listener)
    add(type, listener, options)
  })
})
afterEach(() => {
  stop('reset'); removeStorageListeners(); vi.clearAllTimers(); vi.useRealTimers()
  vi.unstubAllGlobals(); vi.unstubAllEnvs(); vi.restoreAllMocks()
})

// scope-3 is the grant given before the overseas-transfer notice; it must not carry over either.
it.each(['analytics-scope-2', 'analytics-scope-3'])('requires consent to the current scope, with no ID or retroactive event for an older %s grant', async (olderScope) => {
  consent.scope = olderScope
  const analytics = await start()
  expect(network).not.toHaveBeenCalled()
  expect(localStorage.getItem(key)).toBeNull()
  consent.scope = 'analytics-scope-4'; allow()
  await settled()
  expect(network).not.toHaveBeenCalled()
  analytics.captureProductEvent('view_announcement', { announcement_id: '22' })
  await settled()
  expect(network).toHaveBeenCalledOnce()
  expect(payload(0).properties).toMatchObject({ identity_basis: 'browser_v1', $is_identified: false, $process_person_profile: false })
})

it('links reload and next-day activity to the same browser without extending either timestamp', async () => {
  await start()
  const first = record()
  unload(); allow(); await start()
  expect(payload(1).properties.distinct_id).toBe(first.id)
  expect(record()).toEqual(first)
  unload(); vi.setSystemTime(Date.now() + day); consent.expiresAt += day; allow(); await start()
  expect(payload(2).properties.distinct_id).toBe(first.id)
  expect(payload(2).properties.$device_id).toBe(first.id)
  expect(record()).toEqual(first)
  expect(JSON.stringify(record())).not.toMatch(/user|member|context|email/)
})

it('never reuses an expired 180-day identity on a later execution, even with renewed consent', async () => {
  await start()
  const first = record()
  unload(); vi.setSystemTime(first.expiresAt); consent.expiresAt = Date.now() + 180 * day; allow(); await start()
  expect(record().id).not.toBe(first.id)
  expect(record().createdAt).toBe(Date.now())
  expect(payload(1).properties.distinct_id).toBe(record().id)
})

it('deletes the stored ID at the earlier consent deadline even while capture is paused', async () => {
  consent.expiresAt = Date.now() + 1000
  await start()
  expect(record().expiresAt).toBe(consent.expiresAt)
  stop('pause')
  await vi.advanceTimersByTimeAsync(1000)
  expect(localStorage.getItem(key)).toBeNull()
  expect(network).toHaveBeenCalledOnce()
})

it('removes the ID on withdrawal and uses a new unlinked identity after reconsent', async () => {
  await start()
  const first = record().id
  stop('reset')
  expect(localStorage.getItem(key)).toBeNull()
  allow(); await start()
  expect(payload(1).properties.distinct_id).not.toBe(first)
  expect(String(network.mock.calls[1][1]?.body)).not.toMatch(/\$identify|\$set|\$anon_distinct_id/)
})

it('stops an old tab when another tab removes its ID and checks permission before replacing it', async () => {
  const analytics = await start()
  localStorage.removeItem(key)
  consent.expiresAt = Date.now()
  window.dispatchEvent(new StorageEvent('storage', { key, newValue: null }))
  await settled()
  expect(analytics.captureProductEvent('view_complex', { complex_id: '17' })).toBe(false)
  expect(localStorage.getItem(key)).toBeNull()
  expect(network).toHaveBeenCalledOnce()
})

it('does not fall back to per-run IDs when storage cannot persist the identity', async () => {
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked') })
  await start()
  expect(network).not.toHaveBeenCalled()
  expect(localStorage.getItem(key)).toBeNull()
})

it('does not delete another tab’s new ID when an old grant replacement is observed late', async () => {
  await start()
  const first = record()
  const replacement = { ...first, id: crypto.randomUUID() }
  localStorage.setItem(key, JSON.stringify(replacement))
  // Another tab already completed withdrawal/reconsent. This tab still owns
  // the old ID because its server/storage notifications were delayed.
  stop('consent-replaced'); allow(); await start()
  expect(record()).toEqual(replacement)
  expect(payload(1).properties.distinct_id).toBe(replacement.id)
})

it('withdrawal after the storage write but before SDK initialization still deletes that identity', async () => {
  lockRequest.mockImplementationOnce(async (_name, callback) => {
    const result = await callback()
    stop('reset')
    return result
  })
  await start()
  expect(localStorage.getItem(key)).toBeNull()
  expect(network).not.toHaveBeenCalled()
})
