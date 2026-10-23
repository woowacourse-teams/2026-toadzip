import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMarkerHoverAnalytics } from '../../analytics/markerHoverAnalytics'
import { observeMarkerHovers } from './markerHoverTracking'
import type { AnalyticsStopReason } from '../../privacy/consentStore'

const gate = vi.hoisted(() => ({ allowed: true, generation: 0,
  stops: new Set<(reason: AnalyticsStopReason) => void>(), listeners: new Set<() => void>(),
  capture: vi.fn((_name: string, _properties: Record<string, unknown>, _options?: unknown) => true),
}))
vi.mock('../../privacy/consentStore', () => ({ consentStore: {
  subscribe: (listener: () => void) => { gate.listeners.add(listener); return () => { gate.listeners.delete(listener) } },
  onStop: (listener: (reason: AnalyticsStopReason) => void) => { gate.stops.add(listener); return () => { gate.stops.delete(listener) } },
} }))
vi.mock('../../analytics/productAnalytics', () => ({
  createAnalyticsId: () => crypto.randomUUID(),
  captureProductEvent: (...args: Parameters<typeof gate.capture>) => gate.allowed && gate.capture(...args),
  prepareMarkerHoverCompletion: () => {
    if (!gate.allowed) return null
    const generation = gate.generation
    const isCurrent = () => generation === gate.generation
    return { isCurrent, complete: (properties: Record<string, unknown>) =>
      isCurrent() && gate.capture('map_marker_hover_ended', properties) }
  },
}))

let container: HTMLDivElement
let button: HTMLButtonElement
let hit: Element | null
let clock: number
let focused: boolean
let hovered: boolean
let visibility: DocumentVisibilityState
let supportsHover: boolean
let frames: Map<number, FrameRequestCallback>
let dispose: () => void
let disconnect: () => void
let analytics: ReturnType<typeof createMarkerHoverAnalytics>
const mapRect = { x: 0, y: 0, left: 0, top: 0, right: 800, bottom: 600, width: 800, height: 600, toJSON: () => ({}) }
const markerRect = { ...mapRect, x: 80, y: 80, left: 80, top: 80, right: 180, bottom: 130, width: 100, height: 50 }

function records(name: string) { return gate.capture.mock.calls.filter(([event]) => event === name).map(([, properties]) => properties) }
function ended() { return records('map_marker_hover_ended') }
function pointer(type: string, target: Element = button, pointerType = 'mouse', relatedTarget: Element | null = null) {
  const event = new MouseEvent(type, { bubbles: true, clientX: 100, clientY: 100, relatedTarget })
  Object.defineProperty(event, 'pointerType', { value: pointerType })
  target.dispatchEvent(event)
}
async function paint() {
  await Promise.resolve()
  const scheduled = [...frames.values()]
  frames.clear()
  scheduled.forEach(callback => callback(clock))
  await Promise.resolve()
}
function stop(reason: AnalyticsStopReason) {
  gate.allowed = false
  if (reason !== 'pause') gate.generation++
  gate.stops.forEach(listener => listener(reason))
  gate.listeners.forEach(listener => listener())
}
function allow() { gate.allowed = true; gate.listeners.forEach(listener => listener()) }

beforeEach(() => {
  gate.allowed = true; gate.generation = 0; gate.capture.mockClear(); gate.stops.clear(); gate.listeners.clear()
  clock = 100; focused = true; hovered = false; supportsHover = true; visibility = 'visible'
  frames = new Map()
  let frameId = 0
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => { frames.set(++frameId, callback); return frameId })
  vi.stubGlobal('cancelAnimationFrame', (id: number) => frames.delete(id))
  vi.stubGlobal('matchMedia', () => ({ get matches() { return supportsHover }, addEventListener: vi.fn() }))
  vi.spyOn(performance, 'now').mockImplementation(() => clock)
  vi.spyOn(document, 'hasFocus').mockImplementation(() => focused)
  vi.spyOn(document, 'visibilityState', 'get').mockImplementation(() => visibility)
  container = document.createElement('div')
  button = document.createElement('button')
  button.dataset.mapComplexMarker = 'true'; button.dataset.complexId = '17'
  button.style.visibility = 'visible'
  button.append(document.createElement('span'))
  container.append(button); document.body.append(container)
  hit = button
  Object.defineProperty(document, 'elementFromPoint', { configurable: true, value: () => hit })
  vi.spyOn(container, 'getBoundingClientRect').mockReturnValue(mapRect)
  vi.spyOn(button, 'getBoundingClientRect').mockReturnValue(markerRect)
  const matches = button.matches.bind(button)
  vi.spyOn(button, 'matches').mockImplementation(selector => selector === ':hover' ? hovered : matches(selector))
  analytics = createMarkerHoverAnalytics()
  disconnect = analytics.connect()
  dispose = observeMarkerHovers(container, analytics)
})
afterEach(() => {
  disconnect(); dispose(); container.remove()
  vi.restoreAllMocks(); vi.unstubAllGlobals()
  Reflect.deleteProperty(document, 'elementFromPoint')
})

describe('마커 호버 사용 기회와 원본 시간', () => {
  it('마우스를 움직이지 않아도 보이는 개별 마커의 사용 기회를 한 번 남긴다', async () => {
    await paint(); await paint()
    expect(records('map_marker_hover_available')).toHaveLength(1)
    expect(ended()).toHaveLength(0)
  })
  it.each(['empty', 'aggregate', 'hidden', 'disabled', 'offscreen', 'covered', 'coarse', 'background', 'unfocused'])(
    '%s 지도에서는 사용 기회를 만들지 않는다', async condition => {
      if (condition === 'empty') button.remove()
      if (condition === 'aggregate') delete button.dataset.mapComplexMarker
      if (condition === 'hidden') container.setAttribute('aria-hidden', 'true')
      if (condition === 'disabled') button.disabled = true
      if (condition === 'offscreen') vi.mocked(button.getBoundingClientRect).mockReturnValue({ ...markerRect, left: -200, right: -100 })
      if (condition === 'covered') hit = document.body
      if (condition === 'coarse') supportsHover = false
      if (condition === 'background') visibility = 'hidden'
      if (condition === 'unfocused') focused = false
      await paint()
      expect(records('map_marker_hover_available')).toHaveLength(0)
    })
  it.each(['touch', 'pen'])('%s와 키보드 포커스는 호버로 세지 않는다', async type => {
    button.focus(); pointer('pointerover', button, type); clock += 1000; pointer('pointerout', button, type)
    await paint()
    expect(analytics.attribution('17')).toBeUndefined()
    expect(ended()).toHaveLength(0)
  })
  it.each([124, 500, 10000, 11000])('%i ms 원본을 유지하고 클릭으로 종료하지 않는다', duration => {
    pointer('pointerover')
    const original = analytics.attribution('17')
    clock += duration / 2
    button.dispatchEvent(new MouseEvent('click', { bubbles: true, detail: 1 }))
    expect(ended()).toHaveLength(0)
    expect(analytics.attribution('17')).toEqual(original)
    clock += duration / 2
    pointer('pointerout')
    expect(ended()).toEqual([expect.objectContaining({ ...original, duration_ms: duration, reason: 'pointer_leave',
      hover_started_at: expect.any(String), occurred_at: expect.any(String) })])
  })
  it('같은 단지 재진입은 별도 호버이며 단지 수와 호버 수를 구분할 수 있다', () => {
    pointer('pointerover'); clock += 700; pointer('pointerout')
    pointer('pointerover'); clock += 900; pointer('pointerout')
    expect(ended()).toHaveLength(2)
    expect(ended()[0].hover_id).not.toBe(ended()[1].hover_id)
    expect(ended()[0].exploration_id).toBe(ended()[1].exploration_id)
    expect(ended().map(row => row.complex_id)).toEqual(['17', '17'])
  })
  it('확대 영역 내부 요소를 오가거나 선택·하이라이트만 변경되면 타이머를 유지한다', async () => {
    hovered = true; pointer('pointerover')
    const original = analytics.attribution('17')
    clock += 700
    pointer('pointerout', button.firstElementChild!, 'mouse', button)
    button.classList.add('is-selected', 'is-highlighted')
    await paint()
    expect(analytics.attribution('17')).toEqual(original)
    clock += 300; pointer('pointerout')
    expect(ended()).toEqual([expect.objectContaining({ duration_ms: 1000 })])
  })
  it('원래 버튼 밖으로 펼쳐진 정보 영역만 보여도 그 위에서는 계속 측정한다', async () => {
    hovered = true; pointer('pointerover')
    const original = analytics.attribution('17')
    const content = button.firstElementChild as HTMLElement
    content.className = 'housing-map-marker__content'
    vi.spyOn(content, 'getBoundingClientRect').mockReturnValue({ ...markerRect, top: 20, right: 300, width: 220, height: 110 })
    Object.defineProperty(document, 'elementFromPoint', { configurable: true,
      value: (x: number, y: number) => x === 250 && y === 50 ? content : document.body })
    const move = new MouseEvent('pointermove', { bubbles: true, clientX: 250, clientY: 50 })
    Object.defineProperty(move, 'pointerType', { value: 'mouse' })
    content.dispatchEvent(move)
    clock += 700; await paint()
    expect(analytics.attribution('17')).toEqual(original)
    expect(ended()).toHaveLength(0)
    clock += 300; pointer('pointerout', content, 'mouse', document.body)
    expect(ended()).toEqual([expect.objectContaining({ duration_ms: 1000 })])
  })
})

describe('중단·동의·수명 관리', () => {
  it.each(['blur', 'visibility'] as const)('%s 중단 후 정지된 포인터를 재확인하고 비활성 시간은 합산하지 않는다', async interruption => {
    hovered = true; pointer('pointerover'); clock += 800
    if (interruption === 'blur') { focused = false; window.dispatchEvent(new Event('blur')) }
    else { visibility = 'hidden'; document.dispatchEvent(new Event('visibilitychange')) }
    stop('pause')
    expect(ended()).toEqual([expect.objectContaining({ duration_ms: 800,
      reason: interruption === 'blur' ? 'window_blur' : 'document_hidden' })])
    clock += 60_000
    focused = true; visibility = 'visible'; allow()
    window.dispatchEvent(new Event('focus')); await paint()
    clock += 600; pointer('pointerout')
    expect(ended()).toHaveLength(2)
    expect(ended()[1].duration_ms).toBe(600)
    expect(ended()[0].hover_id).not.toBe(ended()[1].hover_id)
    expect(records('map_marker_hover_available')).toHaveLength(1)
  })
  it('다른 창에서 복귀할 때 실제 :hover 상태가 아니면 마지막 좌표만으로 재개하지 않는다', async () => {
    pointer('pointerover'); clock += 600; window.dispatchEvent(new Event('blur'))
    clock += 10_000; window.dispatchEvent(new Event('focus')); await paint()
    expect(analytics.attribution('17')).toBeUndefined()
    expect(ended()).toHaveLength(1)
  })
  it.each(['removed', 'covered'] as const)('마커가 %s되면 종료 사유를 남긴다', async change => {
    pointer('pointerover'); clock += 750
    if (change === 'removed') { button.remove(); hit = null }
    else hit = document.body
    container.classList.add('changed')
    await paint()
    expect(ended()).toEqual([expect.objectContaining({ duration_ms: 750, reason: change === 'removed' ? 'marker_removed' : 'marker_occluded' })])
  })
  it('비활성화가 동의 저장소에서 먼저 통지되어도 같은 기록을 한 번 종료한다', () => {
    pointer('pointerover'); clock += 500; visibility = 'hidden'
    stop('pause'); document.dispatchEvent(new Event('visibilitychange'))
    expect(ended()).toEqual([expect.objectContaining({ duration_ms: 500, reason: 'document_hidden' })])
  })
  it('팝업이 나타나 pointerout이 먼저 와도 단순 이탈과 가림을 구분한다', () => {
    pointer('pointerover'); clock += 700
    hit = document.body
    pointer('pointerout', button, 'mouse', document.body)
    expect(ended()).toEqual([expect.objectContaining({ duration_ms: 700, reason: 'marker_occluded' })])
  })
  it('동의 이전 시간은 수집하지 않고 허용 후 현재 포인터에서 새로 측정한다', async () => {
    gate.allowed = false; hovered = true; pointer('pointerover'); clock += 5000; await paint()
    expect(gate.capture).not.toHaveBeenCalled()
    allow(); await paint(); clock += 600; pointer('pointerout')
    expect(ended()).toEqual([expect.objectContaining({ duration_ms: 600 })])
  })
  it.each(['reset', 'context-change', 'consent-replaced'] as const)('%s 때 진행 중인 기록과 이전 연결을 폐기하고 새 탐색 ID를 사용한다', async reason => {
    hovered = true; pointer('pointerover')
    const previous = analytics.attribution('17')
    clock += 1000; stop(reason)
    expect(analytics.attribution('17')).toBeUndefined()
    expect(ended()).toHaveLength(0)
    allow(); await paint()
    expect(analytics.attribution('17')?.exploration_id).not.toBe(previous?.exploration_id)
    expect(records('map_marker_hover_available')).toHaveLength(2)
  })
  it('화면 이동으로 확정하고 이후 제거·이벤트는 추가 수집하지 않는다', () => {
    pointer('pointerover'); clock += 700
    disconnect(); dispose()
    pointer('pointerover'); clock += 1000; pointer('pointerout')
    expect(ended()).toEqual([expect.objectContaining({ duration_ms: 700, reason: 'route_leave' })])
  })
  it('페이지 숨김은 별도 종료 사유로 한 번 남긴다', () => {
    pointer('pointerover'); clock += 700
    window.dispatchEvent(new Event('pagehide')); window.dispatchEvent(new Event('blur'))
    expect(ended()).toEqual([expect.objectContaining({ duration_ms: 700, reason: 'page_hide' })])
  })
})
