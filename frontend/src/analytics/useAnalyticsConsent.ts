import { useSyncExternalStore } from 'react'
import { analyticsCollectionAllowed, consentStore } from '../privacy/consentStore'

export function useAnalyticsConsent() {
  return useSyncExternalStore(consentStore.subscribe, analyticsCollectionAllowed, () => false)
}
