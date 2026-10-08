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
type PanoramaMarker = { setMap: (panorama: Panorama | null) => void }
type PanoramaSdk = {
  Panorama: new (element: HTMLElement, options: naver.maps.PanoramaOptions) => Panorama
  Marker?: new (options: naver.maps.MarkerOptions) => unknown
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

  const viewport = container.ownerDocument.documentElement
  let disposed = false
  let initializationMessage: StreetViewParentMessage | null = null
  let script: HTMLScriptElement | null = null
  let maps: PanoramaSdk | null = null
  let panorama: Panorama | null = null
  let entranceMarker: PanoramaMarker | null = null
  let markerAttempted = false
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

  const detachEntranceMarker = () => {
    const marker = entranceMarker
    entranceMarker = null
    try {
      marker?.setMap(null)
    } catch {
      // A marker failure must not prevent the rest of the iframe from being released.
    }
  }

  const attachEntranceMarker = () => {
    if (disposed || failed || markerAttempted || !initialized || !querySucceeded || !panorama || !maps || !initializationMessage) return
    markerAttempted = true
    try {
      const { lookAtPosition } = initializationMessage.initialization
      entranceMarker = createPanoramaMarker(maps, {
        position: new maps.LatLng(lookAtPosition.latitude, lookAtPosition.longitude),
        icon: {
          content: createMarkerContent(initializationMessage.markerLabel),
          size: { width: 220, height: 88 },
          anchor: { x: 110, y: 88 },
        },
        clickable: false,
        draggable: false,
      })
      entranceMarker.setMap(panorama)
      send({ type: 'MARKER_STATUS', status: 'ATTACHED' })
    } catch {
      detachEntranceMarker()
      send({ type: 'MARKER_STATUS', status: 'UNAVAILABLE' })
    }
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
    // Panorama.setSize writes pixels to its own container, so measure the independent
    // iframe viewport rather than feeding those previous dimensions back into the SDK.
    const { width, height } = viewport.getBoundingClientRect()
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
        attachEntranceMarker()
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
          attachEntranceMarker()
          alignInitialView()
        }
      }))
      sdkListeners.push(maps.Event.addListener(panorama, 'pano_changed', () => {
        reportLocation()
        alignInitialView()
      }))
      resizeObserver = new ResizeObserver(resize)
      resizeObserver.observe(viewport)
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
    detachEntranceMarker()
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
  // Required constructors are checked here and instance methods after construction.
  return maps as PanoramaSdk
}

function createPanoramaMarker(maps: PanoramaSdk, options: naver.maps.MarkerOptions): PanoramaMarker {
  if (typeof maps.Marker !== 'function') throw new Error('Unavailable marker constructor')
  const marker: unknown = new maps.Marker(options)
  if (!isRecord(marker) || typeof marker.setMap !== 'function') throw new Error('Unavailable marker methods')
  const setMap = marker.setMap
  // The official Panorama example accepts Marker.setMap(panorama), while @types/navermaps
  // only declares Map. Keep that compatibility adjustment at this checked SDK boundary.
  return { setMap: (panorama) => { setMap.call(marker, panorama) } }
}

function createMarkerContent(label: string): HTMLElement {
  const content = document.createElement('div')
  content.className = 'street-view-entrance-marker'
  content.setAttribute('role', 'img')
  content.setAttribute('aria-label', label)
  const name = document.createElement('span')
  name.className = 'street-view-entrance-label'
  name.textContent = label
  name.setAttribute('aria-hidden', 'true')
  const pin = document.createElementNS('http://www.w3.org/2000/svg', 'svg')
  pin.setAttribute('viewBox', '0 0 24 24')
  pin.setAttribute('aria-hidden', 'true')
  pin.setAttribute('focusable', 'false')
  const outline = document.createElementNS('http://www.w3.org/2000/svg', 'path')
  outline.setAttribute('d', 'M12 24C10 21 3 14.5 3 9a9 9 0 1 1 18 0c0 5.5-7 12-9 15Z')
  outline.setAttribute('fill', 'currentColor')
  const center = document.createElementNS('http://www.w3.org/2000/svg', 'circle')
  center.setAttribute('cx', '12')
  center.setAttribute('cy', '9')
  center.setAttribute('r', '3.5')
  center.setAttribute('fill', 'var(--ds-color-surface)')
  pin.append(outline, center)
  content.append(name, pin)
  return content
}

function hasPanoramaMethods(value: unknown): value is Panorama {
  return isRecord(value) && ['getLocation', 'getProjection', 'setPov', 'setSize'].every((method) => typeof value[method] === 'function')
}
