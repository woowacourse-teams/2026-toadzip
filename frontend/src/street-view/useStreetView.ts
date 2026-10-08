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

  const close = useCallback((reason: StreetViewCancelReason | null = 'USER_CLOSED', restoreFocus = true) => {
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
    close(identity.current.complexId !== complexId ? 'TARGET_CHANGED' : null, false)
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
      close(null, false)
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
        setState({ kind: 'blocked', message: disabledDescription(configuration.disabledReason) })
        return
      }
      const nextAttempt = createStreetViewAttempt(configuration)
      lifetime.attempt = nextAttempt
      nextAttempt.start()
      setState({ kind: 'viewing', ready: false, aligned: false, photodate: null, markerStatus: null,
        session: { configuration, attempt: nextAttempt, startedAt: performance.now(), sdkStartedAt: null,
          markerLabel: `${name} · 출입구` } })
    } catch {
      if (!controller.signal.aborted) {
        setAvailability({ kind: 'error' })
        setState({ kind: 'error', message: LOAD_ERROR, refreshPage: false })
      }
    }
  }

  return {
    active, state: active ? state : null, available, availability, visible,
    description: availabilityDescription(availability), buttonRef, panelRef, panelFocusedRef, panelId,
    open, close, refresh: () => setRefreshCount(value => value + 1),
    onReady: (aligned: boolean) => setState(value => value?.kind === 'viewing' ? { ...value, ready: true, aligned } : value),
    onLocation: (photodate: string | null) => setState(value => value?.kind === 'viewing' ? { ...value, photodate } : value),
    onMarkerStatus: (markerStatus: StreetViewMarkerStatus) => setState(value => value?.kind === 'viewing' ? { ...value, markerStatus } : value),
    onFailure: (reason: StreetViewFailureReason) => setState({ kind: 'error', message: LOAD_ERROR, refreshPage: reason === 'DOCUMENT_TIMEOUT' }),
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
