import { useEffect, useId, useState } from 'react'
import type { StreetViewController } from './useStreetView'
import styles from './StreetView.module.css'

export function StreetViewEntry({ controller, name }: {
  readonly controller: StreetViewController
  readonly name: string
}) {
  const [hovered, setHovered] = useState(false)
  const [focused, setFocused] = useState(false)
  const [tooltip, setTooltip] = useState(false)
  const [dismissed, setDismissed] = useState(false)
  const descriptionId = useId()
  const enabled = controller.active || controller.available

  useEffect(() => {
    if ((!hovered && !focused) || enabled || dismissed || !controller.visible) {
      setTooltip(false)
      return
    }
    const timeout = window.setTimeout(() => setTooltip(true), 600)
    return () => window.clearTimeout(timeout)
  }, [hovered, focused, enabled, dismissed, controller.visible])

  useEffect(() => {
    if (!tooltip) return
    const dismiss = (event: KeyboardEvent) => {
      // A top-layer dialog retains Escape even while the pointer hovers this button.
      if (event.key !== 'Escape' || event.target instanceof Element && event.target.closest('dialog[open]:not([aria-modal="false"])')) return
      event.preventDefault()
      event.stopPropagation()
      setDismissed(true)
      setTooltip(false)
    }
    document.addEventListener('keydown', dismiss, true)
    return () => document.removeEventListener('keydown', dismiss, true)
  }, [tooltip])

  if (!controller.visible) return null
  return <div className={styles.entry}
    onMouseEnter={() => { setHovered(true); setDismissed(false) }}
    onMouseLeave={() => setHovered(false)}>
    <button ref={controller.buttonRef} type="button" className={styles.secondaryButton}
      aria-label={`${name} 주변 거리뷰 보기`} aria-disabled={!enabled}
      aria-pressed={controller.active} aria-controls={controller.panelId}
      aria-describedby={!enabled ? descriptionId : undefined}
      onClick={() => {
        if (controller.active) { setDismissed(true); controller.close(); return }
        if (controller.available) { setDismissed(true); void controller.open() }
      }}
      onFocus={() => { setFocused(true); setDismissed(false) }} onBlur={() => setFocused(false)}
      onKeyDown={(event) => {
        if (!enabled && (event.key === 'Enter' || event.key === ' ')) event.preventDefault()
      }}>
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" aria-hidden="true" focusable="false">
        <path d="M3 6.5 8 4l8 2.5L21 4v13.5L16 20l-8-2.5L3 20V6.5ZM8 4v13.5M16 6.5V20" />
      </svg>
      거리뷰
    </button>
    {!enabled && <span id={descriptionId} className={tooltip ? styles.tooltip : styles.description}
      role={tooltip ? 'tooltip' : undefined}>{controller.description}</span>}
    {!controller.active && controller.availability.kind === 'error'
      && <button type="button" className={styles.retry} onClick={controller.refresh}>다시 확인</button>}
  </div>
}
