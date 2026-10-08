import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react'
import { getStreetViewConfiguration } from './api'
import { createStreetViewAttempt, type StreetViewAttempt } from './events'
import { StreetViewDialog, type StreetViewDialogState } from './StreetViewDialog'
import { useMobileViewport } from '../public-housing/components/useMobileViewport'
import type { StreetViewConfiguration } from './types'
import styles from './StreetView.module.css'

type Availability = { readonly kind: 'loading' } | { readonly kind: 'error' }
  | { readonly kind: 'loaded'; readonly configuration: StreetViewConfiguration }
const LOAD_ERROR = '거리뷰를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.'

export function StreetViewEntry({ complexId, name, address }: {
  readonly complexId: string
  readonly name: string
  readonly address: string
}) {
  const mobile = useMobileViewport()
  const [availability, setAvailability] = useState<Availability>({ kind: 'loading' })
  const [dialog, setDialog] = useState<StreetViewDialogState | null>(null)
  const [refresh, setRefresh] = useState(0)
  const [hovered, setHovered] = useState(false)
  const [focused, setFocused] = useState(false)
  const [tooltip, setTooltip] = useState(false)
  const [dismissed, setDismissed] = useState(false)
  const button = useRef<HTMLButtonElement>(null)
  const openingRequest = useRef<AbortController | null>(null)
  const lifetime = useRef<{ attempt: StreetViewAttempt | null; generation: number }>({ attempt: null, generation: 0 }).current
  const identity = useRef({ complexId, mobile })
  const descriptionId = useId()
  const current = identity.current.complexId === complexId && identity.current.mobile === mobile
  const description = availabilityDescription(availability)
  const available = current && availability.kind === 'loaded' && availability.configuration.enabled

  useLayoutEffect(() => {
    if (identity.current.complexId === complexId && identity.current.mobile === mobile) return
    if (identity.current.complexId !== complexId) lifetime.attempt?.cancel('TARGET_CHANGED')
    // A width change has no BE cancellation code. Dispose without inventing a result.
    lifetime.attempt = null
    openingRequest.current?.abort()
    identity.current = { complexId, mobile }
    setDialog(null)
    setAvailability({ kind: 'loading' })
    setTooltip(false)
    setHovered(false)
    setFocused(false)
    if (mobile) document.getElementById(`complex-detail-title-${complexId}`)?.focus({ preventScroll: true })
  }, [complexId, mobile, lifetime])

  useEffect(() => {
    const generation = ++lifetime.generation
    const leavePage = () => {
      lifetime.attempt = null
      openingRequest.current?.abort()
      setDialog(null)
    }
    window.addEventListener('pagehide', leavePage)
    return () => {
      window.removeEventListener('pagehide', leavePage)
      openingRequest.current?.abort()
      // StrictMode's same-instance re-registration is not a target change.
      queueMicrotask(() => {
        if (lifetime.generation === generation) lifetime.attempt?.cancel('TARGET_CHANGED')
      })
    }
  }, [lifetime])

  useEffect(() => {
    if (mobile) return
    const controller = new AbortController()
    setAvailability({ kind: 'loading' })
    void getStreetViewConfiguration(Number(complexId), controller.signal).then(configuration => {
      if (!controller.signal.aborted) setAvailability({ kind: 'loaded', configuration })
    }).catch(() => {
      if (!controller.signal.aborted) setAvailability({ kind: 'error' })
    })
    return () => controller.abort()
  }, [complexId, mobile, refresh])

  useEffect(() => {
    if ((!hovered && !focused) || available || dismissed || mobile) {
      setTooltip(false)
      return
    }
    const timeout = window.setTimeout(() => setTooltip(true), 600)
    return () => window.clearTimeout(timeout)
  }, [hovered, focused, available, dismissed, mobile])

  useEffect(() => {
    if (!tooltip) return
    const dismiss = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      event.preventDefault()
      event.stopPropagation()
      setDismissed(true)
      setTooltip(false)
    }
    document.addEventListener('keydown', dismiss, true)
    return () => document.removeEventListener('keydown', dismiss, true)
  }, [tooltip])

  async function open() {
    if (mobile || !current || (dialog === null && !available)) return
    openingRequest.current?.abort()
    const controller = new AbortController()
    openingRequest.current = controller
    lifetime.attempt = null
    setTooltip(false)
    setDialog({ kind: 'checking' })
    try {
      const configuration = await getStreetViewConfiguration(Number(complexId), controller.signal)
      if (controller.signal.aborted) return
      setAvailability({ kind: 'loaded', configuration })
      if (!configuration.enabled) {
        setDialog({ kind: 'blocked', message: disabledDescription(configuration.disabledReason) })
        return
      }
      const nextAttempt = createStreetViewAttempt(configuration)
      lifetime.attempt = nextAttempt
      nextAttempt.start()
      setDialog({ kind: 'viewing', ready: false, aligned: false, photodate: null,
        session: { configuration, attempt: nextAttempt, startedAt: performance.now(), sdkStartedAt: null } })
    } catch {
      if (!controller.signal.aborted) {
        setAvailability({ kind: 'error' })
        setDialog({ kind: 'error', message: LOAD_ERROR })
      }
    }
  }

  function close() {
    openingRequest.current?.abort()
    lifetime.attempt?.cancel('USER_CLOSED')
    lifetime.attempt = null
    setDialog(null)
    setDismissed(true)
    queueMicrotask(() => {
      if (button.current?.isConnected && !identity.current.mobile) button.current.focus({ preventScroll: true })
    })
  }

  if (mobile) return null
  return <div className={styles.entry}
    onMouseEnter={() => { setHovered(true); setDismissed(false) }}
    onMouseLeave={() => setHovered(false)}>
    <button ref={button} type="button" className={styles.secondaryButton}
      aria-label={`${name} 주변 거리뷰 보기`} aria-disabled={!available}
      aria-describedby={!available ? descriptionId : undefined}
      onClick={() => { if (available && dialog === null) void open() }}
      onFocus={() => { setFocused(true); setDismissed(false) }} onBlur={() => setFocused(false)}
      onKeyDown={(event) => {
        if (!available && (event.key === 'Enter' || event.key === ' ')) event.preventDefault()
        if (event.key === 'Escape' && tooltip) {
          event.preventDefault(); event.stopPropagation(); setDismissed(true); setTooltip(false)
        }
      }}>
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" aria-hidden="true" focusable="false">
        <path d="M3 6.5 8 4l8 2.5L21 4v13.5L16 20l-8-2.5L3 20V6.5ZM8 4v13.5M16 6.5V20" />
      </svg>
      거리뷰
    </button>
    {!available && <span id={descriptionId} className={tooltip ? styles.tooltip : styles.description}
      role={tooltip ? 'tooltip' : undefined}>{description}</span>}
    {availability.kind === 'error' && <button type="button" className={styles.retry}
      onClick={() => setRefresh(value => value + 1)}>다시 확인</button>}
    {current && dialog && <StreetViewDialog name={name} address={address} state={dialog}
      onClose={close} onRetry={() => { void open() }}
      onReady={aligned => setDialog(value => value?.kind === 'viewing' ? { ...value, ready: true, aligned } : value)}
      onLocation={photodate => setDialog(value => value?.kind === 'viewing' ? { ...value, photodate } : value)}
      onFailure={() => setDialog({ kind: 'error', message: LOAD_ERROR })} />}
  </div>
}

function availabilityDescription(state: Availability): string {
  if (state.kind === 'loading') return '거리뷰 이용 가능 여부를 확인하고 있어요.'
  if (state.kind === 'error') return '거리뷰 이용 여부를 확인하지 못했어요. 다시 확인해 주세요.'
  if (!state.configuration.enabled) return disabledDescription(state.configuration.disabledReason)
  return ''
}

function disabledDescription(reason: 'POLICY_DISABLED' | 'INVALID_COORDINATES'): string {
  if (reason === 'POLICY_DISABLED') return '현재 거리뷰 제공이 중단되어 있어요.'
  return '이 단지의 위치정보를 확인할 수 없어 거리뷰를 제공하지 못해요.'
}
