import type { MarkerHoverAnalytics, MarkerHoverEndReason } from '../../analytics/markerHoverAnalytics'

const MARKER_SELECTOR = 'button[data-map-complex-marker="true"]'

/** One set of browser listeners per map; no timers or React renders per marker/mouse move. */
export function observeMarkerHovers(container: HTMLElement, analytics: MarkerHoverAnalytics): () => void {
  if (typeof window.matchMedia !== 'function' || typeof document.elementFromPoint !== 'function') return () => {}
  const controller = new AbortController()
  const { signal } = controller
  const media = window.matchMedia('(any-hover: hover) and (any-pointer: fine)')
  let pointer: { x: number; y: number } | null = null
  let active: { button: HTMLButtonElement; id: string; complexId: string } | null = null
  let frame: number | null = null
  let disposed = false

  function end(reason: MarkerHoverEndReason) {
    if (!active) return
    analytics.end(active.id, reason)
    active = null
  }
  function pointerExit() {
    if (!active) return
    if (!active.button.isConnected) end('marker_removed')
    else if (!usable(active.button)) end('marker_occluded')
    else end('pointer_leave')
  }
  function foreground() {
    return media.matches && document.visibilityState === 'visible' && document.hasFocus()
  }
  function usable(button: HTMLButtonElement) {
    if (!container.contains(button) || button.disabled || button.closest('[hidden], [inert], [aria-hidden="true"]')) return false
    const style = getComputedStyle(button)
    if (style.display === 'none' || style.visibility !== 'visible' || style.pointerEvents === 'none') return false
    for (let element: HTMLElement | null = button; element; element = element.parentElement) {
      if (getComputedStyle(element).opacity === '0') return false
      if (element === container) break
    }
    // The button stays 54px high; the revealed content extends above and beside it.
    const content = button.querySelector<HTMLElement>('.housing-map-marker__content')
    const rectangles = [button.getBoundingClientRect(), ...(content ? [content.getBoundingClientRect()] : [])]
      .filter(rectangle => rectangle.width > 0 && rectangle.height > 0)
    if (rectangles.length === 0) return false
    const map = container.getBoundingClientRect()
    const left = Math.max(Math.min(...rectangles.map(rectangle => rectangle.left)), map.left, 0)
    const right = Math.min(Math.max(...rectangles.map(rectangle => rectangle.right)), map.right, window.innerWidth)
    const top = Math.max(Math.min(...rectangles.map(rectangle => rectangle.top)), map.top, 0)
    const bottom = Math.min(Math.max(...rectangles.map(rectangle => rectangle.bottom)), map.bottom, window.innerHeight)
    if (left >= right || top >= bottom) return false
    const hitMarker = (x: number, y: number) => {
      const hit = document.elementFromPoint(x, y)
      return hit !== null && button.contains(hit)
    }
    if (pointer && pointer.x >= left && pointer.x < right && pointer.y >= top && pointer.y < bottom
      && hitMarker(pointer.x, pointer.y)) return true
    // Hit testing also excludes markers covered by dialogs, panels and other markers.
    return [[0.5, 0.5], [0.1, 0.1], [0.9, 0.1], [0.1, 0.9], [0.9, 0.9]]
      .some(([x, y]) => hitMarker(left + (right - left) * x, top + (bottom - top) * y))
  }
  function markerAtPointer() {
    if (!pointer) return null
    const hit = document.elementFromPoint(pointer.x, pointer.y)
    const button = hit?.closest<HTMLButtonElement>(MARKER_SELECTOR)
    return button && container.contains(button) ? button : null
  }
  function begin(button: HTMLButtonElement) {
    if (!foreground() || !usable(button)) return
    const complexId = button.dataset.complexId
    if (!complexId || active?.button === button) return
    end('pointer_leave')
    const id = analytics.begin(complexId)
    if (id) active = { button, complexId, id }
  }
  function refresh() {
    frame = null
    if (disposed) return
    if (active && analytics.attribution(active.complexId)?.hover_id !== active.id) active = null
    if (!foreground()) {
      end(document.visibilityState !== 'visible' ? 'document_hidden' : !document.hasFocus() ? 'window_blur' : 'marker_occluded')
      return
    }
    if (active && !active.button.isConnected) end('marker_removed')
    if (active && (!usable(active.button) || markerAtPointer() !== active.button)) end('marker_occluded')
    if (!analytics.available(() => [...container.querySelectorAll<HTMLButtonElement>(MARKER_SELECTOR)].some(usable))) return
    const button = markerAtPointer()
    // A stale coordinate alone must not start a hover after returning from another window.
    if (button?.matches(':hover')) begin(button)
  }
  function schedule() {
    if (!disposed && frame === null) frame = requestAnimationFrame(refresh)
  }
  const rememberPointer = (event: PointerEvent) => {
    if (event.pointerType !== 'mouse') return
    pointer = { x: event.clientX, y: event.clientY }
    schedule()
  }
  container.addEventListener('pointerover', event => {
    if (event.pointerType !== 'mouse') return
    rememberPointer(event)
    const button = event.target instanceof Element ? event.target.closest<HTMLButtonElement>(MARKER_SELECTOR) : null
    if (button) begin(button)
  }, { capture: true, signal })
  container.addEventListener('pointerout', event => {
    if (event.pointerType !== 'mouse' || !active || !(event.target instanceof Node) || !active.button.contains(event.target)) return
    if (!(event.relatedTarget instanceof Node) || !active.button.contains(event.relatedTarget)) pointerExit()
  }, { capture: true, signal })
  window.addEventListener('pointermove', rememberPointer, { capture: true, passive: true, signal })
  window.addEventListener('pointerout', event => {
    if (event.pointerType === 'mouse' && event.relatedTarget === null) { pointer = null; pointerExit() }
  }, { signal })
  window.addEventListener('blur', () => end('window_blur'), { signal })
  window.addEventListener('pagehide', () => end('page_hide'), { signal })
  window.addEventListener('focus', schedule, { signal })
  window.addEventListener('pageshow', schedule, { signal })
  window.addEventListener('resize', schedule, { signal })
  document.addEventListener('scroll', schedule, { capture: true, passive: true, signal })
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState !== 'visible') end('document_hidden')
    else schedule()
  }, { signal })
  media.addEventListener('change', schedule, { signal })
  const unsubscribe = analytics.subscribe(schedule)
  const observer = new MutationObserver(schedule)
  observer.observe(document.body, { subtree: true, childList: true, attributes: true,
    attributeFilter: ['class', 'style', 'hidden', 'inert', 'aria-hidden', 'open', 'disabled'] })
  schedule()
  return () => {
    disposed = true
    controller.abort()
    observer.disconnect()
    unsubscribe()
    if (frame !== null) cancelAnimationFrame(frame)
    end('marker_removed')
  }
}
