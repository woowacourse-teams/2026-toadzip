import { useEffect, useLayoutEffect, useRef, type RefObject } from 'react'

interface ScrollPosition {
  complexes: number
  announcements: number
  pageX: number
  pageY: number
}

/** History entries keep independent scroll positions, even for the same search. */
export function useSearchHistoryScroll(
  key: string,
  restore: boolean,
  ready: boolean,
  complexes: RefObject<HTMLDivElement | null>,
  announcements: RefObject<HTMLDivElement | null>,
) {
  const cache = useRef(new Map<string, ScrollPosition>())
  const pending = useRef<ScrollPosition | null>(null)
  useEffect(() => {
    const previous = window.history.scrollRestoration
    window.history.scrollRestoration = 'manual'
    return () => { window.history.scrollRestoration = previous }
  }, [])
  useLayoutEffect(() => {
    const entries = cache.current
    const complexList = complexes.current
    const announcementList = announcements.current
    pending.current = restore ? entries.get(key) ?? null : null
    return () => {
      entries.set(key, {
        complexes: complexList?.scrollTop ?? 0,
        announcements: announcementList?.scrollTop ?? 0,
        pageX: window.scrollX, pageY: window.scrollY,
      })
      if (entries.size > 60) {
        const first = entries.keys().next().value
        if (first !== undefined) entries.delete(first)
      }
    }
  }, [key, restore, complexes, announcements])
  useLayoutEffect(() => {
    if (!ready || !pending.current) return
    const position = pending.current
    pending.current = null
    if (complexes.current) complexes.current.scrollTop = position.complexes
    if (announcements.current) announcements.current.scrollTop = position.announcements
    window.scrollTo(position.pageX, position.pageY)
  }, [key, ready, complexes, announcements])
}
