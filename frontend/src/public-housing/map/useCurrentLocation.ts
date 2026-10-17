import { useCallback, useEffect, useRef, useState } from 'react'
import type { MapCenter } from '../navigation/mapLocation.ts'

type CurrentLocationErrorReason = 'permission-denied' | 'unavailable' | 'failed'

export type CurrentLocationState =
  | { readonly kind: 'idle' | 'loading' | 'located' }
  | { readonly kind: 'error'; readonly reason: CurrentLocationErrorReason; readonly message: string }

const UNAVAILABLE_MESSAGE = '이 환경에서는 위치를 사용할 수 없어요. 지역을 검색해 주세요.'
const FAILED_MESSAGE = '현재 위치를 확인하지 못했어요. 다시 시도하거나 지역을 검색해 주세요.'
const DENIED_MESSAGE = '위치 사용이 차단되어 있어요. 브라우저 설정에서 허용하거나 지역을 검색해 주세요.'

export function useCurrentLocation(
  autoEnabled: boolean,
  onLocated: (center: MapCenter) => void,
) {
  const [state, setState] = useState<CurrentLocationState>({ kind: 'idle' })
  const [position, setPosition] = useState<MapCenter | null>(null)
  const onLocatedRef = useRef(onLocated)
  onLocatedRef.current = onLocated
  const automaticStarted = useRef(false)
  const requestRevision = useRef(0)
  const pending = useRef(false)

  const cancel = useCallback(() => {
    automaticStarted.current = true
    requestRevision.current += 1
    pending.current = false
    setState(current => current.kind === 'loading' ? { kind: 'idle' } : current)
  }, [])

  const locate = useCallback(() => {
    automaticStarted.current = true
    if (pending.current) return
    const revision = ++requestRevision.current
    const fail = (reason: CurrentLocationErrorReason, message: string) => {
      if (revision !== requestRevision.current) return
      pending.current = false
      if (reason === 'permission-denied') setPosition(null)
      setState({ kind: 'error', reason, message })
    }
    if (!navigator.geolocation || window.isSecureContext === false) {
      fail('unavailable', UNAVAILABLE_MESSAGE)
      return
    }
    pending.current = true
    setState({ kind: 'loading' })
    try {
      navigator.geolocation.getCurrentPosition(position => {
        if (revision !== requestRevision.current) return
        const { latitude, longitude } = position.coords
        if (!Number.isFinite(latitude) || latitude < -90 || latitude > 90
          || !Number.isFinite(longitude) || longitude < -180 || longitude > 180) {
          fail('failed', FAILED_MESSAGE)
          return
        }
        pending.current = false
        setPosition({ latitude, longitude })
        setState({ kind: 'located' })
        onLocatedRef.current({ latitude, longitude })
      }, error => {
        if (error.code === 1) fail('permission-denied', DENIED_MESSAGE)
        else fail('failed', FAILED_MESSAGE)
      }, { enableHighAccuracy: false, maximumAge: 0, timeout: 8_000 })
    } catch {
      fail('failed', FAILED_MESSAGE)
    }
  }, [])

  useEffect(() => {
    if (!autoEnabled || automaticStarted.current) return
    let cancelled = false
    // Defer until the welcome dialog has closed; StrictMode's discarded effect
    // must not trigger a second browser permission request.
    queueMicrotask(() => {
      if (!cancelled && !automaticStarted.current) locate()
    })
    return () => { cancelled = true }
  }, [autoEnabled, locate])

  useEffect(() => () => {
    // getCurrentPosition has no abort API. Ignore callbacks after leaving.
    requestRevision.current += 1
    pending.current = false
  }, [])

  return { cancel, locate, position, state }
}
