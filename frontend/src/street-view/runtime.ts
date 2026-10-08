import {
  parseStreetViewParentMessage,
  STREET_VIEW_CHANNEL,
  STREET_VIEW_VERSION,
  type StreetViewParentMessage,
  type StreetViewRuntimeFailure,
  type StreetViewRuntimePayload,
} from './protocol'

const CALLBACK_NAME = '__toadzipStreetViewReady'
const ORIENTATION_WAIT_MS = 1_000
const RETRY_ORIENTATION_MS = 100

type Panorama = Pick<naver.maps.Panorama, 'getLocation' | 'getProjection' | 'setPov' | 'setSize'>
type PanoramaSdk = {
  Panorama: new (element: HTMLElement, options: naver.maps.PanoramaOptions) => Panorama
  LatLng: typeof naver.maps.LatLng
  Event: Pick<typeof naver.maps.Event, 'addListener' | 'removeListener'>
}

/** This runtime lives only inside the dedicated document; the parent owns initialization deadlines. */
export function startStreetViewRuntime(
  container: HTMLElement,
  clientId: string,
  parentWindow: Window = window.parent,
): () => void {
  if (parentWindow === window) return () => undefined

  let disposed = false
  let initializationMessage: StreetViewParentMessage | null = null
  let script: HTMLScriptElement | null = null
  let maps: PanoramaSdk | null = null
  let panorama: Panorama | null = null
  let initialized = false
  let querySucceeded = false
  let ready = false
  let failed = false
  let orientationDeadline: number | null = null
  let orientationTimer: number | undefined
  let resizeObserver: ResizeObserver | null = null
  let renderedWidth = 0
  let renderedHeight = 0
  const sdkListeners: naver.maps.MapEventListener[] = []
  const previousAuthFailure = window.navermap_authFailure
  const previousReady: unknown = Reflect.get(window, CALLBACK_NAME)

  const send = (payload: StreetViewRuntimePayload) => {
    if (disposed || !initializationMessage) return
    parentWindow.postMessage({
      channel: STREET_VIEW_CHANNEL,
      version: STREET_VIEW_VERSION,
      attemptId: initializationMessage.attemptId,
      ...payload,
    }, window.location.origin)
  }

  const fail = (failure: StreetViewRuntimeFailure) => {
    if (disposed || failed) return
    failed = true
    container.inert = true
    window.clearTimeout(orientationTimer)
    send({ type: 'FAILED', ...failure })
  }

  const reportLocation = () => {
    if (!panorama || failed || disposed) return
    let photodate: string | null = null
    try {
      const location: unknown = panorama.getLocation()
      if (isRecord(location) && typeof location.photodate === 'string') {
        photodate = location.photodate.trim().slice(0, 100) || null
      }
    } catch {
      // The SDK can emit a change before location metadata is available.
    }
    send({ type: 'LOCATION', photodate })
  }

  const markReady = (aligned: boolean) => {
    if (disposed || failed || ready) return
    ready = true
    window.clearTimeout(orientationTimer)
    container.inert = false
    reportLocation()
    send({ type: 'READY', aligned })
  }

  const alignInitialView = () => {
    if (disposed || failed || ready || !initialized || !querySucceeded || !panorama || !maps || !initializationMessage) return
    if (orientationDeadline === null) orientationDeadline = performance.now() + ORIENTATION_WAIT_MS
    window.clearTimeout(orientationTimer)
    try {
      const { lookAtPosition, tilt, fov } = initializationMessage.initialization
      const projection = panorama.getProjection()
      const pov: unknown = projection.fromCoordToPov(new maps.LatLng(lookAtPosition.latitude, lookAtPosition.longitude))
      if (isRecord(pov) && typeof pov.pan === 'number' && Number.isFinite(pov.pan)) {
        panorama.setPov({ pan: pov.pan, tilt, fov })
        markReady(true)
        return
      }
    } catch {
      // Projection may not be ready with the first successful panorama response.
    }
    if (performance.now() >= orientationDeadline) {
      markReady(false)
      return
    }
    orientationTimer = window.setTimeout(alignInitialView, Math.min(RETRY_ORIENTATION_MS, orientationDeadline - performance.now()))
  }

  const resize = () => {
    if (!panorama || disposed || failed) return
    const { width, height } = container.getBoundingClientRect()
    if (width <= 0 || height <= 0 || (width === renderedWidth && height === renderedHeight)) return
    panorama.setSize({ width, height })
    renderedWidth = width
    renderedHeight = height
  }

  const onSdkReady = () => {
    if (disposed || failed || panorama || !initializationMessage) return
    maps = readPanoramaSdk()
    if (!maps) {
      fail({ phase: 'SDK', reasonCode: 'SDK_UNAVAILABLE' })
      return
    }
    const { searchPosition, tilt, fov } = initializationMessage.initialization
    try {
      panorama = new maps.Panorama(container, {
        position: new maps.LatLng(searchPosition.latitude, searchPosition.longitude),
        pov: { tilt, fov },
        zoomControl: true,
        flightSpot: false,
        aroundControl: false,
        logoControl: true,
      })
      if (!hasPanoramaMethods(panorama)) throw new Error('Unavailable panorama methods')
      sdkListeners.push(maps.Event.addListener(panorama, 'init', () => {
        initialized = true
        alignInitialView()
      }))
      sdkListeners.push(maps.Event.addListener(panorama, 'pano_status', (status: unknown) => {
        if (status === 'ERROR') {
          fail({ phase: 'PANORAMA', reasonCode: 'PANORAMA_QUERY_FAILED' })
          return
        }
        if (status === 'OK') {
          querySucceeded = true
          reportLocation()
          alignInitialView()
        }
      }))
      sdkListeners.push(maps.Event.addListener(panorama, 'pano_changed', () => {
        reportLocation()
        alignInitialView()
      }))
      resizeObserver = new ResizeObserver(resize)
      resizeObserver.observe(container)
      resize()
      send({ type: 'PHASE', phase: 'PANORAMA' })
    } catch {
      fail({ phase: 'SDK', reasonCode: 'SDK_UNAVAILABLE' })
    }
  }

  const onAuthFailure = () => fail({ phase: 'SDK', reasonCode: 'SDK_AUTH_FAILED' })
  const onScriptError = () => fail({ phase: 'SDK', reasonCode: 'SDK_LOAD_FAILED' })
  const loadSdk = () => {
    send({ type: 'PHASE', phase: 'SDK' })
    if (!clientId.trim()) {
      fail({ phase: 'SDK', reasonCode: 'SDK_UNAVAILABLE' })
      return
    }
    Reflect.set(window, CALLBACK_NAME, onSdkReady)
    window.navermap_authFailure = onAuthFailure
    const url = new URL('https://oapi.map.naver.com/openapi/v3/maps.js')
    url.searchParams.set('ncpKeyId', clientId.trim())
    url.searchParams.set('submodules', 'panorama')
    url.searchParams.set('callback', CALLBACK_NAME)
    script = document.createElement('script')
    script.src = url.toString()
    script.async = true
    script.addEventListener('error', onScriptError, { once: true })
    document.head.append(script)
  }

  const onMessage = (event: MessageEvent<unknown>) => {
    if (disposed || initializationMessage || event.origin !== window.location.origin || event.source !== parentWindow) return
    const message = parseStreetViewParentMessage(event.data)
    if (!message) return
    initializationMessage = message
    container.replaceChildren()
    container.inert = true
    loadSdk()
  }

  const onKeyDown = (event: KeyboardEvent) => {
    if (event.key !== 'Escape') return
    event.preventDefault()
    event.stopPropagation()
    send({ type: 'CLOSE_REQUEST' })
  }

  const dispose = () => {
    if (disposed) return
    disposed = true
    window.removeEventListener('message', onMessage)
    window.removeEventListener('pagehide', dispose)
    window.removeEventListener('keydown', onKeyDown, true)
    window.clearTimeout(orientationTimer)
    resizeObserver?.disconnect()
    sdkListeners.forEach((listener) => maps?.Event.removeListener(listener))
    script?.removeEventListener('error', onScriptError)
    script?.remove()
    if (window.navermap_authFailure === onAuthFailure) window.navermap_authFailure = previousAuthFailure
    if (Reflect.get(window, CALLBACK_NAME) === onSdkReady) {
      Reflect.set(window, CALLBACK_NAME, previousReady ?? (() => undefined))
    }
    container.replaceChildren()
    panorama = null
    maps = null
  }

  window.addEventListener('message', onMessage)
  window.addEventListener('pagehide', dispose)
  window.addEventListener('keydown', onKeyDown, true)
  parentWindow.postMessage({ channel: STREET_VIEW_CHANNEL, version: STREET_VIEW_VERSION, type: 'BOOT_READY' }, window.location.origin)
  return dispose
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function readPanoramaSdk(): PanoramaSdk | null {
  const candidate: unknown = Reflect.get(window, 'naver')
  if (!isRecord(candidate) || !isRecord(candidate.maps)) return null
  const maps = candidate.maps
  if (typeof maps.Panorama !== 'function' || typeof maps.LatLng !== 'function' || !isRecord(maps.Event) ||
    typeof maps.Event.addListener !== 'function' || typeof maps.Event.removeListener !== 'function') return null
  // This is the only SDK cast: constructors are checked here and instance methods after construction.
  return maps as PanoramaSdk
}

function hasPanoramaMethods(value: unknown): value is Panorama {
  return isRecord(value) && ['getLocation', 'getProjection', 'setPov', 'setSize'].every((method) => typeof value[method] === 'function')
}
