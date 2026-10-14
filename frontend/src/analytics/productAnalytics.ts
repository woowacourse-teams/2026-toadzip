import { analyticsCollectionAllowed, consentStore, type AnalyticsStopReason } from '../privacy/consentStore'
import type { CaptureResult, PostHogConfig } from 'posthog-js/no-external'
import type { PostHog } from 'posthog-js/no-external'
import { maskReplayAttribute } from './replayPrivacy'
import { guardPostHogTransport } from './posthogTransport'
import { BROWSER_IDENTITY_PREFIX, clearBrowserAnalyticsIdentities, loadBrowserAnalyticsIdentity,
  readBrowserAnalyticsIdentity, removeBrowserAnalyticsIdentity, type BrowserAnalyticsIdentity } from './browserAnalyticsIdentity'
export { maskReplayAttribute } from './replayPrivacy'
import { isProductEvent, sanitizeProductProperties, type ProductProperties, type SafeProperties } from './productEvents'

const EXCLUSION_KEY = 'toadzip.analytics.disabled'
const DEDUPLICATION_KEY = 'toadzip.posthog.sent-actions.v1'
const MAX_PENDING = 100
const CORE_EVENT_NAMES = new Set(['view_complex', 'view_announcement', 'notification_preregistration_completed'])
const CORE_MAX_AGE_MS = 24 * 60 * 60 * 1000
const CORE_RETRY_DELAY_MS = 30_000
const MAX_DEDUPLICATION_KEYS = 1000
const REQUIRED_ANALYTICS_SCOPE = 'analytics-scope-3'
const MAX_TIMEOUT = 2_147_483_647
const PUBLIC_PATHS = new Set(['/explore', '/feedback', '/mypage/notifications'])
type AuthState = 'member' | 'guest' | 'unknown'
export type CaptureOptions = { dedupeKey?: string; authState?: AuthState; pageName?: 'explorer' | 'feedback' | 'notification_settings' }
type PendingEvent = { name: string; properties: SafeProperties; timestamp: Date }
type CoreEvent = PendingEvent & { uuid: string; dedupeKey?: string; expiresAt: number; state: 'queued' | 'in_flight' }
const corePending = new Map<string, CoreEvent>()
let corePermissionGeneration = 0
let coreRetryTimer: ReturnType<typeof setTimeout> | undefined
let instance: PostHog | undefined
let browserIdentity: BrowserAnalyticsIdentity | undefined
let identityDeadline = 0
let identityTimer: ReturnType<typeof setTimeout> | undefined
let identityCleanup: Promise<void> = Promise.resolve()
let transport: ReturnType<typeof guardPostHogTransport> | undefined
let generation = 0
let loading: Promise<void> | undefined
let sdkModule: Promise<typeof import('posthog-js/no-external')> | undefined
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
  const context = consentStore.getSnapshot().context
  return analyticsCollectionAllowed() && context?.consent.scopeVersion === REQUIRED_ANALYTICS_SCOPE
    && context.requiredScopeVersion === REQUIRED_ANALYTICS_SCOPE
    && (!browserIdentity || identityCurrent())
    && !failed && routeActive && PUBLIC_PATHS.has(window.location.pathname) && !browserExcluded()
}

function identityCurrent(): boolean {
  const config = configuration()
  if (!browserIdentity || !config || performance.now() >= identityDeadline) return false
  const stored = readBrowserAnalyticsIdentity(config.key)
  return stored?.id === browserIdentity.id && stored.expiresAt > Date.now()
}

function releaseIdentity(): void {
  clearTimeout(identityTimer)
  identityTimer = undefined
  browserIdentity = undefined
  identityDeadline = 0
}

function forgetOwnedIdentity(): void {
  const config = configuration()
  const ownedId = browserIdentity?.id
  releaseIdentity()
  if (ownedId && config) identityCleanup = identityCleanup.then(() => removeBrowserAnalyticsIdentity(config.key, ownedId))
}

function refreshIdentity(): void {
  forgetOwnedIdentity()
  // Discard the old identity's queues, and require fresh server permission.
  consentStore.transition()
  void consentStore.refresh()
}

function scheduleIdentityExpiry(): void {
  clearTimeout(identityTimer)
  if (!browserIdentity) return
  const remaining = Math.min(identityDeadline - performance.now(), browserIdentity.expiresAt - Date.now())
  identityTimer = setTimeout(() => {
    if (!identityCurrent()) refreshIdentity()
    else scheduleIdentityExpiry()
  }, Math.max(0, Math.min(MAX_TIMEOUT, remaining)))
}

function safePageName(): NonNullable<CaptureOptions['pageName']> {
  if (window.location.pathname === '/feedback') return 'feedback'
  if (window.location.pathname === '/mypage/notifications') return 'notification_settings'
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
  properties.identity_basis = 'browser_v1'
  properties.page_name = ['explorer', 'feedback', 'notification_settings'].includes(event.properties.page_name)
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
  // The approved notice excludes individual session replay.
  return false
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
    disable_conversations: true, disable_external_dependency_loading: true,
    advanced_disable_flags: true, advanced_disable_feature_flags: true,
    api_transport: 'fetch', disable_beacon: true, disable_compression: true,
    request_batching: false, save_campaign_params: false,
    person_profiles: 'never', persistence: 'memory',
    opt_out_capturing_by_default: true, opt_out_persistence_by_default: true,
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
    if ((event.key === null || event.key?.startsWith(BROWSER_IDENTITY_PREFIX)) && browserIdentity) {
      if (!identityCurrent()) refreshIdentity()
      else {
        const config = configuration()
        const stored = config && readBrowserAnalyticsIdentity(config.key)
        if (stored && stored.expiresAt < browserIdentity.expiresAt) {
          browserIdentity = stored
          identityDeadline = Math.min(identityDeadline, performance.now() + stored.expiresAt - Date.now())
          scheduleIdentityExpiry()
        }
      }
    }
    if (event.key === EXCLUSION_KEY || event.key === null) {
      if (browserExcluded()) {
        stopCollection('context-change')
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
  const expected = generation
  const current = () => expected === generation && collectionAllowed()
  sdkModule ??= import('posthog-js/no-external')
  const operation = sdkModule.then(async ({ PostHog }) => {
    await identityCleanup
    if (!current()) return
    const identity = await loadBrowserAnalyticsIdentity(config.key, consentStore.remainingConsentLifetime, current, loaded => {
      if (!current()) return
      const deadline = performance.now() + Math.max(0, loaded.expiresAt - Date.now())
      identityDeadline = browserIdentity?.id === loaded.id ? Math.min(identityDeadline, deadline) : deadline
      // Establish ownership under the same lock as creation. A reset before init
      // must still know which ID belonged to this consent generation.
      browserIdentity = loaded
      scheduleIdentityExpiry()
    })
    if (!identity || !current()) return
    const next = new PostHog()
    const nextTransport = guardPostHogTransport(next, current, acknowledgeCoreEvent)
    transport = nextTransport
    instance = next
    let ready = false
    next.init(config.key, {
      ...productAnalyticsOptions(), api_host: config.host,
      bootstrap: { distinctID: identity.id, isIdentifiedID: false },
      loaded: () => {
        nextTransport.ready()
        if (current()) { next.opt_in_capturing({ captureEventName: false }); ready = true }
      },
    })
    if (!ready) throw new Error('PostHog transport initialization failed')
    if (!current()) { nextTransport.stop(); return }
    const queue = pending
    pending = []
    queue.forEach(send)
    flushCoreEvents()
    syncReplay()
  }).catch(() => {
    if (expected === generation) { stopCollection('pause'); failed = true }
  }).finally(() => { if (loading === operation) loading = undefined })
  loading = operation
}

function stopCollection(reason: AnalyticsStopReason = 'reset'): void {
  if (reason === 'reset') { releaseIdentity(); clearBrowserAnalyticsIdentities() }
  if (reason === 'consent-replaced') forgetOwnedIdentity()
  clearTimeout(coreRetryTimer)
  coreRetryTimer = undefined
  if (reason === 'pause') {
    for (const event of corePending.values()) event.state = 'queued'
  } else {
    corePermissionGeneration++
    corePending.clear()
  }
  generation++
  pending = []
  loading = undefined
  failed = false
  const previous = transport
  transport = undefined
  instance = undefined
  previous?.stop()
  if (reason === 'pause') scheduleCoreRetry()
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

function preparedEvent(name: string, properties: ProductProperties, options: CaptureOptions): PendingEvent {
  const safeProperties = sanitizeProductProperties(properties)
  safeProperties.event_id ??= createAnalyticsId()
  safeProperties.auth_state = options.authState ?? authState
  safeProperties.page_name = options.pageName ?? safePageName()
  const occurredAt = safeProperties.occurred_at
  return { name, properties: safeProperties, timestamp: typeof occurredAt === 'string' ? new Date(occurredAt) : new Date() }
}

function pruneCoreEvents(): void {
  const now = Date.now()
  for (const [id, event] of corePending) if (event.expiresAt <= now) corePending.delete(id)
}

function scheduleCoreRetry(): void {
  if (coreRetryTimer || corePending.size === 0) return
  coreRetryTimer = setTimeout(() => {
    coreRetryTimer = undefined
    pruneCoreEvents()
    if (collectionAllowed()) { initialize(); flushCoreEvents() }
    if (corePending.size > 0) scheduleCoreRetry()
  }, CORE_RETRY_DELAY_MS)
}

function acknowledgeCoreEvent(eventId: string, statusCode: number): void {
  const event = corePending.get(eventId)
  if (!event) return
  if (statusCode >= 200 && statusCode < 300) {
    corePending.delete(eventId)
    // This is transport acceptance, not a guarantee of final warehouse ingestion.
    if (event.dedupeKey) {
      try { remember(event.dedupeKey) } catch { /* The in-memory dedupe remains valid. */ }
    }
  } else if (statusCode >= 400 && statusCode < 500 && statusCode !== 408 && statusCode !== 429) {
    corePending.delete(eventId)
  } else {
    // The SDK owns retries until its final callback. Never race its retry queue.
    event.state = 'queued'
    scheduleCoreRetry()
  }
  if (corePending.size === 0) { clearTimeout(coreRetryTimer); coreRetryTimer = undefined }
}

function flushCoreEvents(): void {
  pruneCoreEvents()
  if (!instance || !collectionAllowed()) return
  for (const event of corePending.values()) {
    if (!collectionAllowed()) return
    if (event.state !== 'queued') continue
    event.state = 'in_flight'
    try {
      const result = instance.capture(event.name, event.properties, { timestamp: event.timestamp, uuid: event.uuid })
      if (!result) event.state = 'queued'
    } catch { event.state = 'queued' }
  }
  scheduleCoreRetry()
}

function queueCoreEvent(event: PendingEvent, options: CaptureOptions): boolean {
  pruneCoreEvents()
  const eventId = String(event.properties.event_id)
  if (corePending.has(eventId) || (options.dedupeKey && (hasBeenSent(options.dedupeKey)
    || [...corePending.values()].some(pendingEvent => pendingEvent.dedupeKey === options.dedupeKey)))) return false
  if (corePending.size >= MAX_PENDING) return false
  corePending.set(eventId, { ...event, uuid: eventId, dedupeKey: options.dedupeKey,
    expiresAt: Date.now() + CORE_MAX_AGE_MS, state: 'queued' })
  if (collectionAllowed()) { initialize(); flushCoreEvents() }
  else scheduleCoreRetry()
  return true
}

/** Accepted means queued locally. Core deduplication is committed only after HTTP acceptance. */
export function captureProductEvent(name: string, properties: ProductProperties = {}, options: CaptureOptions = {}): boolean {
  try {
    if (browserIdentity && !identityCurrent()) { refreshIdentity(); return false }
    if (!isProductEvent(name) || !configuration() || !collectionAllowed()) return false
    const event = preparedEvent(name, properties, options)
    if (CORE_EVENT_NAMES.has(name)) return queueCoreEvent(event, options)
    if (options.dedupeKey && hasBeenSent(options.dedupeKey)) return false
    if (!instance && pending.length >= MAX_PENDING) return false
    if (instance) send(event)
    else { pending.push(event); initialize() }
    if (options.dedupeKey) remember(options.dedupeKey)
    return true
  } catch { return false }
}

/** Create once per original business action, and reuse for idempotent action retries. */
export function prepareNotificationCompletion(): (properties: ProductProperties, options?: CaptureOptions) => boolean {
  const permitted = !!configuration() && collectionAllowed()
  const expected = corePermissionGeneration
  const config = configuration()
  const expiresAt = Date.now() + CORE_MAX_AGE_MS
  const originalAuthState = authState
  const originalPage = safePageName()
  return (properties, options = {}) => {
    try {
      const current = configuration()
      if (!permitted || expected !== corePermissionGeneration || Date.now() >= expiresAt
        || !current || current.key !== config?.key || current.environment !== config.environment
        || browserExcluded() || !PUBLIC_PATHS.has(window.location.pathname)) return false
      return queueCoreEvent(preparedEvent('notification_preregistration_completed', properties,
        { ...options, authState: options.authState ?? originalAuthState, pageName: options.pageName ?? originalPage }), options)
    } catch { return false }
  }
}

export function setProductPageActive(active: boolean): void {
  routeActive = active
  // StrictMode temporarily deactivates the boundary without leaving the page.
  // Do not discard already deduplicated safe events during that cleanup.
  if (!PUBLIC_PATHS.has(window.location.pathname) || browserExcluded()) stopCollection('context-change')
  if (active && collectionAllowed()) { initialize(); flushCoreEvents() }
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

consentStore.subscribe(() => {
  if (browserIdentity && !identityCurrent()) { refreshIdentity(); return }
  if (collectionAllowed()) { initialize(); flushCoreEvents() }
})

// Closed browsers cannot run timers. Expired records are removed on the next execution,
// even before permission is available; they are never reused or silently extended.
clearBrowserAnalyticsIdentities(true)

consentStore.onStop((reason) => {
  stopCollection(reason)
  if (reason === 'pause') return
  seen.clear()
  try {
    sessionStorage.removeItem(DEDUPLICATION_KEY)
    for (const storage of [localStorage, sessionStorage]) {
      for (const key of Object.keys(storage)) {
        if (key.startsWith('ph_') || key.startsWith('__ph_opt_in_out_') || key.startsWith('toadzip.notification-interest.') || key === 'toadzip.announcement-viewer') storage.removeItem(key)
      }
    }
  } catch { /* A blocked storage area must not prevent cookie cleanup. */ }
  try {
    for (const cookie of document.cookie.split(';')) {
      const name = cookie.split('=')[0]?.trim()
      if (!name || (!name.startsWith('_ga') && !name.startsWith('ph_'))) continue
      document.cookie = `${name}=; Max-Age=0; Path=/; SameSite=Lax`
      const host = window.location.hostname
      const parts = host.split('.')
      for (let index = 0; index < parts.length - 1; index++) document.cookie = `${name}=; Max-Age=0; Path=/; Domain=.${parts.slice(index).join('.')}; SameSite=Lax`
    }
  } catch { /* Stop still applies in memory when browser storage is unavailable. */ }
})
