import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createStreetViewInitMessage, type StreetViewChildMessage } from './protocol'
import { startStreetViewRuntime } from './runtime'

const attemptId = '7dce1fe0-6728-47f6-b9d8-806920929a3a'
const initialization = {
  searchPosition: { latitude: 37.561443, longitude: 126.962715 },
  lookAtPosition: { latitude: 37.5616, longitude: 126.963 },
  tilt: 0,
  fov: 90,
}

let dispose: () => void = () => undefined

beforeEach(() => vi.useFakeTimers())
afterEach(() => {
  dispose()
  vi.useRealTimers()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  document.body.replaceChildren()
  Reflect.deleteProperty(window, '__toadzipStreetViewReady')
})

function setup(clientId = 'test-browser-client') {
  const parentFrame = document.createElement('iframe')
  const container = document.createElement('div')
  document.body.append(parentFrame, container)
  const parentWindow = parentFrame.contentWindow
  if (!parentWindow) throw new Error('Missing test parent')
  const postMessage = vi.spyOn(parentWindow, 'postMessage').mockImplementation(() => undefined)
  const listeners = new Map<string, (value?: unknown) => void>()
  const setPov = vi.fn()
  const setSize = vi.fn()
  const fromCoordToPov = vi.fn().mockReturnValue({ pan: 137, tilt: -40 })
  const getLocation = vi.fn().mockReturnValue({ photodate: '2025-06', coord: { x: 126.9627, y: 37.5614 } })
  const constructor = vi.fn()
  const removeListener = vi.fn()
  const disconnect = vi.fn()
  const observe = vi.fn()
  const createMarker = vi.fn()
  const setMarkerMap = vi.fn()
  let notifyResize: () => void = () => undefined
  const sdk = {
    LatLng: class {
      latitude: number
      longitude: number
      constructor(latitude: number, longitude: number) {
        this.latitude = latitude
        this.longitude = longitude
      }
    },
    Panorama: class {
      constructor(element: HTMLElement, options: unknown) { constructor(element, options) }
      getLocation = getLocation
      getProjection = () => ({ fromCoordToPov })
      setPov = setPov
      setSize = setSize
    },
    Marker: class {
      constructor(options: unknown) { createMarker(options) }
      setMap = setMarkerMap
    },
    Event: {
      addListener: (_target: unknown, name: string, callback: (value?: unknown) => void) => {
        listeners.set(name, callback)
        return { name, callback }
      },
      removeListener,
    },
  }
  vi.stubGlobal('naver', { maps: sdk })
  vi.stubGlobal('ResizeObserver', class {
    constructor(callback: () => void) { notifyResize = callback }
    observe = observe
    disconnect = disconnect
  })
  dispose = startStreetViewRuntime(container, clientId, parentWindow)
  const initialize = (origin = window.location.origin, source: MessageEventSource = parentWindow, markerLabel = '어바니엘위드더스타일충정로 · 출입구') => {
    window.dispatchEvent(new MessageEvent('message', {
      origin,
      source,
      data: createStreetViewInitMessage(attemptId, initialization, markerLabel),
    }))
  }
  const sdkReady = () => {
    const callback: unknown = Reflect.get(window, '__toadzipStreetViewReady')
    if (typeof callback !== 'function') throw new Error('SDK callback not registered')
    callback()
  }
  const emit = (name: string, value?: unknown) => listeners.get(name)?.(value)
  const messages = () => postMessage.mock.calls.map(([message]) => message as StreetViewChildMessage)
  return { parentWindow, initialize, sdkReady, emit, messages, container, setPov, setSize, fromCoordToPov, getLocation, constructor, removeListener, disconnect, observe, sdk, createMarker, setMarkerMap, notifyResize: () => notifyResize() }
}

describe('isolated panorama runtime', () => {
  it('loads no SDK until a valid same-origin parent INIT and accepts it only once', () => {
    const runtime = setup()
    expect(runtime.messages()).toEqual([expect.objectContaining({ type: 'BOOT_READY' })])
    expect(document.head.querySelector('script')).toBeNull()
    runtime.initialize('https://untrusted.example')
    runtime.initialize(window.location.origin, window)
    expect(document.head.querySelector('script')).toBeNull()
    runtime.initialize()
    runtime.initialize()
    const scripts = document.head.querySelectorAll('script')
    expect(scripts).toHaveLength(1)
    expect(new URL(scripts[0].src).searchParams.get('submodules')).toBe('panorama')
    expect(runtime.messages().filter((message) => message.type === 'PHASE')).toEqual([
      expect.objectContaining({ type: 'PHASE', phase: 'SDK', attemptId }),
    ])
  })

  it('does not bootstrap or load the SDK when the document is opened directly', () => {
    const container = document.createElement('div')
    const send = vi.spyOn(window, 'postMessage')
    dispose = startStreetViewRuntime(container, 'test-client', window)
    expect(send).not.toHaveBeenCalled()
    expect(document.head.querySelector('script')).toBeNull()
  })

  it.each([
    ['init', 'pano_status'],
    ['pano_status', 'init'],
  ])('waits for both SDK events in order %s then %s before applying the entrance direction once', (first, second) => {
    const runtime = setup()
    runtime.initialize()
    runtime.sdkReady()
    expect(runtime.constructor).toHaveBeenCalledWith(runtime.container, expect.objectContaining({
      zoomControl: true, flightSpot: false, aroundControl: false, logoControl: true, pov: { tilt: 0, fov: 90 },
    }))
    runtime.emit(first, 'OK')
    expect(runtime.setPov).not.toHaveBeenCalled()
    expect(runtime.createMarker).not.toHaveBeenCalled()
    expect(runtime.container.inert).toBe(true)
    runtime.emit(second, 'OK')
    expect(runtime.setPov).toHaveBeenCalledExactlyOnceWith({ pan: 137, tilt: 0, fov: 90 })
    expect(runtime.fromCoordToPov).toHaveBeenCalledWith(expect.objectContaining(initialization.lookAtPosition))
    expect(runtime.messages()).toContainEqual(expect.objectContaining({ type: 'READY', aligned: true }))
    expect(runtime.container.inert).toBe(false)
    expect(runtime.constructor).toHaveBeenCalledWith(runtime.container, expect.objectContaining({
      position: expect.objectContaining(initialization.searchPosition),
    }))
    expect(runtime.createMarker).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({
      position: expect.objectContaining(initialization.lookAtPosition), clickable: false, draggable: false,
    }))
    expect(runtime.setMarkerMap).toHaveBeenCalledExactlyOnceWith(expect.any(runtime.sdk.Panorama))
    expect(runtime.messages().filter((message) => message.type === 'MARKER_STATUS')).toEqual([
      expect.objectContaining({ type: 'MARKER_STATUS', status: 'ATTACHED', attemptId }),
    ])

    runtime.getLocation.mockReturnValue({ photodate: '2026-01' })
    runtime.emit('pano_changed')
    runtime.emit('pano_status', 'OK')
    expect(runtime.setPov).toHaveBeenCalledTimes(1)
    expect(runtime.messages().filter((message) => message.type === 'READY')).toHaveLength(1)
    expect(runtime.messages()).toContainEqual(expect.objectContaining({ type: 'LOCATION', photodate: '2026-01' }))
    runtime.emit('init')
    expect(runtime.createMarker).toHaveBeenCalledTimes(1)
    expect(runtime.setMarkerMap).toHaveBeenCalledTimes(1)
    expect(runtime.messages().filter((message) => message.type === 'MARKER_STATUS')).toHaveLength(1)
  })

  it('waits up to one second for projection and never realigns after falling back', () => {
    const runtime = setup()
    runtime.fromCoordToPov.mockReturnValue({ pan: Infinity })
    runtime.initialize()
    runtime.sdkReady()
    runtime.emit('init')
    runtime.emit('pano_status', 'OK')
    expect(runtime.messages()).toContainEqual(expect.objectContaining({ type: 'MARKER_STATUS', status: 'ATTACHED' }))
    vi.advanceTimersByTime(999)
    expect(runtime.messages().some((message) => message.type === 'READY')).toBe(false)
    vi.advanceTimersByTime(1)
    expect(runtime.messages()).toContainEqual(expect.objectContaining({ type: 'READY', aligned: false }))
    expect(runtime.setPov).not.toHaveBeenCalled()
    runtime.fromCoordToPov.mockReturnValue({ pan: 130 })
    runtime.emit('pano_changed')
    vi.advanceTimersByTime(1_000)
    expect(runtime.setPov).not.toHaveBeenCalled()
  })

  it('uses projection when it becomes ready during the grace period and does not wait for a photo date', () => {
    const runtime = setup()
    runtime.fromCoordToPov.mockImplementationOnce(() => { throw new Error('not ready') })
    runtime.getLocation.mockReturnValue(undefined)
    runtime.initialize()
    runtime.sdkReady()
    runtime.emit('init')
    runtime.emit('pano_status', 'OK')
    vi.advanceTimersByTime(100)
    expect(runtime.messages()).toContainEqual(expect.objectContaining({ type: 'READY', aligned: true }))
    expect(runtime.messages()).toContainEqual(expect.objectContaining({ type: 'LOCATION', photodate: null }))
  })

  it.each(['missing', 'constructor', 'setMap', 'invalid-instance'] as const)('keeps imagery ready when the marker fails: %s', (failure) => {
    const runtime = setup()
    if (failure === 'missing') Reflect.deleteProperty(runtime.sdk, 'Marker')
    if (failure === 'constructor') runtime.createMarker.mockImplementation(() => { throw new Error('Marker construction failed') })
    if (failure === 'setMap') runtime.setMarkerMap.mockImplementation(() => { throw new Error('Marker attachment failed') })
    if (failure === 'invalid-instance') Reflect.set(runtime.sdk, 'Marker', class {})
    runtime.initialize()
    runtime.sdkReady()
    runtime.emit('init')
    runtime.emit('pano_status', 'OK')
    runtime.emit('pano_changed')
    runtime.emit('pano_status', 'OK')
    expect(runtime.messages().filter((message) => message.type === 'MARKER_STATUS')).toEqual([
      expect.objectContaining({ type: 'MARKER_STATUS', status: 'UNAVAILABLE' }),
    ])
    expect(runtime.messages()).toContainEqual(expect.objectContaining({ type: 'READY', aligned: true }))
    expect(runtime.messages().some((message) => message.type === 'FAILED')).toBe(false)
    expect(runtime.container.inert).toBe(false)
  })

  it('renders HTML-like and long names as inert text with an accessible full label and pin-tip anchor', () => {
    const runtime = setup()
    const label = '<img src=x onerror=alert(1)>' + '😀'.repeat(225) + ' · 출입구'
    runtime.initialize(window.location.origin, runtime.parentWindow, label)
    runtime.sdkReady()
    runtime.emit('pano_status', 'OK')
    runtime.emit('init')
    const options = runtime.createMarker.mock.calls[0][0] as naver.maps.MarkerOptions
    const icon = options.icon as naver.maps.HtmlIcon
    const content = icon.content as HTMLElement
    expect(content.getAttribute('role')).toBe('img')
    expect(content.getAttribute('aria-label')).toBe(label)
    expect(content.textContent).toBe(label)
    expect(content.querySelector('img, [onerror], button, a, [tabindex]')).toBeNull()
    expect(content.querySelector('svg')).toHaveAttribute('focusable', 'false')
    expect(icon.size).toEqual({ width: 220, height: 88 })
    expect(icon.anchor).toEqual({ x: 110, y: 88 })
  })

  it('detaches the marker only once and finishes resource cleanup even when detachment throws', () => {
    const runtime = setup()
    runtime.fromCoordToPov.mockReturnValue({ pan: Infinity })
    runtime.initialize()
    runtime.sdkReady()
    runtime.emit('init')
    runtime.emit('pano_status', 'OK')
    runtime.setMarkerMap.mockImplementationOnce(() => { throw new Error('Detached document') })
    const count = runtime.messages().length
    expect(() => dispose()).not.toThrow()
    dispose()
    expect(runtime.setMarkerMap).toHaveBeenLastCalledWith(null)
    expect(runtime.setMarkerMap).toHaveBeenCalledTimes(2)
    expect(runtime.removeListener).toHaveBeenCalledTimes(3)
    expect(runtime.disconnect).toHaveBeenCalledOnce()
    expect(document.head.querySelector('script')).toBeNull()
    expect(vi.getTimerCount()).toBe(0)
    runtime.emit('pano_status', 'OK')
    runtime.emit('init')
    expect(runtime.messages()).toHaveLength(count)
  })

  it('reports a panorama error without claiming that no imagery exists or reporting readiness later', () => {
    const runtime = setup()
    runtime.initialize()
    runtime.sdkReady()
    runtime.emit('pano_status', 'ERROR')
    runtime.emit('init')
    runtime.emit('pano_status', 'OK')
    expect(runtime.messages()).toContainEqual(expect.objectContaining({ type: 'FAILED', phase: 'PANORAMA', reasonCode: 'PANORAMA_QUERY_FAILED' }))
    expect(runtime.messages().some((message) => message.type === 'READY')).toBe(false)
  })

  it('distinguishes missing configuration, network and authentication failures', () => {
    const missing = setup('')
    missing.initialize()
    expect(missing.messages()).toContainEqual(expect.objectContaining({ type: 'FAILED', reasonCode: 'SDK_UNAVAILABLE' }))
    dispose()
    const network = setup()
    network.initialize()
    document.head.querySelector('script')?.dispatchEvent(new Event('error'))
    expect(network.messages()).toContainEqual(expect.objectContaining({ type: 'FAILED', reasonCode: 'SDK_LOAD_FAILED' }))
    dispose()
    const auth = setup()
    auth.initialize()
    window.navermap_authFailure?.()
    expect(auth.messages()).toContainEqual(expect.objectContaining({ type: 'FAILED', reasonCode: 'SDK_AUTH_FAILED' }))
  })

  it('rejects an SDK missing required panorama functions', () => {
    const runtime = setup()
    runtime.initialize()
    vi.stubGlobal('naver', { maps: { Panorama: class {} } })
    runtime.sdkReady()
    expect(runtime.messages()).toContainEqual(expect.objectContaining({ type: 'FAILED', reasonCode: 'SDK_UNAVAILABLE' }))
  })

  it('follows the iframe viewport after the SDK pins its panorama container to pixel dimensions', () => {
    const runtime = setup()
    const viewport = vi.spyOn(document.documentElement, 'getBoundingClientRect')
      .mockReturnValue(new DOMRect(0, 0, 640, 480))
    vi.spyOn(runtime.container, 'getBoundingClientRect').mockImplementation(() => new DOMRect(
      0, 0, Number.parseFloat(runtime.container.style.width) || 640,
      Number.parseFloat(runtime.container.style.height) || 480,
    ))
    runtime.setSize.mockImplementation(({ width, height }: { width: number; height: number }) => {
      // Real NAVER setSize writes pixels to the same element passed to Panorama.
      runtime.container.style.width = `${width}px`
      runtime.container.style.height = `${height}px`
    })
    runtime.initialize()
    runtime.sdkReady()
    expect(runtime.container.style.width).toBe('640px')
    expect(runtime.observe).toHaveBeenCalledExactlyOnceWith(document.documentElement)

    viewport.mockReturnValue(new DOMRect(0, 0, 1100, 600))
    runtime.notifyResize()
    expect(runtime.setSize).toHaveBeenLastCalledWith({ width: 1100, height: 600 })
    expect(runtime.container.style.width).toBe('1100px')
    viewport.mockReturnValue(new DOMRect(0, 0, 338, 520))
    runtime.notifyResize()
    expect(runtime.setSize).toHaveBeenLastCalledWith({ width: 338, height: 520 })
    expect(runtime.container.style.width).toBe('338px')
    expect(runtime.container.style.height).toBe('520px')
  })

  it('resizes only for changed positive dimensions and ignores notifications after cleanup', () => {
    const runtime = setup()
    const bounds = vi.spyOn(document.documentElement, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 0, 640, 480))
    runtime.initialize()
    runtime.sdkReady()
    runtime.notifyResize()
    runtime.notifyResize()
    expect(runtime.setSize).toHaveBeenCalledExactlyOnceWith({ width: 640, height: 480 })

    bounds.mockReturnValue(new DOMRect(0, 0, 0, 480))
    runtime.notifyResize()
    bounds.mockReturnValue(new DOMRect(0, 0, 640, 0))
    runtime.notifyResize()
    bounds.mockReturnValue(new DOMRect(0, 0, 640, 480))
    runtime.notifyResize()
    expect(runtime.setSize).toHaveBeenCalledTimes(1)

    bounds.mockReturnValue(new DOMRect(0, 0, 800, 480))
    runtime.notifyResize()
    bounds.mockReturnValue(new DOMRect(0, 0, 800, 600))
    runtime.notifyResize()
    expect(runtime.setSize.mock.calls).toEqual([
      [{ width: 640, height: 480 }], [{ width: 800, height: 480 }], [{ width: 800, height: 600 }],
    ])
    dispose()
    bounds.mockReturnValue(new DOMRect(0, 0, 900, 600))
    runtime.notifyResize()
    expect(runtime.setSize).toHaveBeenCalledTimes(3)
  })

  it('forwards Escape and disposes listeners, timers, script and resize observer', () => {
    const runtime = setup()
    runtime.fromCoordToPov.mockReturnValue({ pan: NaN })
    runtime.initialize()
    runtime.sdkReady()
    runtime.emit('init')
    runtime.emit('pano_status', 'OK')
    const escape = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })
    window.dispatchEvent(escape)
    expect(escape.defaultPrevented).toBe(true)
    expect(runtime.messages()).toContainEqual(expect.objectContaining({ type: 'CLOSE_REQUEST', attemptId }))
    const count = runtime.messages().length
    dispose()
    vi.advanceTimersByTime(1_000)
    runtime.emit('pano_status', 'OK')
    runtime.initialize()
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    expect(runtime.messages()).toHaveLength(count)
    expect(runtime.removeListener).toHaveBeenCalledTimes(3)
    expect(runtime.disconnect).toHaveBeenCalledExactlyOnceWith()
    expect(document.head.querySelector('script')).toBeNull()
    expect(vi.getTimerCount()).toBe(0)
  })
})
