import { useEffect } from 'react'
import type { RefObject } from 'react'

export function useDocumentScrollActivity(ref: RefObject<HTMLElement | null>) {
  useEffect(() => {
    const element = ref.current
    if (!element) return

    let idleTimer: ReturnType<typeof setTimeout> | undefined
    element.dataset.scrolling = 'false'

    const handleScroll = () => {
      element.dataset.scrolling = 'true'
      if (idleTimer !== undefined) clearTimeout(idleTimer)
      idleTimer = setTimeout(() => {
        element.dataset.scrolling = 'false'
        idleTimer = undefined
      }, 900)
    }

    element.addEventListener('scroll', handleScroll, { passive: true })
    return () => {
      element.removeEventListener('scroll', handleScroll)
      if (idleTimer !== undefined) clearTimeout(idleTimer)
      delete element.dataset.scrolling
    }
  }, [ref])
}
