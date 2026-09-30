import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AnalyticsEvents } from './googleAnalytics'

const ID = 'G-TEST123456'
const DISABLE_KEY = `ga-disable-${ID}` as const
const EXCLUSION_KEY = 'toadzip.analytics.disabled'
const SCRIPT_SELECTOR = 'script[data-toadzip-google-tag]'
let storageListeners: EventListenerOrEventListenerObject[] = []

function commands(): unknown[][] {
  return (window.dataLayer ?? []).map((command) => Array.from(command as IArguments))
}

function events(): unknown[][] {
  return commands().filter((command) => command[0] === 'event')
}

function script(): HTMLScriptElement {
  const element = document.querySelector<HTMLScriptElement>(SCRIPT_SELECTOR)
  if (!element) throw new Error('Expected Google tag script')
  return element
}

async function activeAnalytics() {
  const analytics = await import('./googleAnalytics')
  analytics.setAnalyticsPageActive(true)
  return analytics
}

beforeEach(() => {
  vi.resetModules()
  vi.useFakeTimers()
  vi.stubEnv('DEV', false)
  vi.stubEnv('VITE_GA_MEASUREMENT_ID', ID)
  vi.stubEnv('VITE_GA_DEBUG_MODE', '')
  window.localStorage.clear()
  window.history.replaceState({}, '', '/')
  delete window.dataLayer
  delete window.gtag
  delete window[DISABLE_KEY]
  document.querySelectorAll(SCRIPT_SELECTOR).forEach((element) => element.remove())
  storageListeners = []
  const originalAdd = window.addEventListener.bind(window)
  vi.spyOn(window, 'addEventListener').mockImplementation((type, listener, options) => {
    if (type === 'storage' && listener) storageListeners.push(listener)
    originalAdd(type, listener, options)
  })
})

afterEach(() => {
  storageListeners.forEach((listener) => window.removeEventListener('storage', listener))
  vi.clearAllTimers()
  vi.useRealTimers()
  vi.unstubAllEnvs()
  vi.restoreAllMocks()
  delete window.dataLayer
  delete window.gtag
  delete window[DISABLE_KEY]
  document.querySelectorAll(SCRIPT_SELECTOR).forEach((element) => element.remove())
})

describe('Google Analytics transport', () => {
  it.each(['', 'invalid', 'G-TEST?email=private@example.test'])(
    'does not load for missing or invalid measurement ID: %s', async (value) => {
      vi.stubEnv('VITE_GA_MEASUREMENT_ID', value)
      const { trackEvent } = await activeAnalytics()
      expect(trackEvent('page_view', {})).toBe(false)
      expect(document.querySelector(SCRIPT_SELECTOR)).toBeNull()
      expect(commands()).toEqual([])
    },
  )

  it('does not collect local development traffic by default', async () => {
    vi.stubEnv('DEV', true)
    const { trackEvent } = await activeAnalytics()
    expect(trackEvent('page_view', {})).toBe(false)
    expect(document.querySelector(SCRIPT_SELECTOR)).toBeNull()
  })

  it('allows explicitly enabled local debug collection', async () => {
    vi.stubEnv('DEV', true)
    vi.stubEnv('VITE_GA_DEBUG_MODE', 'true')
    const { trackEvent } = await activeAnalytics()
    expect(trackEvent('page_view', {})).toBe(true)
    script().dispatchEvent(new Event('load'))
    expect(commands()).toContainEqual(['config', ID, expect.objectContaining({ debug_mode: true, send_page_view: false })])
  })

  it.each(['/login', '/admin', '/admin/login', '/other'])(
    'does not collect outside the public explorer: %s', async (path) => {
      window.history.replaceState({}, '', path)
      const { trackEvent } = await activeAnalytics()
      expect(trackEvent('page_view', {})).toBe(false)
      expect(document.querySelector(SCRIPT_SELECTOR)).toBeNull()
    },
  )

  it('loads one tag and only submits sanitized commands after the SDK loads', async () => {
    window.history.replaceState({}, '', '/?email=private@example.test&utm_source=secret&latitude=37.123#private')
    document.title = 'private@example.test'
    vi.spyOn(document, 'referrer', 'get').mockReturnValue('https://user:password@search.example/private?q=secret#private')
    const { trackEvent } = await activeAnalytics()
    trackEvent('page_view', {})
    trackEvent('view_complex', { complex_id: '123', entry_point: 'direct' })
    const tag = script()
    expect(commands()).toEqual([])
    expect(tag.src).toBe(`https://www.googletagmanager.com/gtag/js?id=${ID}`)
    expect(tag.async).toBe(true)
    expect(tag.referrerPolicy).toBe('no-referrer')
    expect(document.querySelectorAll(SCRIPT_SELECTOR)).toHaveLength(1)
    tag.dispatchEvent(new Event('load'))
    const context = {
      page_location: `${window.location.origin}/`,
      page_title: '공공주택 복덕방',
      page_referrer: 'https://search.example/',
      allow_google_signals: false,
      allow_ad_personalization_signals: false,
    }
    expect(commands().slice(0, 3)).toEqual([
      ['set', context], ['js', expect.any(Date)], ['config', ID, { ...context, send_page_view: false }],
    ])
    expect(events()).toEqual([
      ['event', 'page_view', { ...context, send_to: ID }],
      ['event', 'view_complex', { complex_id: '123', entry_point: 'direct', ...context, send_to: ID }],
    ])
    expect(JSON.stringify(commands())).not.toMatch(/private|secret|latitude|password|utm_source/)
  })

  it.each(['', 'about:blank', 'javascript:alert(1)', 'invalid'])(
    'omits unusable referrers: %s', async (referrer) => {
      vi.spyOn(document, 'referrer', 'get').mockReturnValue(referrer)
      const { trackEvent } = await activeAnalytics()
      trackEvent('page_view', {})
      script().dispatchEvent(new Event('load'))
      expect(events()[0][2]).toEqual(expect.objectContaining({ page_referrer: '' }))
    },
  )

  it('omits same-origin login referrers', async () => {
    vi.spyOn(document, 'referrer', 'get').mockReturnValue(`${window.location.origin}/login?contact=private`)
    const { trackEvent } = await activeAnalytics()
    trackEvent('page_view', {})
    script().dispatchEvent(new Event('load'))
    expect(events()[0][2]).toEqual(expect.objectContaining({ page_referrer: '' }))
  })

  it('removes extra fields and sends only permitted event parameters', async () => {
    const { trackEvent } = await activeAnalytics()
    const detail = { complex_id: '17', entry_point: 'map' as const, email: 'private@example.test' }
    trackEvent('view_complex', detail)
    trackEvent('view_announcement', { announcement_id: '19', entry_point: 'detail' })
    trackEvent('select_search_result', { result_type: 'complex', complex_id: '17', announcement_id: '999' })
    trackEvent('select_search_result', { result_type: 'announcement', announcement_id: '19' })
    trackEvent('select_search_result', { result_type: 'region', complex_id: '999' })
    trackEvent('apply_filter', { filter_target: 'complex', filter_types: 'region,deposit', filter_count: 2 })
    trackEvent('apply_filter', { filter_target: 'announcement', filter_types: 'none', filter_count: 0 })
    script().dispatchEvent(new Event('load'))
    expect(events()).toHaveLength(7)
    expect(events()[2][2]).toEqual(expect.objectContaining({ result_type: 'complex', complex_id: '17' }))
    expect(events()[2][2]).not.toHaveProperty('announcement_id')
    expect(events()[4][2]).not.toHaveProperty('complex_id')
    expect(JSON.stringify(commands())).not.toMatch(/private|email|999/)
    expect(events().some((event) => event[1] === 'page_view')).toBe(false)
  })

  it.each(['', '0', '-1', '1.2', 'complex-1', 'private@example.test', '1?email=private', '9'.repeat(20)])(
    'rejects non-public or malformed IDs: %s', async (id) => {
      const { trackEvent } = await activeAnalytics()
      expect(trackEvent('view_complex', { complex_id: id, entry_point: 'direct' })).toBe(false)
      expect(document.querySelector(SCRIPT_SELECTOR)).toBeNull()
    },
  )

  it('rejects invalid event values at the SDK boundary', async () => {
    const { trackEvent } = await activeAnalytics()
    expect(trackEvent('view_complex', { complex_id: '17', entry_point: 'private@example.test' } as unknown as AnalyticsEvents['view_complex'])).toBe(false)
    expect(trackEvent('select_search_result', { result_type: 'complex' })).toBe(false)
    expect(trackEvent('apply_filter', { filter_target: 'complex', filter_types: 'email', filter_count: 1 })).toBe(false)
    expect(trackEvent('apply_filter', { filter_target: 'complex', filter_types: 'region,region', filter_count: 2 })).toBe(false)
    expect(trackEvent('apply_filter', { filter_target: 'complex', filter_types: 'region', filter_count: 2 })).toBe(false)
    expect(trackEvent('apply_filter', { filter_target: 'announcement', filter_types: 'deposit', filter_count: 1 })).toBe(false)
    expect(document.querySelector(SCRIPT_SELECTOR)).toBeNull()
  })

  it('honors persistent browser exclusion before loading', async () => {
    window.localStorage.setItem(EXCLUSION_KEY, 'true')
    const { trackEvent } = await activeAnalytics()
    expect(trackEvent('page_view', {})).toBe(false)
    expect(window[DISABLE_KEY]).toBe(true)
    expect(document.querySelector(SCRIPT_SELECTOR)).toBeNull()
    expect(window.localStorage.getItem(EXCLUSION_KEY)).toBe('true')
  })

  it('preserves both preexisting opt-outs and external writes while paused', async () => {
    window[DISABLE_KEY] = true
    const { setAnalyticsPageActive, trackEvent } = await activeAnalytics()
    setAnalyticsPageActive(false)
    setAnalyticsPageActive(true)
    expect(trackEvent('page_view', {})).toBe(false)
    window[DISABLE_KEY] = false
    expect(trackEvent('page_view', {})).toBe(true)
    script().dispatchEvent(new Event('load'))
    setAnalyticsPageActive(false)
    window[DISABLE_KEY] = true
    setAnalyticsPageActive(true)
    expect(trackEvent('page_view', {})).toBe(false)
    expect(events()).toHaveLength(1)
  })

  it('does not replace an existing disable accessor', async () => {
    const existingGate = () => true
    Object.defineProperty(window, DISABLE_KEY, { configurable: true, get: existingGate })
    const { trackEvent } = await activeAnalytics()
    expect(trackEvent('page_view', {})).toBe(false)
    expect(Object.getOwnPropertyDescriptor(window, DISABLE_KEY)?.get).toBe(existingGate)
    expect(document.querySelector(SCRIPT_SELECTOR)).toBeNull()
  })

  it('fails closed when the disable property cannot be safely owned', async () => {
    const originalDescriptor = Object.getOwnPropertyDescriptor
    vi.spyOn(Object, 'getOwnPropertyDescriptor').mockImplementation((object, key) => {
      if (object === window && key === DISABLE_KEY) return { configurable: false, value: true }
      return originalDescriptor(object, key)
    })
    const { trackEvent } = await activeAnalytics()
    expect(trackEvent('page_view', {})).toBe(false)
    expect(document.querySelector(SCRIPT_SELECTOR)).toBeNull()
  })

  it('clears waiting events on leave and resumes without another script', async () => {
    const { setAnalyticsPageActive, trackEvent } = await activeAnalytics()
    trackEvent('view_complex', { complex_id: '17', entry_point: 'direct' })
    window.history.replaceState({}, '', '/login?private=true')
    setAnalyticsPageActive(false)
    script().dispatchEvent(new Event('load'))
    expect(commands()).toEqual([])
    expect(window[DISABLE_KEY]).toBe(true)
    window.history.replaceState({}, '', '/')
    setAnalyticsPageActive(true)
    expect(trackEvent('page_view', {})).toBe(true)
    expect(events()).toHaveLength(1)
    expect(events()[0][1]).toBe('page_view')
    expect(document.querySelectorAll(SCRIPT_SELECTOR)).toHaveLength(1)
  })

  it('guards the actual route before the route effect deactivates analytics', async () => {
    const { trackEvent } = await activeAnalytics()
    trackEvent('page_view', {})
    window.history.replaceState({}, '', '/admin')
    script().dispatchEvent(new Event('load'))
    expect(commands()).toEqual([])
    expect(window[DISABLE_KEY]).toBe(true)
  })

  it.each([EXCLUSION_KEY, null])('discards waiting events after another tab excludes the browser: %s', async (key) => {
    const { trackEvent } = await activeAnalytics()
    trackEvent('page_view', {})
    window.localStorage.setItem(EXCLUSION_KEY, 'true')
    window.dispatchEvent(new StorageEvent('storage', { key, newValue: 'true' }))
    window.localStorage.removeItem(EXCLUSION_KEY)
    window.dispatchEvent(new StorageEvent('storage', { key: EXCLUSION_KEY, newValue: null }))
    script().dispatchEvent(new Event('load'))
    expect(events()).toEqual([])
    expect(trackEvent('page_view', {})).toBe(true)
    expect(events()).toHaveLength(1)
  })

  it('rechecks same-tab exclusion before SDK load and every event', async () => {
    const { trackEvent } = await activeAnalytics()
    trackEvent('page_view', {})
    window.localStorage.setItem(EXCLUSION_KEY, 'true')
    script().dispatchEvent(new Event('load'))
    expect(commands()).toEqual([])
    expect(trackEvent('page_view', {})).toBe(false)
    window.localStorage.removeItem(EXCLUSION_KEY)
    expect(trackEvent('page_view', {})).toBe(true)
    window.localStorage.setItem(EXCLUSION_KEY, 'true')
    expect(trackEvent('page_view', {})).toBe(false)
    expect(events()).toHaveLength(1)
  })

  it('fails closed when storage is unavailable without interrupting the service', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('storage unavailable') })
    const { trackEvent } = await activeAnalytics()
    expect(trackEvent('page_view', {})).toBe(false)
    expect(document.querySelector(SCRIPT_SELECTOR)).toBeNull()
  })

  it('bounds the pending queue and discards it on timeout, including a late load', async () => {
    const { trackEvent } = await activeAnalytics()
    const accepted = Array.from({ length: 100 }, (_, index) => trackEvent('view_complex', { complex_id: String(index + 1), entry_point: 'list' }))
    const tag = script()
    expect(accepted.filter(Boolean)).toHaveLength(50)
    expect(commands()).toEqual([])
    await vi.advanceTimersByTimeAsync(15_000)
    tag.dispatchEvent(new Event('load'))
    expect(trackEvent('page_view', {})).toBe(false)
    expect(commands()).toEqual([])
    expect(document.querySelector(SCRIPT_SELECTOR)).toBeNull()
    expect(window[DISABLE_KEY]).toBe(true)
  })

  it('handles failed loading without leaking events or retrying indefinitely', async () => {
    const { trackEvent } = await activeAnalytics()
    trackEvent('page_view', {})
    const tag = script()
    tag.dispatchEvent(new Event('error'))
    tag.dispatchEvent(new Event('load'))
    expect(trackEvent('page_view', {})).toBe(false)
    expect(commands()).toEqual([])
    expect(vi.getTimerCount()).toBe(0)
    expect(document.querySelector(SCRIPT_SELECTOR)).toBeNull()
  })

  it('clears the load timeout after success and continues sending', async () => {
    const { trackEvent } = await activeAnalytics()
    trackEvent('page_view', {})
    script().dispatchEvent(new Event('load'))
    await vi.advanceTimersByTimeAsync(15_000)
    expect(trackEvent('view_announcement', { announcement_id: '19', entry_point: 'history' })).toBe(true)
    expect(events()).toHaveLength(2)
  })

  it('contains script insertion failures', async () => {
    const append = vi.spyOn(document.head, 'append').mockImplementation(() => { throw new Error('blocked') })
    const { trackEvent } = await activeAnalytics()
    expect(trackEvent('page_view', {})).toBe(false)
    expect(trackEvent('page_view', {})).toBe(false)
    expect(append).toHaveBeenCalledTimes(1)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('contains exceptions from an already present tag function', async () => {
    window.gtag = () => { throw new Error('SDK unavailable') }
    const { trackEvent } = await activeAnalytics()
    trackEvent('page_view', {})
    expect(() => script().dispatchEvent(new Event('load'))).not.toThrow()
    expect(trackEvent('page_view', {})).toBe(false)
    expect(window[DISABLE_KEY]).toBe(true)
  })
})
