import { useLayoutEffect, useRef, type ReactNode } from 'react'

export function HousingDetailOverlay({ label, mobile, onClose, children }: {
  readonly label: string
  readonly mobile: boolean
  readonly onClose: () => void
  readonly children: ReactNode
}) {
  const page = useRef<HTMLDivElement>(null)

  useLayoutEffect(() => {
    const element = page.current
    if (!mobile || !element) return
    const boundary = element.closest<HTMLElement>('[data-mobile-detail-sheet]') ?? element
    // Keep the map mounted; portalled attachment dialogs stay outside these branches.
    const root = element.closest('.app-shell') ?? element.closest('.housing-explorer')
    const background = new Map<HTMLElement, { inert: boolean; hidden: string | null }>()
    function isolateBackground() {
      for (let branch: HTMLElement | null = boundary; root && branch && branch !== root; branch = branch.parentElement) {
        for (const sibling of branch.parentElement?.children ?? []) {
          if (sibling === branch || !(sibling instanceof HTMLElement) || background.has(sibling)) continue
          background.set(sibling, { inert: sibling.inert, hidden: sibling.getAttribute('aria-hidden') })
          sibling.inert = true
          sibling.setAttribute('aria-hidden', 'true')
        }
      }
    }
    isolateBackground()
    const observer = new MutationObserver(isolateBackground)
    for (let branch: HTMLElement | null = boundary; root && branch && branch !== root; branch = branch.parentElement) {
      if (branch.parentElement) observer.observe(branch.parentElement, { childList: true })
    }
    return () => {
      observer.disconnect()
      for (const [sibling, entry] of background) {
        sibling.inert = entry.inert
        if (entry.hidden === null) sibling.removeAttribute('aria-hidden')
        else sibling.setAttribute('aria-hidden', entry.hidden)
      }
    }
  }, [mobile])

  return <div ref={page} className="housing-detail-layer" role={mobile ? 'region' : 'dialog'}
    aria-label={mobile ? `${label} 페이지` : label} data-mobile-page={mobile || undefined}
    onKeyDown={(event) => {
      if (event.key !== 'Escape' || event.defaultPrevented
        || !(event.target instanceof Node) || !event.currentTarget.contains(event.target)) return
      event.preventDefault()
      event.stopPropagation()
      onClose()
    }}>
    {children}
  </div>
}
