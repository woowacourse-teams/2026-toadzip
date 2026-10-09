import { useCallback, useEffect, useRef } from 'react'
import { captureProductEvent, createAnalyticsId } from '../../analytics/productAnalytics'
import { isForegroundElement } from './useProductDetailAnalytics'

export function useListMeasurement(visible: boolean, type: 'region_complex' | 'announcement' | 'recent', key: string) {
  const visit = useRef<{ id: string; key: string; type: string; scrolled: boolean } | null>(null)
  const entry = useRef('navigation')
  const lastDetail = useRef<string | null>(null)
  const resumeAfterDetail = useRef(false)
  useEffect(() => {
    const panel = document.getElementById('housing-list-page')
    if (!panel) return
    let cancelled = false
    let inputUntil = 0
    function measure() {
      if (cancelled || !panel) return
      const detail = document.querySelector<HTMLElement>('dialog.housing-detail-layer[open]')?.dataset.detailVisitId ?? null
      if (detail !== null && detail !== lastDetail.current) resumeAfterDetail.current = true
      lastDetail.current = detail
      const content = panel.querySelector<HTMLElement>('#housing-list-content')
      const exposed = visible && content !== null && isForegroundElement(content) && !hasForegroundModal(panel)
      if (visit.current && (!exposed || visit.current.key !== key || visit.current.type !== type)) {
        captureProductEvent('list_closed', { list_view_id: visit.current.id, list_type: visit.current.type })
        visit.current = null
        delete panel.dataset.listViewId
      }
      if (!exposed || visit.current) return
      visit.current = { id: createAnalyticsId(), key, type, scrolled: false }
      panel.dataset.listViewId = visit.current.id
      captureProductEvent('list_opened', { list_view_id: visit.current.id, list_type: type, entry_point: entry.current })
      entry.current = 'navigation'
      resumeAfterDetail.current = false
    }
    function resume() {
      if (!resumeAfterDetail.current || !panel || hasForegroundModal(panel)) return
      const content = panel.querySelector<HTMLElement>('#housing-list-content')
      if (!visible || !content || !isForegroundElement(content)) return
      if (visit.current) captureProductEvent('list_closed', { list_view_id: visit.current.id, list_type: visit.current.type })
      visit.current = null
      entry.current = 'detail_return'
      measure()
    }
    function intent(event: Event) {
      if (event instanceof KeyboardEvent && !['ArrowDown', 'ArrowUp', 'PageDown', 'PageUp', 'Home', 'End', ' '].includes(event.key)) return
      resume()
      inputUntil = performance.now() + 1000
    }
    function scroll(event: Event) {
      const target = event.target
      const current = visit.current
      if (!(target instanceof HTMLElement) || !target.classList.contains('housing-results__scroll')
        || !current || current.scrolled || performance.now() > inputUntil || !isForegroundElement(target) || hasForegroundModal(target)) return
      current.scrolled = true
      captureProductEvent('list_scrolled', { list_view_id: current.id, list_type: current.type })
    }
    const observer = new MutationObserver(measure)
    observer.observe(document.body, { subtree: true, childList: true, attributes: true,
      attributeFilter: ['hidden', 'inert', 'aria-hidden', 'aria-modal', 'open', 'class', 'style', 'data-detail-visit-id'] })
    panel.addEventListener('wheel', intent, { passive: true })
    panel.addEventListener('touchmove', intent, { passive: true })
    panel.addEventListener('keydown', intent)
    panel.addEventListener('pointerdown', resume)
    panel.addEventListener('scroll', scroll, true)
    document.addEventListener('visibilitychange', measure)
    window.addEventListener('resize', measure)
    queueMicrotask(measure)
    return () => {
      cancelled = true; observer.disconnect()
      panel.removeEventListener('wheel', intent); panel.removeEventListener('touchmove', intent)
      panel.removeEventListener('pointerdown', resume)
      panel.removeEventListener('keydown', intent); panel.removeEventListener('scroll', scroll, true)
      document.removeEventListener('visibilitychange', measure); window.removeEventListener('resize', measure)
      queueMicrotask(() => {
        if (!panel.isConnected && visit.current) {
          captureProductEvent('list_closed', { list_view_id: visit.current.id, list_type: visit.current.type })
          visit.current = null
        }
      })
    }
  }, [visible, type, key])
  return useCallback((source: string) => { entry.current = source }, [])
}

export function listRequestProperties(type: 'region_complex' | 'announcement') {
  return { list_type: type, list_view_id: document.getElementById('housing-list-page')?.dataset.listViewId ?? 'unavailable', request_id: createAnalyticsId() }
}

function hasForegroundModal(element: HTMLElement) {
  return [...document.querySelectorAll('dialog[open][aria-modal="true"], [role="dialog"][aria-modal="true"]')].some(dialog => !dialog.contains(element))
}
