import { useEffect, useMemo } from 'react'
import { captureProductEvent } from '../../analytics/productAnalytics'

export type DocumentViewerAction = 'search_open' | 'search_close' | 'search' | 'previous_result' | 'next_result' | 'outline' | 'zoom' | 'retry'
export interface DocumentPreviewTelemetry {
  requested(): void
  rendered(element: HTMLElement): void
  failed(reason: 'request_failed' | 'render_failed' | 'unsupported' | 'unavailable' | 'viewer_failed'): void
  action(action: DocumentViewerAction): void
}

// A loaded document is not a viewed document: wait for an error-free rendered
// page in the foreground viewport, and discard callbacks when selection changes.
export function useDocumentPreviewTelemetry(announcementId: string, attachmentId: string, documentOpenId: string, attempt: number) {
  const lifecycle = useMemo(() => {
    const properties = { announcement_id: announcementId, attachment_id: attachmentId,
      document_open_id: documentOpenId, preview_attempt: attempt }
    const key = `${documentOpenId}:${attempt}`
    let succeeded = false
    let failed = false
    let active = true
    const candidates = new Map<HTMLElement, boolean>()
    let observer: IntersectionObserver | undefined
    let mutations: MutationObserver | undefined
    const check = () => {
      if (!active || failed || succeeded || document.visibilityState !== 'visible') return
      const modals = Array.from(document.querySelectorAll<HTMLDialogElement>('dialog[open]'))
      for (const [element, intersecting] of candidates) {
        if (!intersecting || !element.isConnected || element.closest('[hidden], [inert]')) continue
        if (modals.some((modal) => !modal.contains(element) && !modal.classList.contains('housing-detail-layer'))) continue
        succeeded = true
        captureProductEvent('document_preview_succeeded', properties, { dedupeKey: `${key}:succeeded` })
        observer?.disconnect()
        mutations?.disconnect()
        return
      }
    }
    const telemetry: DocumentPreviewTelemetry = {
      requested() { captureProductEvent('document_preview_requested', properties, { dedupeKey: `${key}:requested` }) },
      rendered(element) {
        if (!active || failed || succeeded || candidates.has(element) || typeof IntersectionObserver === 'undefined') return
        if (!observer) {
          observer = new IntersectionObserver((entries) => {
            for (const entry of entries) if (entry.target instanceof HTMLElement) candidates.set(entry.target, entry.isIntersecting)
            check()
          })
          mutations = new MutationObserver(check)
          mutations.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['open', 'hidden', 'inert'] })
        }
        candidates.set(element, false)
        observer.observe(element)
      },
      failed(reason) {
        if (!active || failed) return
        failed = true
        captureProductEvent('document_preview_failed', { ...properties, failure_reason: reason }, { dedupeKey: `${key}:failed` })
      },
      action(action) { if (active) captureProductEvent('document_viewer_action', { ...properties, action }) },
    }
    return { telemetry, connect() { active = true; document.addEventListener('visibilitychange', check) },
      disconnect() { active = false; observer?.disconnect(); mutations?.disconnect(); candidates.clear(); document.removeEventListener('visibilitychange', check) } }
  }, [announcementId, attachmentId, documentOpenId, attempt])
  useEffect(() => { lifecycle.connect(); return () => lifecycle.disconnect() }, [lifecycle])
  return lifecycle.telemetry
}
