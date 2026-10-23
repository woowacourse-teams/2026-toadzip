import { useEffect, useState } from 'react'

// Keep public CSS mobile queries in sync with these two alternatives.
const MOBILE_QUERIES = ['(max-width: 767px)', '(hover: none) and (pointer: coarse)'] as const

export function isMobileViewport() {
  return MOBILE_QUERIES.some(query => window.matchMedia?.(query).matches ?? false)
}

export function useMobileViewport() {
  const [mobile, setMobile] = useState(isMobileViewport)
  useEffect(() => {
    if (!window.matchMedia) return
    const media = MOBILE_QUERIES.map(query => window.matchMedia(query))
    const update = () => setMobile(media.some(query => query.matches))
    update()
    media.forEach(query => query.addEventListener?.('change', update))
    return () => media.forEach(query => query.removeEventListener?.('change', update))
  }, [])
  return mobile
}
