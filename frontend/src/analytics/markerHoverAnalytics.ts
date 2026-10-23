import { useEffect, useState } from 'react'
import { consentStore } from '../privacy/consentStore'
import { captureProductEvent, createAnalyticsId, prepareMarkerHoverCompletion,
  type PreparedMarkerHoverCompletion } from './productAnalytics'

export const MARKER_HOVER_MEASUREMENT_VERSION = 'marker_hover_v1'
export interface MarkerHoverAttribution {
  readonly exploration_id: string
  readonly hover_id: string
  readonly complex_id: string
  readonly measurement_version: typeof MARKER_HOVER_MEASUREMENT_VERSION
}
export type MarkerHoverEndReason = 'pointer_leave' | 'window_blur' | 'document_hidden'
  | 'page_hide' | 'route_leave' | 'marker_removed' | 'marker_occluded' | 'collection_paused'

export interface MarkerHoverAnalytics {
  readonly connect: () => () => void
  readonly subscribe: (listener: () => void) => () => void
  readonly available: (visible?: () => boolean) => boolean
  readonly begin: (complexId: string) => string | null
  readonly end: (hoverId: string, reason: MarkerHoverEndReason) => void
  readonly attribution: (complexId: string) => MarkerHoverAttribution | undefined
}

/** One explorer visit, independent of camera, filters, marker reconciliation and detail requests. */
export function createMarkerHoverAnalytics(): MarkerHoverAnalytics {
  let explorationId = createAnalyticsId()
  let exposed = false
  let active: { attribution: MarkerHoverAttribution; started: number; startedAt: string;
    completion: PreparedMarkerHoverCompletion } | null = null
  const listeners = new Set<() => void>()
  const notify = () => listeners.forEach(listener => listener())

  function available(visible: () => boolean = () => true): boolean {
    if (!prepareMarkerHoverCompletion()) return false
    if (!exposed && !visible()) return false
    if (!exposed) exposed = captureProductEvent('map_marker_hover_available', {
      exploration_id: explorationId, measurement_version: MARKER_HOVER_MEASUREMENT_VERSION,
    }, { dedupeKey: `marker-hover-available:${explorationId}`, pageName: 'explorer' })
    return exposed
  }

  function end(hoverId: string, reason: MarkerHoverEndReason): void {
    if (active?.attribution.hover_id !== hoverId) return
    const finished = active
    active = null
    finished.completion.complete({ ...finished.attribution, hover_started_at: finished.startedAt,
      duration_ms: Math.max(0, performance.now() - finished.started), reason, occurred_at: new Date().toISOString() })
  }

  return {
    available,
    begin(complexId) {
      if (active?.attribution.complex_id === complexId && active.completion.isCurrent()) return active.attribution.hover_id
      if (active) end(active.attribution.hover_id, 'pointer_leave')
      const completion = prepareMarkerHoverCompletion()
      if (!completion || !available()) return null
      const attribution = Object.freeze({ exploration_id: explorationId, hover_id: createAnalyticsId(),
        complex_id: complexId, measurement_version: MARKER_HOVER_MEASUREMENT_VERSION })
      active = { attribution, completion, started: performance.now(), startedAt: new Date().toISOString() }
      return attribution.hover_id
    },
    end,
    attribution(complexId) {
      return active?.attribution.complex_id === complexId && active.completion.isCurrent() ? active.attribution : undefined
    },
    subscribe(listener) { listeners.add(listener); return () => { listeners.delete(listener) } },
    connect() {
      const stop = consentStore.onStop(reason => {
        if (reason === 'pause') {
          if (active) end(active.attribution.hover_id, document.visibilityState !== 'visible'
            ? 'document_hidden' : !document.hasFocus() ? 'window_blur' : 'collection_paused')
        } else {
          active = null
          exposed = false
          explorationId = createAnalyticsId()
        }
        notify()
      })
      const unsubscribe = consentStore.subscribe(notify)
      return () => {
        stop()
        unsubscribe()
        if (active) end(active.attribution.hover_id, 'route_leave')
      }
    },
  }
}

export function useMarkerHoverAnalytics(): MarkerHoverAnalytics {
  const [analytics] = useState(createMarkerHoverAnalytics)
  useEffect(() => analytics.connect(), [analytics])
  return analytics
}
