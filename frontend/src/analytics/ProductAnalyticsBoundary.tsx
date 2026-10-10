import { useLayoutEffect, useRef } from 'react'
import { useLocation } from 'react-router'
import { captureProductEvent, createAnalyticsId, setProductPageActive } from './productAnalytics'
import { useAnalyticsConsent } from './useAnalyticsConsent'

export function ProductAnalyticsBoundary() {
  const { pathname } = useLocation()
  const allowed = useAnalyticsConsent()
  const visit = useRef<{ pathname: string; id: string } | null>(null)
  useLayoutEffect(() => {
    const active = ['/', '/feedback', '/notifications/cancel', '/mypage/notifications'].includes(pathname)
    setProductPageActive(active)
    if (!active || !allowed) { visit.current = null; return }
    if (visit.current?.pathname !== pathname) visit.current = { pathname, id: createAnalyticsId() }
    captureProductEvent('page_view', {}, { dedupeKey: `page:${visit.current.id}` })
    return () => setProductPageActive(false)
  }, [pathname, allowed])
  return null
}
