import { useLayoutEffect, useRef, type ReactNode } from 'react'
import { useMobileViewport } from './useMobileViewport'

export function HousingDetailOverlay({ label, onClose, children }: {
  readonly label: string
  readonly onClose: () => void
  readonly children: ReactNode
}) {
  const mobile = useMobileViewport()
  const dialog = useRef<HTMLDialogElement>(null)

  useLayoutEffect(() => {
    const element = dialog.current
    if (!element) return
    const focused = document.activeElement
    const foreground = focused instanceof HTMLElement ? focused.closest('dialog[open]') : null
    if (element.open) element.close()
    if (mobile) element.showModal()
    else element.open = true
    // A viewport change must keep an already-open attachment/email dialog above this detail.
    if (foreground instanceof HTMLDialogElement && foreground !== element) {
      if (mobile) { foreground.close(); foreground.showModal() }
      if (focused instanceof HTMLElement) focused.focus({ preventScroll: true })
    }
  }, [mobile])

  useLayoutEffect(() => {
    const element = dialog.current
    if (!element) return
    return () => element.close()
  }, [])

  return <dialog ref={dialog} className="housing-detail-layer" aria-label={label} aria-modal={mobile}
    onCancel={(event) => {
      if (event.target !== event.currentTarget) return
      event.preventDefault()
      onClose()
    }}
    onClick={(event) => {
      if (!mobile || event.target !== event.currentTarget) return
      const bounds = event.currentTarget.getBoundingClientRect()
      if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) onClose()
    }}>
    {children}
  </dialog>
}
