import { useId, useRef, useState } from 'react'
import { IconButton } from '../../design-system/components/IconButton.tsx'
import type { CurrentLocationState } from './useCurrentLocation.ts'
import styles from './CurrentLocationControl.module.css'

export function CurrentLocationControl({ state, onLocate }: {
  readonly state: CurrentLocationState
  readonly onLocate: () => void
}) {
  const statusId = useId()
  const locateButtonRef = useRef<HTMLButtonElement>(null)
  const [dismissedError, setDismissedError] = useState<CurrentLocationState | null>(null)
  // Each failed request has a new state, even when its message is unchanged.
  const message = state.kind === 'error' && state !== dismissedError ? state.message : null
  return (
    <div className={styles.control}>
      <IconButton ref={locateButtonRef} label="현재 위치로 이동" title="현재 위치로 이동" size="lg"
        disabled={state.kind === 'loading'} aria-busy={state.kind === 'loading'}
        aria-describedby={message ? statusId : undefined} onClick={onLocate}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
          <circle cx="12" cy="12" r="7" /><circle cx="12" cy="12" r="2.5" />
          <path d="M12 2v3M12 19v3M2 12h3M19 12h3" />
        </svg>
      </IconButton>
      <div className={styles.status} hidden={!message}>
        <span id={statusId} role="status" aria-live="polite">{message}</span>
        <IconButton label="위치 안내 닫기" size="lg" onClick={event => {
          setDismissedError(state)
          if (document.activeElement === event.currentTarget) locateButtonRef.current?.focus()
        }}>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"
            aria-hidden="true" focusable="false">
            <path d="m5 5 14 14M19 5 5 19" />
          </svg>
        </IconButton>
      </div>
    </div>
  )
}
