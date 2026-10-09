import { useRef, useState, type KeyboardEvent, type UIEvent } from 'react'
import { captureProductEvent, createAnalyticsId } from '../../analytics/productAnalytics'

export function detailVisitId(element: HTMLElement | null) {
  return element?.closest<HTMLDialogElement>('dialog.housing-detail-layer')?.dataset.detailVisitId
}

export function useDetailScrollAnalytics(targetType: 'COMPLEX' | 'ANNOUNCEMENT', targetId: string) {
  const [fallbackVisitId] = useState(createAnalyticsId)
  const intent = useRef({ targetId: '', until: 0 })
  const arm = () => { intent.current = { targetId, until: performance.now() + 1500 } }
  return {
    onWheel: arm,
    onTouchMove: arm,
    onPointerDown: arm,
    onKeyDown(event: KeyboardEvent<HTMLElement>) {
      if (['ArrowUp', 'ArrowDown', 'PageUp', 'PageDown', 'Home', 'End', ' '].includes(event.key)) arm()
    },
    onScroll(event: UIEvent<HTMLElement>) {
      const element = event.currentTarget
      if (event.target !== element || intent.current.targetId !== targetId || performance.now() > intent.current.until || document.visibilityState !== 'visible'
        || element.closest('[inert], [hidden]')) return
      if (Array.from(document.querySelectorAll<HTMLDialogElement>('dialog[open]')).some((dialog) => !dialog.contains(element))) return
      const visit = detailVisitId(element) ?? `${fallbackVisitId}:${targetType}:${targetId}`
      captureProductEvent('detail_scrolled', { target_type: targetType, target_id: targetId, detail_visit_id: visit },
        { dedupeKey: `detail-scrolled:${visit}` })
    },
  }
}
