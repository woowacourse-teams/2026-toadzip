import { captureProductEvent, createAnalyticsId } from '../../analytics/productAnalytics'
import type { MarkerHoverAttribution } from '../../analytics/markerHoverAnalytics'
import { isMobileViewport } from '../../public-housing/components/useMobileViewport'
import { COMPLEX_MARKER_ANCHOR_X, COMPLEX_MARKER_HEIGHT, createComplexMarkerButton, markerWidth } from './complexMarkerButton.ts'
import { renderedMarkerContentKey, renderedMarkerId, type RenderedMarker } from './markerData.ts'
import type {
  NaverMapAggregateMarker,
  NaverMapMarker,
} from './naverMapTypes.ts'

interface MarkerFocusTarget {
  readonly kind: RenderedMarker['kind']
  readonly id: string
}

export interface CreatedMarker {
  readonly button: HTMLButtonElement
  readonly dispose: () => void
  isInteracting: () => boolean
  readonly overlay: naver.maps.Marker
  readonly rendered: RenderedMarker
  presentation: MarkerPresentation | null
}

interface MarkerPresentation {
  readonly selected: boolean
  readonly highlighted: boolean
  readonly zIndex: number
}

export function updateAggregateMarkerAvailability(
  markers: readonly CreatedMarker[],
  disabled: boolean,
) {
  markers.forEach(({ button, rendered }) => {
    if (rendered.kind === 'aggregate') {
      button.disabled = disabled
    }
  })
}

interface CreateMarkerOptions {
  readonly interactionGroup?: MarkerInteractionGroup
  readonly hoverAttribution?: (complexId: string) => MarkerHoverAttribution | undefined
  readonly previewScopeId?: () => string
  readonly enterDelay?: number
  readonly mapInstance: naver.maps.Map
  readonly maps: typeof naver.maps
  readonly marker: RenderedMarker
  readonly onAggregateMarkerSelect: (
    marker: NaverMapAggregateMarker,
  ) => void
  readonly onMarkerHighlight: ((complexId: string | null) => void) | undefined
  readonly onMarkerSelect: ((complexId: string, hover?: MarkerHoverAttribution) => void) | undefined
}

export function createMarker({
  interactionGroup,
  hoverAttribution,
  enterDelay,
  previewScopeId,
  mapInstance,
  maps,
  marker,
  onAggregateMarkerSelect,
  onMarkerHighlight,
  onMarkerSelect,
}: CreateMarkerOptions): CreatedMarker {
  const controller = new AbortController()
  const isAggregate = marker.kind === 'aggregate'
  const button = isAggregate
    ? aggregateMarkerButton(marker.marker)
    : createComplexMarkerButton(marker.marker)
  const width = isAggregate ? 92 : markerWidth(marker.marker)
  const height = isAggregate ? 52 : COMPLEX_MARKER_HEIGHT
  const title = isAggregate
    ? aggregateMarkerTitle(marker.marker)
    : undefined
  bindMarkerActivation(button, (event) => {
    if (marker.kind === 'aggregate') {
      onAggregateMarkerSelect(marker.marker)
    } else {
      const mouse = 'pointerType' in event ? event.pointerType === 'mouse' : event.detail > 0
      const hover = mouse ? hoverAttribution?.(marker.marker.id) : undefined
      if (hover) onMarkerSelect?.(marker.marker.id, hover)
      else onMarkerSelect?.(marker.marker.id)
    }
  }, controller.signal)
  const overlay = new maps.Marker({
    clickable: true,
    cursor: 'pointer',
    icon: {
      anchor: new maps.Point(isAggregate ? width / 2 : COMPLEX_MARKER_ANCHOR_X, isAggregate ? height / 2 : height),
      content: createMarkerContent(button, controller.signal, enterDelay),
      size: new maps.Size(width, height),
    },
    map: mapInstance,
    position: new maps.LatLng(marker.marker.latitude, marker.marker.longitude),
    title,
  })
  const created: CreatedMarker = {
    button,
    dispose: () => controller.abort(),
    isInteracting: () => false,
    overlay,
    rendered: marker,
    presentation: null,
  }
  if (marker.kind === 'complex') {
    created.isInteracting = bindMarkerHighlight(button, marker.marker.id, (complexId) => {
      // 펼친 마커는 맨 위로 올리고, 접으면 겹침 순서에서 원래 자리로 돌려놓는다.
      overlay.setZIndex(complexId !== null ? INTERACTING_Z_INDEX
        : created.presentation?.zIndex ?? COMPLEX_BAND * RANK_SPAN)
      onMarkerHighlight?.(complexId)
    }, controller.signal, previewScopeId, interactionGroup)
  }
  return created
}

function createMarkerContent(
  button: HTMLButtonElement,
  signal: AbortSignal,
  enterDelay: number | undefined,
) {
  const content = document.createElement('div')
  const motion = document.createElement('div')
  content.className = 'housing-marker-content'
  motion.append(button)
  content.append(motion)
  const reducedMotion = typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches
  if (enterDelay !== undefined && !reducedMotion) {
    motion.className = 'housing-marker-enter'
    motion.style.setProperty('--marker-enter-delay', `${enterDelay}ms`)
    const finishEntry = (event: Event) => {
      if (event.target !== motion) {
        return
      }
      motion.classList.remove('housing-marker-enter')
      motion.style.removeProperty('--marker-enter-delay')
      motion.removeEventListener('animationend', finishEntry)
      motion.removeEventListener('animationcancel', finishEntry)
    }
    motion.addEventListener('animationend', finishEntry, { signal })
    motion.addEventListener('animationcancel', finishEntry, { signal })
  }
  return content
}

const DRAG_CLICK_TOLERANCE_PX = 5

function bindMarkerActivation(
  button: HTMLButtonElement,
  onSelect: (event: MouseEvent) => void,
  signal: AbortSignal,
) {
  // 마커는 지도와 같이 움직여서, 마커 위에서 시작한 지도 끌기도 손을 떼면 같은 마커의 click이 된다.
  // 누른 곳에서 멀리 움직인 click은 끌기로 보고 선택하지 않는다.
  let pressPoint: { x: number; y: number } | null = null
  button.addEventListener('pointerdown', (event) => {
    pressPoint = { x: event.clientX, y: event.clientY }
  }, { signal })
  // Native keyboard clicks have no pointer coordinates for the SDK to process.
  button.addEventListener('click', (event) => {
    event.stopImmediatePropagation()
    const press = pressPoint
    pressPoint = null
    if (press && event.detail > 0 && Math.hypot(event.clientX - press.x, event.clientY - press.y) > DRAG_CLICK_TOLERANCE_PX) {
      return
    }
    onSelect(event)
  }, { capture: true, signal })
  const keepKeyboardOnMarker = (event: KeyboardEvent) => {
    // Preserve native Enter/Space activation and keep both phases off the map.
    event.stopPropagation()
  }
  button.addEventListener('keydown', keepKeyboardOnMarker, { signal })
  button.addEventListener('keyup', keepKeyboardOnMarker, { signal })
}

function aggregateMarkerButton(marker: NaverMapAggregateMarker) {
  const button = document.createElement('button')
  const label = document.createElement('span')
  const count = document.createElement('strong')
  button.type = 'button'
  button.className = 'housing-map-aggregate'
  button.dataset.aggregateMarkerId = marker.groupKey
  button.dataset.expansionZoom = String(marker.expansionZoom)
  button.dataset.groupKey = marker.groupKey
  button.dataset.mapAggregateMarker = 'true'
  button.dataset.nextStage = String(marker.nextStage)
  button.dataset.uniqueComplexCount = String(marker.uniqueComplexCount)
  button.setAttribute('aria-label', aggregateMarkerAriaLabel(marker))
  button.title = aggregateMarkerTitle(marker)
  label.className = 'housing-map-aggregate__label'
  label.textContent = marker.groupLabel
  count.className = 'housing-map-aggregate__count'
  count.textContent = `${marker.uniqueComplexCount}곳`
  button.append(label, count)
  return button
}

function aggregateMarkerAriaLabel(marker: NaverMapAggregateMarker) {
  return `${aggregateMarkerTitle(marker)}, 다음 지역 단계로 확대해서 보기`
}

function aggregateMarkerTitle(marker: NaverMapAggregateMarker) {
  return `${marker.groupLabel} ${marker.uniqueComplexCount}곳`
}

export interface MarkerInteractionGroup {
  deactivate: (() => void) | null
}

/** Mouse hover waits briefly so markers the cursor only passes over stay collapsed. */
export const MARKER_HOVER_DELAY_MS = 150

// Focus restored by code (e.g. after a detail closes) expands a marker only when the
// last input was the keyboard, like :focus-visible, which jsdom cannot evaluate.
let lastInputWasKeyboard = true
let inputModalityTracked = false

function trackInputModality() {
  if (inputModalityTracked) return
  inputModalityTracked = true
  document.addEventListener('keydown', (event) => {
    if (!event.metaKey && !event.ctrlKey && !event.altKey) lastInputWasKeyboard = true
  }, true)
  document.addEventListener('pointerdown', () => { lastInputWasKeyboard = false }, true)
}

function bindMarkerHighlight(
  button: HTMLButtonElement,
  complexId: string,
  onHighlight: (complexId: string | null) => void,
  signal: AbortSignal,
  previewScopeId: (() => string) | undefined,
  interactionGroup?: MarkerInteractionGroup,
) {
  trackInputModality()
  let focused = false
  let keyboardFocused = false
  let pointerInside = false
  let pointerShown = false
  let hoverTimer: number | null = null
  let directFocus = false
  let keyboardAt = -Infinity
  let previewTimer: number | null = null
  let highlighted = false
  const fallbackScope = createAnalyticsId()
  document.addEventListener('keydown', event => { if (event.key === 'Tab') keyboardAt = performance.now() }, { capture: true, signal })
  const clearPreview = () => { if (previewTimer !== null) window.clearTimeout(previewTimer); previewTimer = null }
  const clearHoverTimer = () => { if (hoverTimer !== null) window.clearTimeout(hoverTimer); hoverTimer = null }
  signal.addEventListener('abort', clearPreview, { once: true })
  signal.addEventListener('abort', clearHoverTimer, { once: true })
  function updatePreview() {
    if (!pointerInside && !directFocus) { clearPreview(); return }
    if (previewTimer !== null) return
    previewTimer = window.setTimeout(() => {
      previewTimer = null
      if (signal.aborted || !button.isConnected || button.closest('[inert], [hidden], [aria-hidden="true"]') || document.visibilityState !== 'visible') return
      if ([...document.querySelectorAll('dialog[open], [role="dialog"][aria-modal="true"]')].some(dialog => !dialog.contains(button) && (dialog.getAttribute('aria-modal') !== 'false' || !dialog.classList.contains('housing-detail-layer')))) return
      captureProductEvent('map_marker_previewed', { complex_id: complexId, method: pointerInside ? 'pointer' : 'keyboard' }, { dedupeKey: `marker-preview:${previewScopeId?.() ?? fallbackScope}:${complexId}` })
    }, 500)
  }
  const updateHighlight = () => {
    const next = (focused && keyboardFocused) || pointerShown
    if (next !== highlighted) {
      highlighted = next
      button.classList.toggle('is-previewing', next)
      onHighlight(next ? complexId : null)
    }
    updatePreview()
  }
  const deactivate = () => {
    clearHoverTimer()
    focused = false
    keyboardFocused = false
    pointerInside = false
    pointerShown = false
    directFocus = false
    updateHighlight()
  }
  const claim = () => {
    if (!interactionGroup || !isMobileViewport()) return
    if (interactionGroup.deactivate !== deactivate) interactionGroup.deactivate?.()
    interactionGroup.deactivate = deactivate
  }
  signal.addEventListener('abort', () => {
    if (interactionGroup?.deactivate === deactivate) interactionGroup.deactivate = null
  }, { once: true })
  // Touch and pen expand a marker only while pressed; they have no leave event to collapse it later.
  const isPress = (event: PointerEvent) => event.pointerType === 'touch' || event.pointerType === 'pen'
  button.addEventListener('pointerdown', (event) => {
    if (!isPress(event)) return
    claim()
    clearHoverTimer()
    pointerInside = true
    pointerShown = true
    updateHighlight()
  }, { signal })
  const releasePress = (event: PointerEvent) => {
    if (!isPress(event)) return
    pointerInside = false
    pointerShown = false
    updateHighlight()
  }
  button.addEventListener('pointerup', releasePress, { signal })
  button.addEventListener('pointercancel', releasePress, { signal })
  // Pointer events tell a real mouse apart from the mouse events a touch tap emulates.
  button.addEventListener('pointerenter', (event) => {
    if (event.pointerType !== 'mouse') return
    claim()
    pointerInside = true
    if (!pointerShown && hoverTimer === null) {
      hoverTimer = window.setTimeout(() => {
        hoverTimer = null
        pointerShown = pointerInside
        updateHighlight()
      }, MARKER_HOVER_DELAY_MS)
    }
    updateHighlight()
  }, { signal })
  button.addEventListener('pointerleave', (event) => {
    if (event.pointerType !== 'mouse') return
    clearHoverTimer()
    pointerInside = false
    pointerShown = false
    updateHighlight()
  }, { signal })
  button.addEventListener('focus', () => {
    claim()
    focused = true
    keyboardFocused = lastInputWasKeyboard
    directFocus = performance.now() - keyboardAt < 100
    updateHighlight()
  }, { signal })
  button.addEventListener('blur', () => {
    focused = false
    directFocus = false
    updateHighlight()
  }, { signal })
  return () => focused || pointerInside
}

export function clearMarkers(markers: readonly CreatedMarker[]) {
  markers.forEach(({ dispose, overlay }) => {
    dispose()
    overlay.setMap(null)
  })
}

export function applyMarkerPresentation(
  createdMarkers: readonly CreatedMarker[],
  markers: readonly NaverMapMarker[],
) {
  const selectedIds = new Set(
    markers.filter(({ selected }) => selected).map(({ id }) => id),
  )
  const highlightedIds = new Set(
    markers.filter(({ highlighted }) => highlighted).map(({ id }) => id),
  )
  const ranks = stackingRanks(createdMarkers)
  createdMarkers.forEach((created) => {
    const { button, overlay, rendered, presentation } = created
    const selected = rendered.kind === 'complex'
      && selectedIds.has(rendered.marker.id)
    const highlighted = markerIsHighlighted(rendered, highlightedIds)
    const zIndex = markerZIndex(rendered, selected, highlighted) * RANK_SPAN + (ranks.get(created) ?? 0)
    if (presentation?.selected === selected
      && presentation.highlighted === highlighted
      && presentation.zIndex === zIndex) {
      return
    }
    if (presentation?.selected !== selected) {
      button.classList.toggle('is-selected', selected)
      if (rendered.kind === 'complex') {
        button.setAttribute('aria-pressed', String(selected))
      }
    }
    if (presentation?.highlighted !== highlighted) {
      button.classList.toggle('is-highlighted', highlighted)
    }
    if (presentation?.zIndex !== zIndex && !created.isInteracting()) {
      overlay.setZIndex(zIndex)
    }
    created.presentation = { selected, highlighted, zIndex }
  })
}

function markerIsHighlighted(
  marker: RenderedMarker,
  highlightedIds: ReadonlySet<string>,
) {
  if (marker.kind === 'aggregate') {
    return false
  }
  return highlightedIds.has(marker.marker.id)
}

// 겹친 마커의 순서는 지도 SDK가 마커를 만든 순서(나중에 만든 것이 위)를 따른다.
// 줌·이동으로 일부만 다시 만들면 순서가 뒤바뀌므로, 같은 층 안에서는 아래 규칙으로 순위를 매겨 고정한다.
const AGGREGATE_BAND = 0
const COMPLEX_BAND = 10
const HIGHLIGHTED_BAND = 20
const SELECTED_BAND = 30
const INTERACTING_BAND = 40
/** 한 층 안에 매길 수 있는 순위 수. 화면에 이보다 많은 마커가 뜨지 않는다. */
const RANK_SPAN = 100_000
const INTERACTING_Z_INDEX = INTERACTING_BAND * RANK_SPAN

function markerZIndex(marker: RenderedMarker, selected: boolean, highlighted: boolean) {
  if (selected) {
    return SELECTED_BAND
  }
  if (highlighted) {
    return HIGHLIGHTED_BAND
  }
  if (marker.kind === 'aggregate') {
    return AGGREGATE_BAND
  }
  return COMPLEX_BAND
}

/** 지역 묶음은 단지가 많은 곳을, 단지 마커는 남쪽(화면 아래)에 있는 것을 위에 둔다. */
function stackingRanks(createdMarkers: readonly CreatedMarker[]) {
  const ordered = [...createdMarkers].sort((left, right) =>
    compareStacking(left.rendered, right.rendered))
  return new Map(ordered.map((created, rank) => [created, rank]))
}

function compareStacking(left: RenderedMarker, right: RenderedMarker) {
  if (left.kind === 'aggregate' && right.kind === 'aggregate') {
    return left.marker.uniqueComplexCount - right.marker.uniqueComplexCount
      || right.marker.groupKey.localeCompare(left.marker.groupKey)
  }
  return right.marker.latitude - left.marker.latitude
    || renderedMarkerId(left).localeCompare(renderedMarkerId(right))
}

export function sameRenderedMarkers(
  current: readonly CreatedMarker[],
  next: readonly RenderedMarker[],
) {
  if (current.length !== next.length) {
    return false
  }
  return current.every(({ rendered }, index) =>
    renderedMarkerContentKey(rendered)
      === renderedMarkerContentKey(next[index]),
  )
}

export function readMarkerFocus(
  createdMarkers: readonly CreatedMarker[],
): MarkerFocusTarget | null {
  const focused = createdMarkers.find(
    ({ button }) => button === document.activeElement,
  )
  if (!focused) {
    return null
  }
  return {
    kind: focused.rendered.kind,
    id: renderedMarkerId(focused.rendered),
  }
}

export function restoreMarkerFocus(
  createdMarkers: readonly CreatedMarker[],
  focus: MarkerFocusTarget | null,
) {
  if (!focus) {
    return undefined
  }
  const target = findMarkerFocusTarget(createdMarkers, focus)
  return target && target.button !== document.activeElement
    ? window.setTimeout(() => target.button.focus({ preventScroll: true }))
    : undefined
}

function findMarkerFocusTarget(
  createdMarkers: readonly CreatedMarker[],
  focus: MarkerFocusTarget,
) {
  return createdMarkers.find(({ rendered }) =>
    rendered.kind === focus.kind && renderedMarkerId(rendered) === focus.id,
  )
}
