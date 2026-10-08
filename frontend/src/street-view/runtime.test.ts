import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createStreetViewInitMessage, type StreetViewChildMessage } from './protocol'
import { startStreetViewRuntime } from './runtime'

const attemptId = '7dce1fe0-6728-47f6-b9d8-806920929a3a'
const initialization = {
  searchPosition: { latitude: 37.561443, longitude: 126.962715 },
  lookAtPosition: { latitude: 37.561443, longitude: 126.962715 },
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
    observe = vi.fn()
    disconnect = disconnect
  })
  dispose = startStreetViewRuntime(container, clientId, parentWindow)
  const initialize = (origin = window.location.origin, source: MessageEventSource = parentWindow) => {
    window.dispatchEvent(new MessageEvent('message', {
      origin,
      source,
      data: createStreetViewInitMessage(attemptId, initialization),
    }))
  }
  const sdkReady = () => {
    const callback: unknown = Reflect.get(window, '__toadzipStreetViewReady')
    if (typeof callback !== 'function') throw new Error('SDK callback not registered')
    callback()
  }
  const emit = (name: string, value?: unknown) => listeners.get(name)?.(value)
  const messages = () => postMessage.mock.calls.map(([message]) => message as StreetViewChildMessage)
  return { parentWindow, initialize, sdkReady, emit, messages, container, setPov, setSize, fromCoordToPov, getLocation, constructor, removeListener, disconnect, notifyResize: () => notifyResize() }
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
    expect(runtime.container.inert).toBe(true)
    runtime.emit(second, 'OK')
    expect(runtime.setPov).toHaveBeenCalledExactlyOnceWith({ pan: 137, tilt: 0, fov: 90 })
    expect(runtime.fromCoordToPov).toHaveBeenCalledWith(expect.objectContaining(initialization.lookAtPosition))
    expect(runtime.messages()).toContainEqual(expect.objectContaining({ type: 'READY', aligned: true }))
    expect(runtime.container.inert).toBe(false)

    runtime.getLocation.mockReturnValue({ photodate: '2026-01' })
    runtime.emit('pano_changed')
    runtime.emit('pano_status', 'OK')
    expect(runtime.setPov).toHaveBeenCalledTimes(1)
    expect(runtime.messages().filter((message) => message.type === 'READY')).toHaveLength(1)
    expect(runtime.messages()).toContainEqual(expect.objectContaining({ type: 'LOCATION', photodate: '2026-01' }))
  })

  it('waits up to one second for projection and never realigns after falling back', () => {
    const runtime = setup()
    runtime.fromCoordToPov.mockReturnValue({ pan: Infinity })
    runtime.initialize()
    runtime.sdkReady()
    runtime.emit('init')
    runtime.emit('pano_status', 'OK')
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

  it('resizes only for changed positive dimensions and ignores notifications after cleanup', () => {
    const runtime = setup()
    const bounds = vi.spyOn(runtime.container, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 0, 640, 480))
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
