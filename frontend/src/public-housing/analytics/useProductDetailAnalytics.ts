import { useCallback, useEffect, useRef } from 'react'
import { useNavigationType } from 'react-router'
import { captureProductEvent, createAnalyticsId } from '../../analytics/productAnalytics'
import type { DetailEntryPoint } from '../../analytics/googleAnalytics'
import type { DetailLocationResult } from '../navigation/detailLocation'

interface Visit { key: string | null; id: string; entry: DetailEntryPoint; sent: boolean }

/** Data loaded behind another dialog is not an active visit until the user can see it. */
export function useProductDetailAnalytics({ detailLocation, readyComplexId, readyAnnouncementId }: {
  detailLocation: DetailLocationResult
  readyComplexId: string | null
  readyAnnouncementId: string | null
}) {
  const navigationType = useNavigationType()
  const kind = detailLocation.kind
  const id = kind === 'complex' ? detailLocation.complexId
    : kind === 'announcement' ? detailLocation.announcementId : null
  const key = id === null ? null : `${kind}:${id}`
  const visit = useRef<Visit | null>(null)
  const pending = useRef<{ key: string; entry: DetailEntryPoint; id: string } | null>(null)

  useEffect(() => {
    if (visit.current === null || visit.current.key !== key) {
      const prepared = pending.current?.key === key ? pending.current : null
      visit.current = {
        key, id: prepared?.id ?? createAnalyticsId(),
        entry: prepared?.entry ?? (visit.current !== null && navigationType === 'POP' ? 'history' : 'direct'),
        sent: false,
      }
    }
    pending.current = null
    const current = visit.current
    const ready = kind === 'complex' ? readyComplexId === id
      : kind === 'announcement' && readyAnnouncementId === id
    let cancelled = false
    function measure() {
      if (cancelled || id === null) return
      const dialog = document.querySelector<HTMLElement>(kind === 'complex'
        ? 'dialog.housing-detail-layer[aria-label="단지 상세"]'
        : 'dialog.housing-detail-layer[aria-label="공고 상세"]')
      if (dialog) dialog.dataset.detailVisitId = current.id
      if (!ready || current.sent || !dialog || !isForegroundElement(dialog)) return
      current.sent = captureProductEvent(kind === 'complex' ? 'view_complex' : 'view_announcement', {
        [kind === 'complex' ? 'complex_id' : 'announcement_id']: id,
        detail_visit_id: current.id, entry_point: current.entry,
      }, { dedupeKey: `detail:${current.id}` })
    }
    const observer = new MutationObserver(measure)
    observer.observe(document.body, { attributes: true, childList: true, subtree: true,
      attributeFilter: ['open', 'hidden', 'inert', 'aria-hidden', 'class', 'style'] })
    document.addEventListener('visibilitychange', measure)
    window.addEventListener('resize', measure)
    queueMicrotask(measure)
    return () => {
      cancelled = true
      observer.disconnect()
      document.removeEventListener('visibilitychange', measure)
      window.removeEventListener('resize', measure)
    }
  }, [id, key, kind, navigationType, readyAnnouncementId, readyComplexId])

  const prepare = useCallback((detailKind: 'complex' | 'announcement', detailId: string, entry: DetailEntryPoint) => {
    const target = `${detailKind}:${detailId}`
    const visitId = target === key ? visit.current?.id ?? createAnalyticsId() : createAnalyticsId()
    pending.current = target === key ? null : { key: target, entry, id: visitId }
    captureProductEvent('detail_open_requested', {
      target_type: detailKind, [detailKind === 'complex' ? 'complex_id' : 'announcement_id']: detailId,
      detail_visit_id: visitId, entry_point: entry,
    })
  }, [key])
  const action = useCallback((name: 'detail_closed' | 'detail_back_used') => {
    if (id === null || !visit.current) return
    captureProductEvent(name, {
      target_type: kind, [kind === 'complex' ? 'complex_id' : 'announcement_id']: id,
      detail_visit_id: visit.current.id,
    })
  }, [id, kind])
  return { prepare, action }
}

export function isForegroundElement(element: HTMLElement): boolean {
  if (document.visibilityState !== 'visible' || element.closest('[hidden], [inert], [aria-hidden="true"]')) return false
  if (element instanceof HTMLDialogElement && !element.open) return false
  if ([...document.querySelectorAll('dialog[open], [role="dialog"][aria-modal="true"]')].some(dialog => dialog !== element
    && !dialog.classList.contains('housing-detail-layer') && !element.contains(dialog))) return false
  const rectangle = element.getBoundingClientRect()
  const style = getComputedStyle(element)
  return style.display !== 'none' && style.visibility !== 'hidden' && rectangle.width > 0 && rectangle.height > 0
    && rectangle.bottom > 0 && rectangle.right > 0 && rectangle.top < window.innerHeight && rectangle.left < window.innerWidth
}
