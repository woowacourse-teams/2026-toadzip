import { useEffect, useState, useSyncExternalStore } from 'react'
import { privacyApi, type Notice } from './api'
import { consentStore } from './consentStore'

export function usePrivacyConsent() { return useSyncExternalStore(consentStore.subscribe, consentStore.getSnapshot) }
export function usePrivacyNotices() {
  const [notices, setNotices] = useState<Notice[]>([])
  const [error, setError] = useState(false)
  const [attempt, setAttempt] = useState(0)
  useEffect(() => {
    const refresh = () => setAttempt(value => value + 1)
    window.addEventListener('privacy-notices-refresh', refresh)
    return () => window.removeEventListener('privacy-notices-refresh', refresh)
  }, [])
  useEffect(() => {
    let active = true
    privacyApi.notices().then(value => { if (active) { setNotices(value); setError(false) } }, () => { if (active) setError(true) })
    return () => { active = false }
  }, [attempt])
  return { notices, error, retry: () => setAttempt(value => value + 1) }
}
