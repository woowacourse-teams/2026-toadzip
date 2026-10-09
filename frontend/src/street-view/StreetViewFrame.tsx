import { useEffect, useRef } from 'react'
import { createStreetViewInitMessage, parseStreetViewChildMessage, type StreetViewMarkerStatus } from './protocol'
import type { createStreetViewAttempt } from './events'
import type { EnabledStreetViewConfiguration, StreetViewFailureReason } from './types'

export interface StreetViewSession {
  readonly configuration: EnabledStreetViewConfiguration
  readonly attempt: ReturnType<typeof createStreetViewAttempt>
  readonly startedAt: number
  readonly markerLabel: string
  sdkStartedAt: number | null
}

interface Props {
  readonly session: StreetViewSession
  readonly ready: boolean
  readonly onReady: (aligned: boolean) => void
  readonly onLocation: (photodate: string | null) => void
  readonly onMarkerStatus: (status: StreetViewMarkerStatus) => void
  readonly onFailure: (reason: StreetViewFailureReason) => void
  readonly onClose: () => void
}

export function StreetViewFrame({ session, ready, onReady, onLocation, onMarkerStatus, onFailure, onClose }: Props) {
  const frame = useRef<HTMLIFrameElement>(null)
  const callbacks = useRef({ onReady, onLocation, onMarkerStatus, onFailure, onClose })
  callbacks.current = { onReady, onLocation, onMarkerStatus, onFailure, onClose }

  useEffect(() => {
    const element = frame.current
    if (!element) return
    let sdkTimeout: number | undefined
    let totalTimeout: number | undefined
    let disposed = false

    function clearDeadlines() {
      window.clearTimeout(sdkTimeout)
      window.clearTimeout(totalTimeout)
    }

    function fail(reason: StreetViewFailureReason) {
      if (disposed) return
      clearDeadlines()
      session.attempt.fail(reason)
      callbacks.current.onFailure(reason)
    }

    function scheduleSdkDeadline() {
      if (session.sdkStartedAt === null || session.attempt.phase !== 'SDK' || session.attempt.terminal) return
      window.clearTimeout(sdkTimeout)
      sdkTimeout = window.setTimeout(() => fail('INITIALIZATION_TIMEOUT'),
        Math.max(0, session.sdkStartedAt + 15_000 - performance.now()))
    }

    function receive(event: MessageEvent<unknown>) {
      if (disposed || event.origin !== window.location.origin || event.source !== element?.contentWindow) return
      const message = parseStreetViewChildMessage(event.data)
      if (!message) return
      if (message.type === 'BOOT_READY') {
        element?.contentWindow?.postMessage(
          createStreetViewInitMessage(session.attempt.id, session.configuration.initialization, session.markerLabel),
          window.location.origin,
        )
        return
      }
      if (message.attemptId !== session.attempt.id) return
      switch (message.type) {
        case 'PHASE':
          session.attempt.setPhase(message.phase)
          if (session.attempt.phase === 'SDK') {
            session.sdkStartedAt ??= performance.now()
            scheduleSdkDeadline()
          } else window.clearTimeout(sdkTimeout)
          break
        case 'READY':
          if (session.attempt.terminal) return
          clearDeadlines()
          session.attempt.ready()
          callbacks.current.onReady(message.aligned)
          break
        case 'LOCATION':
          callbacks.current.onLocation(message.photodate)
          break
        case 'MARKER_STATUS':
          callbacks.current.onMarkerStatus(message.status)
          break
        case 'FAILED':
          fail(message.reasonCode)
          break
        case 'CLOSE_REQUEST':
          callbacks.current.onClose()
          break
      }
    }

    window.addEventListener('message', receive)
    if (!session.attempt.terminal) {
      totalTimeout = window.setTimeout(() => fail(session.attempt.phase === 'DOCUMENT'
        ? 'DOCUMENT_TIMEOUT' : 'INITIALIZATION_TIMEOUT'),
      Math.max(0, session.startedAt + 30_000 - performance.now()))
      scheduleSdkDeadline()
    }
    // Register the receiver before navigating the iframe. StrictMode reuses this document.
    if (!element.getAttribute('src')) element.src = '/street-view.html'
    return () => {
      disposed = true
      clearDeadlines()
      window.removeEventListener('message', receive)
    }
  }, [session])

  return <iframe ref={frame} title="네이버 단지 주변 거리뷰" aria-hidden={!ready}
    tabIndex={ready ? 0 : -1} style={{ visibility: ready ? 'visible' : 'hidden' }} />
}
