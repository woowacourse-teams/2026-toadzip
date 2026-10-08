import type { CaptureResult, PostHog, PostHogConfig } from 'posthog-js'
import { maskReplayAttribute } from './replayPrivacy'
export { maskReplayAttribute } from './replayPrivacy'
import { isProductEvent, sanitizeProductProperties, type ProductProperties, type SafeProperties } from './productEvents'

const EXCLUSION_KEY = 'toadzip.analytics.disabled'
const DEDUPLICATION_KEY = 'toadzip.posthog.sent-actions.v1'
const MAX_PENDING = 100
const MAX_DEDUPLICATION_KEYS = 1000
const PUBLIC_PATHS = new Set(['/', '/feedback', '/notifications/cancel'])
type AuthState = 'member' | 'guest' | 'unknown'
type PendingEvent = { name: string; properties: SafeProperties; timestamp: Date }
let instance: PostHog | undefined
let loading: Promise<void> | undefined
let failed = false
let routeActive = true
let listenersInstalled = false
let authState: AuthState = 'unknown'
let lastConfirmedAuthState: Exclude<AuthState, 'unknown'> | undefined
let pending: PendingEvent[] = []
const replayBlocks = new Set<string>()
const seen = new Set<string>()

export function createAnalyticsId(): string {
  return crypto.randomUUID()
}

function configuration(): { key: string; host: string; environment: 'dev' | 'prod' } | null {
  if (import.meta.env.MODE === 'test') return null
  const key = import.meta.env.VITE_POSTHOG_KEY?.trim()
  const host = import.meta.env.VITE_POSTHOG_HOST?.trim()
  const environment = import.meta.env.VITE_ANALYTICS_ENV
  if (!key || !/^phc_[a-zA-Z0-9]+$/.test(key) || host !== 'https://us.i.posthog.com') return null
  if (environment !== 'dev' && environment !== 'prod') return null
  const local = ['localhost', '127.0.0.1', '[::1]'].includes(window.location.hostname)
  if (local && (environment !== 'dev' || import.meta.env.VITE_POSTHOG_LOCAL_ENABLED !== 'true')) return null
  if (window.location.hostname === 'bokduckbang.com' && environment !== 'prod') return null
  if (window.location.hostname === 'dev.bokduckbang.com' && environment !== 'dev') return null
  return { key, host, environment }
}

function browserExcluded(): boolean {
  try {
    return localStorage.getItem(EXCLUSION_KEY) === 'true'
  } catch {
    return true
  }
}

function collectionAllowed(): boolean {
  return !failed && routeActive && PUBLIC_PATHS.has(window.location.pathname) && !browserExcluded()
}

function safePageName(): string {
  if (window.location.pathname === '/feedback') return 'feedback'
  if (window.location.pathname === '/notifications/cancel') return 'guest_cancellation'
  return 'explorer'
}

/** URLs are reduced before both event capture and recorder URL targeting. */
export function safeAnalyticsUrl(value: string): string {
  try {
    const url = new URL(value, window.location.origin)
    if (!['http:', 'https:'].includes(url.protocol)) return ''
    return `${url.origin}/`
  } catch {
    return ''
  }
}


const TECHNICAL_PROPERTIES = new Set([
  'token', 'distinct_id', '$device_id', '$session_id', '$window_id', '$lib', '$lib_version',
  '$browser', '$browser_version', '$browser_language', '$os', '$os_version', '$device_type',
  '$screen_height', '$screen_width', '$viewport_height', '$viewport_width', '$insert_id',
  '$process_person_profile', '$is_identified', '$time', '$sent_at', '$sdk_debug_current_session_duration',
  '$session_recording_start_reason', '$snapshot_data', '$snapshot_bytes', '$snapshot_source',
  '$session_recording_is_sampled', '$session_recording_is_active', '$session_recording_active',
])

export function sanitizeCapture(event: CaptureResult | null): CaptureResult | null {
  if (!event || !collectionAllowed()) return null
  const replayEvent = event.event === '$snapshot'
  if (!replayEvent && !isProductEvent(event.event)) return null
  if (replayEvent && !replayAllowed()) return null
  const properties: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(event.properties)) {
    if (TECHNICAL_PROPERTIES.has(key)) properties[key] = value
  }
  if (!replayEvent) Object.assign(properties, sanitizeProductProperties(event.properties))
  properties.$current_url = `${window.location.origin}/`
  properties.$pathname = PUBLIC_PATHS.has(window.location.pathname) ? window.location.pathname : '/'
  properties.$referrer = safeAnalyticsUrl(document.referrer)
  properties.$host = window.location.host
  if (replayEvent) properties.$snapshot_host = window.location.hostname
  properties.$geoip_disable = true
  properties.$ip = null
  properties.environment = configuration()?.environment
  properties.schema_version = 1
  properties.page_name = ['explorer', 'feedback', 'guest_cancellation'].includes(event.properties.page_name)
    ? event.properties.page_name : safePageName()
  properties.auth_state = ['member', 'guest', 'unknown'].includes(event.properties.auth_state)
    ? event.properties.auth_state : authState
  // No automatically populated person properties ($set/$set_once), account IDs,
  // campaign parameters, raw URLs, console logs or unrestricted custom props.
  // The SDK also enriches top-level $set_once/$set, outside properties.
  // Preserve only the transport envelope, never that automatic person data.
  return { event: event.event, properties, timestamp: event.timestamp, uuid: event.uuid }
}

function replayAllowed(): boolean {
  return collectionAllowed() && window.location.pathname === '/' && replayBlocks.size === 0
    && !new URLSearchParams(window.location.search).has('login')
}

function syncReplay(): void {
  if (!instance) return
  if (replayAllowed()) instance.startSessionRecording()
  else instance.stopSessionRecording()
}

export function productAnalyticsOptions(): Partial<PostHogConfig> {
  return {
    api_host: 'https://us.i.posthog.com', ui_host: 'https://us.posthog.com',
    autocapture: false, capture_pageview: false, capture_pageleave: false,
    capture_dead_clicks: false, rageclick: false, capture_heatmaps: false,
    capture_exceptions: false, capture_performance: false, capture_webmcp: false,
    disable_surveys: true, disable_product_tours: true, disable_web_experiments: true,
    advanced_disable_feature_flags: true,
    person_profiles: 'always', persistence: 'localStorage+cookie',
    ip: false, disableDeviceModel: true, save_referrer: false, store_google: false,
    enable_recording_console_log: false, disable_session_recording: true,
    session_recording: {
      maskAllInputs: true, maskTextSelector: '*', maskTextFn: () => '***',
      maskInputFn: () => '***', maskAttributeFn: maskReplayAttribute,
      blockSelector: '.ph-no-capture, [data-analytics-sensitive], iframe, object, embed, canvas, input[type="file"], img',
      recordCrossOriginIframes: false, recordHeaders: false, recordBody: false,
      captureCanvas: { recordCanvas: false }, captureJsonLd: false,
      // The recorder also invokes this callback with only { name } for its
      // mandatory page-URL metadata. Keep a sanitized origin for that case.
      maskCapturedNetworkRequestFn: (request) => Object.keys(request).every(key => key === 'name')
        ? { ...request, name: safeAnalyticsUrl(request.name) } : null,
      slimDOMOptions: { script: true, comment: true, headMetaDescKeywords: true, headMetaSocial: true, headMetaAuthorship: true },
    },
    before_send: sanitizeCapture,
    get_current_url: safeAnalyticsUrl,
  }
}

function installListeners(): void {
  if (listenersInstalled) return
  listenersInstalled = true
  window.addEventListener('storage', (event) => {
    if (event.key === EXCLUSION_KEY || event.key === null) {
      if (browserExcluded()) {
        pending = []
        instance?.stopSessionRecording()
      }
      // The application gate owns team exclusion. Do not overwrite a separate
      // SDK opt-out, which must remain independent when this key is removed.
    }
  })
}

function send(event: PendingEvent): void {
  if (!instance || !collectionAllowed()) return
  instance.capture(event.name, event.properties, { timestamp: event.timestamp })
}

function initialize(): void {
  if (loading || instance || failed) return
  const config = configuration()
  if (!config || !collectionAllowed()) return
  installListeners()
  loading = import('posthog-js').then(({ default: posthog }) => {
    if (!collectionAllowed()) { pending = []; return }
    instance = posthog.init(config.key, {
      ...productAnalyticsOptions(), api_host: config.host,
      loaded: () => { syncReplay() },
    })
    const queue = pending
    pending = []
    queue.forEach(send)
    syncReplay()
  }).catch(() => { failed = true; pending = [] }).finally(() => { loading = undefined })
}

function hasBeenSent(key: string): boolean {
  if (seen.has(key)) return true
  const stored: unknown = JSON.parse(sessionStorage.getItem(DEDUPLICATION_KEY) ?? '[]')
  return Array.isArray(stored) && stored.includes(key)
}

function remember(key: string): void {
  seen.add(key)
  const stored: unknown = JSON.parse(sessionStorage.getItem(DEDUPLICATION_KEY) ?? '[]')
  const keys = Array.isArray(stored) ? stored.filter((entry): entry is string => typeof entry === 'string') : []
  sessionStorage.setItem(DEDUPLICATION_KEY, JSON.stringify([...keys, key].slice(-MAX_DEDUPLICATION_KEYS)))
  if (seen.size > MAX_DEDUPLICATION_KEYS) seen.delete(seen.values().next().value ?? '')
}

export function captureProductEvent(name: string, properties: ProductProperties = {}, options: { dedupeKey?: string; authState?: AuthState; pageName?: 'explorer' | 'feedback' | 'guest_cancellation' } = {}): boolean {
  try {
    if (!isProductEvent(name) || !configuration() || !collectionAllowed()) return false
    if (options.dedupeKey && hasBeenSent(options.dedupeKey)) return false
    if (!instance && pending.length >= MAX_PENDING) return false
    const safeProperties = sanitizeProductProperties(properties)
    safeProperties.event_id ??= createAnalyticsId()
    safeProperties.auth_state = options.authState ?? authState
    safeProperties.page_name = options.pageName ?? safePageName()
    const occurredAt = safeProperties.occurred_at
    const event = { name, properties: safeProperties, timestamp: typeof occurredAt === 'string' ? new Date(occurredAt) : new Date() }
    if (instance) send(event)
    else { pending.push(event); initialize() }
    if (options.dedupeKey) remember(options.dedupeKey)
    return true
  } catch {
    return false
  }
}

export function setProductPageActive(active: boolean): void {
  routeActive = active
  // StrictMode temporarily deactivates the boundary without leaving the page.
  // Do not discard already deduplicated safe events during that cleanup.
  if (!PUBLIC_PATHS.has(window.location.pathname) || browserExcluded()) pending = []
  syncReplay()
}

export function setProductAuthState(next: AuthState): void {
  authState = next
  if (next === 'unknown') return
  const previous = lastConfirmedAuthState
  lastConfirmedAuthState = next
  if (previous && previous !== next) captureProductEvent('auth_state_changed', { previous_auth_state: previous, next_auth_state: next })
}

export function setReplaySensitive(reason: string, active: boolean): void {
  if (active) replayBlocks.add(reason)
  else replayBlocks.delete(reason)
  syncReplay()
}
