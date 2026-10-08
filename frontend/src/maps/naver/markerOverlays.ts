import { captureProductEvent, createAnalyticsId } from '../../analytics/productAnalytics'
import { COMPLEX_MARKER_ANCHOR_X, createComplexMarkerButton, markerWidth } from './complexMarkerButton.ts'
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
  readonly isInteracting: () => boolean
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
  readonly previewScopeId?: () => string
  readonly enterDelay?: number
  readonly mapInstance: naver.maps.Map
  readonly maps: typeof naver.maps
  readonly marker: RenderedMarker
  readonly onAggregateMarkerSelect: (
    marker: NaverMapAggregateMarker,
  ) => void
  readonly onMarkerHighlight: ((complexId: string | null) => void) | undefined
  readonly onMarkerSelect: ((complexId: string) => void) | undefined
}

export function createMarker({
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
  const height = isAggregate ? 52 : 58
  const title = isAggregate
    ? aggregateMarkerTitle(marker.marker)
    : undefined
  bindMarkerActivation(button, () => {
    if (marker.kind === 'aggregate') {
      onAggregateMarkerSelect(marker.marker)
    } else {
      onMarkerSelect?.(marker.marker.id)
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
  const isInteracting = marker.kind === 'complex'
    ? bindMarkerHighlight(button, marker.marker.id, (complexId) => {
      overlay.setZIndex(complexId !== null ? 40
        : button.classList.contains('is-selected') ? 30
          : button.classList.contains('is-highlighted') ? 20 : 10)
      onMarkerHighlight?.(complexId)
    }, controller.signal, previewScopeId)
    : () => false
  return {
    button,
    dispose: () => controller.abort(),
    isInteracting,
    overlay,
    rendered: marker,
    presentation: null,
  }
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

function bindMarkerActivation(
  button: HTMLButtonElement,
  onSelect: () => void,
  signal: AbortSignal,
) {
  // Native keyboard clicks have no pointer coordinates for the SDK to process.
  button.addEventListener('click', (event) => {
    event.stopImmediatePropagation()
    onSelect()
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

function bindMarkerHighlight(
  button: HTMLButtonElement,
  complexId: string,
  onHighlight: (complexId: string | null) => void,
  signal: AbortSignal,
  previewScopeId: (() => string) | undefined,
) {
  let focused = false
  let pointerInside = false
  let directFocus = false
  let keyboardAt = -Infinity
  let previewTimer: number | null = null
  const fallbackScope = createAnalyticsId()
  document.addEventListener('keydown', event => { if (event.key === 'Tab') keyboardAt = performance.now() }, { capture: true, signal })
  const clearPreview = () => { if (previewTimer !== null) window.clearTimeout(previewTimer); previewTimer = null }
  signal.addEventListener('abort', clearPreview, { once: true })
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
    onHighlight(focused || pointerInside ? complexId : null)
    updatePreview()
  }
  button.addEventListener('mouseenter', () => {
    pointerInside = true
    updateHighlight()
  }, { signal })
  button.addEventListener('mouseleave', () => {
    pointerInside = false
    updateHighlight()
  }, { signal })
  button.addEventListener('focus', () => {
    focused = true
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
  createdMarkers.forEach((created) => {
    const { button, overlay, rendered, presentation } = created
    const selected = rendered.kind === 'complex'
      && selectedIds.has(rendered.marker.id)
    const highlighted = markerIsHighlighted(rendered, highlightedIds)
    const zIndex = markerZIndex(rendered, selected, highlighted)
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

function markerZIndex(marker: RenderedMarker, selected: boolean, highlighted: boolean) {
  if (selected) {
    return 30
  }
  if (highlighted) {
    return 20
  }
  if (marker.kind === 'aggregate') {
    return 0
  }
  return 10
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
