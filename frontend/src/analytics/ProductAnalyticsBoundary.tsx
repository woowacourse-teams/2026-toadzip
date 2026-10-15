import { useLayoutEffect, useRef } from 'react'
import { useLocation } from 'react-router'
import { consentStore } from '../privacy/consentStore'
import { captureProductEvent, captureWebPageView, createAnalyticsId, setProductPageActive } from './productAnalytics'
import { useAnalyticsConsent } from './useAnalyticsConsent'

export function ProductAnalyticsBoundary() {
  const { pathname } = useLocation()
  const allowed = useAnalyticsConsent()
  const visit = useRef<{ pathname: string; id: string } | null>(null)
  const webVisit = useRef<{ pathname: string; id: string; captured: boolean } | null>(null)
  useLayoutEffect(() => consentStore.onStop(reason => {
    if (reason !== 'pause') webVisit.current = null
  }), [])
  useLayoutEffect(() => {
    const active = ['/explore', '/feedback', '/mypage/notifications'].includes(pathname)
    setProductPageActive(active)
    if (!active || webVisit.current?.pathname !== pathname) webVisit.current = null
    if (!active || !allowed) { visit.current = null; return }
    if (visit.current?.pathname !== pathname) visit.current = { pathname, id: createAnalyticsId() }
    captureProductEvent('page_view', {}, { dedupeKey: `page:${visit.current.id}` })
    // Focus/visibility rechecks pause consent without creating a new web visit.
    // Keep this even if the bounded shared deduplication cache evicts the key.
    webVisit.current ??= { pathname, id: createAnalyticsId(), captured: false }
    if (!webVisit.current.captured) webVisit.current.captured = captureWebPageView(webVisit.current.id)
    return () => setProductPageActive(false)
  }, [pathname, allowed])
  return null
}
