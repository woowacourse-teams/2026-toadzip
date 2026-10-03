import type { ComplexMarkerCluster } from './markerClustering.ts'
import { createComplexMarkerButton, markerSummary, markerWidth } from './complexMarkerButton.ts'
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
  readonly enterDelay?: number
  readonly mapInstance: naver.maps.Map
  readonly maps: typeof naver.maps
  readonly marker: RenderedMarker
  readonly onAggregateMarkerSelect: (
    marker: NaverMapAggregateMarker,
  ) => void
  readonly onClusterSelect: (cluster: ComplexMarkerCluster) => void
  readonly onMarkerHighlight: ((complexId: string | null) => void) | undefined
  readonly onMarkerSelect: ((complexId: string) => void) | undefined
}

export function createMarker({
  enterDelay,
  mapInstance,
  maps,
  marker,
  onAggregateMarkerSelect,
  onClusterSelect,
  onMarkerHighlight,
  onMarkerSelect,
}: CreateMarkerOptions): CreatedMarker {
  const controller = new AbortController()
  const button = marker.kind === 'complex'
    ? createComplexMarkerButton(marker.marker)
    : aggregateMarkerButton(aggregateMarkerData(marker))
  const width = marker.kind === 'complex' ? markerWidth(marker.marker) : 104
  const height = marker.kind === 'complex' ? 66 : 68
  const title = marker.kind === 'complex'
    ? markerSummary(marker.marker) : aggregateMarkerTitle(aggregateMarkerData(marker))
  if (marker.kind === 'cluster') {
    delete button.dataset.mapAggregateMarker
    button.dataset.mapClusterMarker = 'true'
    button.setAttribute('aria-label', `단지 ${marker.marker.members.length}곳, 모여 있는 단지 확대해서 보기`)
  }
  bindMarkerActivation(button, () => {
    if (marker.kind === 'aggregate') {
      onAggregateMarkerSelect(marker.marker)
    } else if (marker.kind === 'cluster') {
      onClusterSelect(marker.marker)
    } else {
      onMarkerSelect?.(marker.marker.id)
    }
  }, controller.signal)
  const isInteracting = marker.kind === 'complex'
    ? bindMarkerHighlight(button, marker.marker.id,
      (complexId) => onMarkerHighlight?.(complexId), controller.signal)
    : () => false
  const overlay = new maps.Marker({
    clickable: true,
    cursor: 'pointer',
    icon: {
      anchor: new maps.Point(width / 2, height),
      content: createMarkerContent(button, controller.signal, enterDelay),
      size: new maps.Size(width, height),
    },
    map: mapInstance,
    position: new maps.LatLng(marker.marker.latitude, marker.marker.longitude),
    title,
  })
  return {
    button,
    dispose: () => controller.abort(),
    isInteracting,
    overlay,
    rendered: marker,
    presentation: null,
  }
}

function aggregateMarkerData(marker: Exclude<RenderedMarker, { kind: 'complex' }>): NaverMapAggregateMarker {
  return marker.kind === 'aggregate' ? marker.marker : {
    ...marker.marker, groupKey: marker.marker.id, groupLabel: '단지',
    uniqueComplexCount: marker.marker.members.length, expansionZoom: 17, nextStage: 4,
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
) {
  let focused = false
  let pointerInside = false
  const updateHighlight = () => {
    onHighlight(focused || pointerInside ? complexId : null)
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
    updateHighlight()
  }, { signal })
  button.addEventListener('blur', () => {
    focused = false
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
    if (presentation?.zIndex !== zIndex) {
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
  if (marker.kind === 'cluster') {
    return marker.marker.members.some((member) => highlightedIds.has(member.id))
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
  const exact = createdMarkers.find(({ rendered }) =>
    rendered.kind === focus.kind && renderedMarkerId(rendered) === focus.id,
  )
  if (exact || focus.kind !== 'cluster') return exact
  const previousMembers = new Set(focus.id.split(','))
  return createdMarkers.find(({ rendered }) => rendered.kind === 'complex'
    ? previousMembers.has(rendered.marker.id)
    : rendered.kind === 'cluster' && rendered.marker.members.some((member) => previousMembers.has(member.id)))
}
