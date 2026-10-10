import { captureProductEvent, createAnalyticsId } from '../analytics/productAnalytics'
import { isForegroundElement } from '../public-housing/analytics/useProductDetailAnalytics'
import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState } from 'react'
import { getStreetViewConfiguration } from './api'
import { createStreetViewAttempt, type StreetViewAttempt } from './events'
import type { StreetViewSession } from './StreetViewFrame'
import type { StreetViewMarkerStatus } from './protocol'
import type { StreetViewCancelReason, StreetViewConfiguration, StreetViewFailureReason } from './types'

export type StreetViewAvailability = { readonly kind: 'loading' } | { readonly kind: 'error' }
  | { readonly kind: 'loaded'; readonly configuration: StreetViewConfiguration }

export type StreetViewPanelState =
  | { readonly kind: 'checking' }
  | { readonly kind: 'blocked'; readonly message: string }
  | { readonly kind: 'error'; readonly message: string; readonly refreshPage: boolean }
  | { readonly kind: 'viewing'; readonly session: StreetViewSession; readonly ready: boolean;
      readonly aligned: boolean; readonly photodate: string | null; readonly markerStatus: StreetViewMarkerStatus | null }

interface Target { readonly complexId: string; readonly name: string }
const LOAD_ERROR = '거리뷰를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.'

export function useStreetView({ target, mobile, supported }: {
  readonly target: Target | null
  readonly mobile: boolean
  readonly supported: boolean
}) {
  const measurement = useRef<{ id: string; complexId: string; attemptId?: string; viewed: boolean; viewState: string } | null>(null)
  const complexId = target?.complexId ?? null
  const name = target?.name ?? ''
  const [availability, setAvailability] = useState<StreetViewAvailability>({ kind: 'loading' })
  const [state, setState] = useState<StreetViewPanelState | null>(null)
  const [refreshCount, setRefreshCount] = useState(0)
  const buttonRef = useRef<HTMLButtonElement>(null)
  const panelRef = useRef<HTMLDivElement>(null)
  const panelFocusedRef = useRef(false)
  const openingRequest = useRef<AbortController | null>(null)
  const availabilityRequest = useRef<AbortController | null>(null)
  const lifetime = useRef<{ attempt: StreetViewAttempt | null; generation: number }>({ attempt: null, generation: 0 }).current
  const identity = useRef({ complexId, mobile, supported })
  const panelId = useId()
  const current = identity.current.complexId === complexId && identity.current.mobile === mobile
    && identity.current.supported === supported
  const visible = complexId !== null && !mobile && supported
  const active = current && visible && state !== null
  const available = current && visible && availability.kind === 'loaded' && availability.configuration.enabled

  const close = useCallback((reason: StreetViewCancelReason | null = 'USER_CLOSED', restoreFocus = true, interaction?: 'escape' | 'map_button' | 'mobile_transition' | 'page_exit') => {
    const measured = measurement.current
    if (measured) {
      captureProductEvent('street_view_closed', { complex_id: measured.complexId, street_view_open_id: measured.id, ...(measured.attemptId ? { attempt_id: measured.attemptId } : {}), was_viewed: measured.viewed, view_state: measured.viewState, reason: interaction ?? (reason === 'TARGET_CHANGED' ? 'target_changed' : restoreFocus ? 'user_closed' : 'navigation') })
      measurement.current = null
    }
    const closedTarget = identity.current.complexId
    openingRequest.current?.abort()
    if (reason !== null) lifetime.attempt?.cancel(reason)
    lifetime.attempt = null
    panelFocusedRef.current = false
    setState(null)
    if (!restoreFocus) return
    queueMicrotask(() => {
      if (identity.current.complexId === closedTarget && !identity.current.mobile
        && buttonRef.current?.isConnected) buttonRef.current.focus({ preventScroll: true })
    })
  }, [lifetime])

  useLayoutEffect(() => {
    if (current) return
    const restoreDetailFocus = mobile && panelFocusedRef.current
    close(identity.current.complexId !== complexId ? 'TARGET_CHANGED' : null, false, mobile ? 'mobile_transition' : undefined)
    availabilityRequest.current?.abort()
    identity.current = { complexId, mobile, supported }
    setAvailability({ kind: 'loading' })
    if (restoreDetailFocus && complexId !== null) {
      // Wait until the detail's native dialog has completed its mobile autofocus steps.
      queueMicrotask(() => {
        if (identity.current.complexId !== complexId || !identity.current.mobile || lifetime.attempt !== null) return
        const focused = document.activeElement
        const foreground = focused instanceof HTMLElement ? focused.closest('dialog[open]') : null
        const heading = document.getElementById(`complex-detail-title-${complexId}`)
        if (foreground && !foreground.contains(heading)) return
        heading?.focus({ preventScroll: true })
      })
    }
  }, [close, complexId, current, lifetime, mobile, supported])

  useEffect(() => {
    const generation = ++lifetime.generation
    const leavePage = () => {
      close(null, false, 'page_exit')
      availabilityRequest.current?.abort()
    }
    window.addEventListener('pagehide', leavePage)
    return () => {
      window.removeEventListener('pagehide', leavePage)
      openingRequest.current?.abort()
      availabilityRequest.current?.abort()
      // StrictMode's same-instance re-registration is not a target change.
      queueMicrotask(() => {
        if (lifetime.generation === generation) lifetime.attempt?.cancel('TARGET_CHANGED')
      })
    }
  }, [close, lifetime])

  useEffect(() => {
    if (!visible || complexId === null) return
    const controller = new AbortController()
    availabilityRequest.current = controller
    setAvailability({ kind: 'loading' })
    void getStreetViewConfiguration(Number(complexId), controller.signal).then(configuration => {
      if (!controller.signal.aborted) setAvailability({ kind: 'loaded', configuration })
    }).catch(() => {
      if (!controller.signal.aborted) setAvailability({ kind: 'error' })
    })
    return () => controller.abort()
  }, [complexId, visible, refreshCount])

  async function open() {
    if (!visible || !current || complexId === null || (!active && !available)) return
    if (active) captureProductEvent('street_view_retry_clicked', { complex_id: complexId, street_view_open_id: measurement.current?.id ?? 'unavailable' })
    measurement.current = { id: createAnalyticsId(), complexId, viewed: false, viewState: 'checking' }
    const measured = measurement.current
    captureProductEvent('street_view_open_requested', { complex_id: complexId, street_view_open_id: measured.id, entry_point: 'complex_detail' })
    openingRequest.current?.abort()
    availabilityRequest.current?.abort()
    const controller = new AbortController()
    openingRequest.current = controller
    lifetime.attempt = null
    setState({ kind: 'checking' })
    try {
      const configuration = await getStreetViewConfiguration(Number(complexId), controller.signal)
      if (controller.signal.aborted) return
      setAvailability({ kind: 'loaded', configuration })
      if (!configuration.enabled) {
        measured.viewState = 'blocked'
        captureProductEvent('street_view_blocked', { complex_id: complexId, street_view_open_id: measured.id, reason: configuration.disabledReason })
        setState({ kind: 'blocked', message: disabledDescription(configuration.disabledReason) })
        return
      }
      const nextAttempt = createStreetViewAttempt(configuration)
      lifetime.attempt = nextAttempt
      measured.attemptId = nextAttempt.id
      measured.viewState = 'loading'
      nextAttempt.start()
      setState({ kind: 'viewing', ready: false, aligned: false, photodate: null, markerStatus: null,
        session: { configuration, attempt: nextAttempt, startedAt: performance.now(), sdkStartedAt: null,
          markerLabel: `${name} · 출입구` } })
    } catch {
      if (!controller.signal.aborted) {
        measured.viewState = 'failed'
        captureProductEvent('street_view_failed', { complex_id: complexId, street_view_open_id: measured.id, reason: 'CONFIGURATION_REQUEST_FAILED' })
        setAvailability({ kind: 'error' })
        setState({ kind: 'error', message: LOAD_ERROR, refreshPage: false })
      }
    }
  }

  useEffect(() => {
    if (!active || state?.kind !== 'viewing' || !state.ready) return
    const measured = measurement.current
    if (!measured) return
    measured.viewState = 'ready'
    const measure = () => {
      const panel = panelRef.current
      if (measured.viewed || measurement.current !== measured || !panel || !isForegroundElement(panel)) return
      measured.viewed = captureProductEvent('street_view_viewed', { complex_id: measured.complexId, street_view_open_id: measured.id, attempt_id: state.session.attempt.id, aligned: state.aligned }, { dedupeKey: `street-view:${measured.id}` })
    }
    const observer = new MutationObserver(measure)
    observer.observe(document.body, { subtree: true, childList: true, attributes: true, attributeFilter: ['open', 'hidden', 'inert', 'aria-hidden', 'style', 'class'] })
    document.addEventListener('visibilitychange', measure)
    window.addEventListener('resize', measure)
    measure()
    return () => { observer.disconnect(); document.removeEventListener('visibilitychange', measure); window.removeEventListener('resize', measure) }
  }, [active, state])

  return {
    active, state: active ? state : null, available, availability, visible,
    description: availabilityDescription(availability), buttonRef, panelRef, panelFocusedRef, panelId,
    open, close, refresh: () => setRefreshCount(value => value + 1),
    onReady: (aligned: boolean) => setState(value => value?.kind === 'viewing' ? { ...value, ready: true, aligned } : value),
    onLocation: (photodate: string | null) => setState(value => value?.kind === 'viewing' ? { ...value, photodate } : value),
    onMarkerStatus: (markerStatus: StreetViewMarkerStatus) => {
      const measured = measurement.current
      if (measured) captureProductEvent('street_view_marker_status', { complex_id: measured.complexId, street_view_open_id: measured.id, attempt_id: measured.attemptId, marker_status: markerStatus }, { dedupeKey: `street-marker:${measured.id}:${markerStatus}` })
      setState(value => value?.kind === 'viewing' ? { ...value, markerStatus } : value)
    },
    onFailure: (reason: StreetViewFailureReason) => {
      const measured = measurement.current
      if (measured) { measured.viewState = 'failed'; captureProductEvent('street_view_failed', { complex_id: measured.complexId, street_view_open_id: measured.id, attempt_id: measured.attemptId, reason }, { dedupeKey: `street-failure:${measured.id}` }) }
      setState({ kind: 'error', message: LOAD_ERROR, refreshPage: reason === 'DOCUMENT_TIMEOUT' })
    },
  }
}

export type StreetViewController = ReturnType<typeof useStreetView>

function availabilityDescription(state: StreetViewAvailability): string {
  if (state.kind === 'loading') return '거리뷰 이용 가능 여부를 확인하고 있어요.'
  if (state.kind === 'error') return '거리뷰 이용 여부를 확인하지 못했어요. 다시 확인해 주세요.'
  if (!state.configuration.enabled) return disabledDescription(state.configuration.disabledReason)
  return ''
}

function disabledDescription(reason: 'POLICY_DISABLED' | 'INVALID_COORDINATES'): string {
  if (reason === 'POLICY_DISABLED') return '현재 거리뷰 제공이 중단되어 있어요.'
  return '이 단지의 위치정보를 확인할 수 없어 거리뷰를 제공하지 못해요.'
}
