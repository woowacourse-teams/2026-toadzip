const GOOGLE_TAG_URL = 'https://www.googletagmanager.com/gtag/js'
const LOAD_TIMEOUT_MS = 15_000
const MAX_PENDING_EVENTS = 50
const EXCLUSION_KEY = 'toadzip.analytics.disabled'
const ANNOUNCEMENT_FILTER_TYPES = ['region', 'rental', 'status', 'agency', 'recruitment']
const COMPLEX_FILTER_TYPES = [...ANNOUNCEMENT_FILTER_TYPES, 'deposit', 'rent', 'area', 'built_year']
const DETAIL_ENTRY_POINTS = ['map', 'list', 'search', 'recent', 'detail', 'direct', 'history'] as const

export type DetailEntryPoint = typeof DETAIL_ENTRY_POINTS[number]
export type AnalyticsEvents = {
  page_view: Record<string, never>
  view_complex: { complex_id: string; entry_point: DetailEntryPoint }
  view_announcement: { announcement_id: string; entry_point: DetailEntryPoint }
  select_search_result: {
    result_type: 'complex' | 'announcement' | 'region' | 'subway_station'
    complex_id?: string
    announcement_id?: string
  }
  apply_filter: {
    filter_target: 'complex' | 'announcement'
    filter_types: string
    filter_count: number
  }
}

type Parameters = Record<string, string | number | boolean>
type GoogleTagCommand =
  | ['js', Date]
  | ['set', Parameters]
  | ['config', string, Parameters]
  | ['event', string, Parameters]
type PendingEvent = { name: keyof AnalyticsEvents; parameters: Parameters }

declare global {
  interface Window {
    dataLayer?: unknown[]
    gtag?: (...command: GoogleTagCommand) => void
    [key: `ga-disable-${string}`]: boolean | undefined
  }
}

const measurementId = readMeasurementId()
let pageActive = false
let flagInstalled = false
let state: 'idle' | 'loading' | 'ready' | 'failed' = 'idle'
let configured = false
let pendingEvents: PendingEvent[] = []
let stopLoading: (() => void) | undefined
let tagScript: HTMLScriptElement | undefined
let pageContext: Parameters | undefined

function readMeasurementId(): string | null {
  const id = import.meta.env.VITE_GA_MEASUREMENT_ID?.trim()
  if (!id || !/^G-[A-Z0-9]+$/.test(id)) return null
  if (import.meta.env.DEV && import.meta.env.VITE_GA_DEBUG_MODE !== 'true') return null
  return id
}

function browserExcluded(): boolean {
  try {
    return window.localStorage.getItem(EXCLUSION_KEY) === 'true'
  } catch {
    return true
  }
}

function installDisableFlag(id: string): boolean {
  if (flagInstalled) return true
  const key = `ga-disable-${id}` as const
  const descriptor = Object.getOwnPropertyDescriptor(window, key)
  if (descriptor && (!descriptor.configurable || descriptor.get || descriptor.set)) return false
  let externalOptOut = window[key] === true

  // Keep external opt-out writes separate from our route/storage gate so a
  // later return to the explorer cannot silently undo somebody else's opt-out.
  Object.defineProperty(window, key, {
    configurable: true,
    enumerable: true,
    get: () => (
      externalOptOut ||
      !pageActive ||
      window.location.pathname !== '/' ||
      browserExcluded() ||
      state === 'failed'
    ),
    set: (value: unknown) => {
      externalOptOut = value === true
      if (externalOptOut) pendingEvents = []
    },
  })
  window.addEventListener('storage', (event) => {
    if ((event.key === EXCLUSION_KEY || event.key === null) && browserExcluded()) {
      pendingEvents = []
    }
  })
  flagInstalled = true
  return true
}

function collectionAllowed(): boolean {
  const allowed = measurementId !== null &&
    flagInstalled &&
    window[`ga-disable-${measurementId}`] !== true
  if (!allowed) pendingEvents = []
  return allowed
}

function safePageContext(): Parameters {
  let referrer = ''
  try {
    const url = new URL(document.referrer)
    if (
      (url.protocol === 'https:' || url.protocol === 'http:') &&
      url.origin !== window.location.origin
    ) {
      referrer = `${url.origin}/`
    }
  } catch {
    // Missing or non-URL referrers have no useful non-sensitive source.
  }
  return {
    page_location: `${window.location.origin}/`,
    page_title: '공공주택 복덕방',
    page_referrer: referrer,
    allow_google_signals: false,
    allow_ad_personalization_signals: false,
  }
}

function failCollection(): void {
  state = 'failed'
  pendingEvents = []
  stopLoading?.()
  tagScript?.remove()
}

function sendEvent(event: PendingEvent): boolean {
  if (!collectionAllowed() || !measurementId || !window.gtag || !pageContext) return false
  if (!configured) {
    window.gtag('set', pageContext)
    window.gtag('js', new Date())
    window.gtag('config', measurementId, {
      ...pageContext,
      send_page_view: false,
      ...(import.meta.env.VITE_GA_DEBUG_MODE === 'true' ? { debug_mode: true } : {}),
    })
    configured = true
  }
  window.gtag('event', event.name, { ...event.parameters, ...pageContext, send_to: measurementId })
  return true
}

function loadGoogleTag(): void {
  if (state !== 'idle' || !measurementId) return
  state = 'loading'
  pageContext = safePageContext()
  window.dataLayer ??= []
  // Google tag uses the standard arguments-object queue, not a nested array.
  window.gtag ??= function () {
    window.dataLayer?.push(arguments)
  }
  const script = document.createElement('script')
  tagScript = script
  script.src = `${GOOGLE_TAG_URL}?id=${measurementId}`
  script.async = true
  script.referrerPolicy = 'no-referrer'
  script.setAttribute('data-toadzip-google-tag', '')
  const onLoad = () => {
    stopLoading?.()
    state = 'ready'
    try {
      while (pendingEvents.length > 0 && collectionAllowed()) {
        const event = pendingEvents.shift()
        if (event) sendEvent(event)
      }
    } catch {
      failCollection()
    }
  }
  const onError = () => failCollection()
  const timeout = window.setTimeout(onError, LOAD_TIMEOUT_MS)
  stopLoading = () => {
    window.clearTimeout(timeout)
    script.removeEventListener('load', onLoad)
    script.removeEventListener('error', onError)
    stopLoading = undefined
  }
  script.addEventListener('load', onLoad, { once: true })
  script.addEventListener('error', onError, { once: true })
  document.head.append(script)
}

function isPublicId(value: unknown): value is string {
  return typeof value === 'string' && /^[1-9]\d{0,18}$/.test(value)
}

function eventParameters(name: keyof AnalyticsEvents, parameters: unknown): Parameters | null {
  if (typeof parameters !== 'object' || parameters === null) return null
  const values = parameters as Record<string, unknown>
  switch (name) {
    case 'page_view':
      return {}
    case 'view_complex':
    case 'view_announcement': {
      const idKey = name === 'view_complex' ? 'complex_id' : 'announcement_id'
      const id = values[idKey]
      if (
        !isPublicId(id) ||
        !DETAIL_ENTRY_POINTS.some((entry) => entry === values.entry_point)
      ) return null
      return { [idKey]: id, entry_point: String(values.entry_point) }
    }
    case 'select_search_result':
      if (values.result_type === 'region') return { result_type: 'region' }
      if (values.result_type === 'subway_station') return { result_type: 'subway_station' }
      if (values.result_type === 'complex' && isPublicId(values.complex_id)) {
        return { result_type: 'complex', complex_id: values.complex_id }
      }
      if (values.result_type === 'announcement' && isPublicId(values.announcement_id)) {
        return { result_type: 'announcement', announcement_id: values.announcement_id }
      }
      return null
    case 'apply_filter': {
      if (values.filter_target !== 'complex' && values.filter_target !== 'announcement') return null
      if (typeof values.filter_types !== 'string' || typeof values.filter_count !== 'number') return null
      const types = values.filter_types === 'none' ? [] : values.filter_types.split(',')
      const allowedTypes = values.filter_target === 'complex'
        ? COMPLEX_FILTER_TYPES
        : ANNOUNCEMENT_FILTER_TYPES
      if (
        !types.every((type) => allowedTypes.includes(type)) ||
        new Set(types).size !== types.length ||
        types.length !== values.filter_count
      ) return null
      return {
        filter_target: values.filter_target,
        filter_types: values.filter_types,
        filter_count: values.filter_count,
      }
    }
    default:
      return null
  }
}

export function setAnalyticsPageActive(active: boolean): void {
  try {
    if (typeof window === 'undefined' || !measurementId) return
    pageActive = active
    if (!installDisableFlag(measurementId)) {
      failCollection()
      return
    }
    collectionAllowed()
  } catch {
    failCollection()
  }
}

export function trackEvent<Name extends keyof AnalyticsEvents>(
  name: Name,
  parameters: AnalyticsEvents[Name],
): boolean {
  try {
    if (typeof window === 'undefined' || !collectionAllowed()) return false
    const sanitized = eventParameters(name, parameters)
    if (!sanitized) return false
    const event = { name, parameters: sanitized }
    if (state === 'ready') return sendEvent(event)
    if (pendingEvents.length >= MAX_PENDING_EVENTS) return false
    pendingEvents.push(event)
    loadGoogleTag()
    return state === 'loading'
  } catch {
    failCollection()
    return false
  }
}
