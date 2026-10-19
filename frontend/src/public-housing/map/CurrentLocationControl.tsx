import { useId, useRef, useState } from 'react'
import { Button } from '../../design-system/components/Button.tsx'
import { IconButton } from '../../design-system/components/IconButton.tsx'
import type { CurrentLocationState } from './useCurrentLocation.ts'
import styles from './CurrentLocationControl.module.css'

export function CurrentLocationControl({ state, onLocate }: {
  readonly state: CurrentLocationState
  readonly onLocate: () => void
}) {
  const statusId = useId()
  const locateButton = useRef<HTMLButtonElement>(null)
  const [dismissedError, setDismissedError] = useState<CurrentLocationState | null>(null)
  const loading = state.kind === 'loading'
  // Each failed request has a new state, even when its message is unchanged.
  const message = state.kind === 'error' && state !== dismissedError ? state.message : null
  const denied = state.kind === 'error' && state.reason === 'permission-denied'
  const requestLocation = () => {
    if (loading) return
    locateButton.current?.focus()
    onLocate()
  }
  return (
    <div className={styles.control}>
      <IconButton label="현재 위치로 이동" title="현재 위치로 이동" size="lg"
        ref={locateButton} aria-disabled={loading} aria-busy={loading}
        aria-describedby={message ? statusId : undefined} onClick={requestLocation}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
          <circle cx="12" cy="12" r="7" /><circle cx="12" cy="12" r="2.5" />
          <path d="M12 2v3M12 19v3M2 12h3M19 12h3" />
        </svg>
      </IconButton>
      <div className={styles.status} hidden={!message} data-location-status>
        <div className={styles.statusHeader}>
          <p id={statusId} role="status" aria-live="polite">{message}</p>
          <IconButton label="위치 안내 닫기" className={styles.dismissButton} onClick={event => {
            setDismissedError(state)
            if (document.activeElement === event.currentTarget) locateButton.current?.focus()
          }}>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
              <path d="m6 6 12 12M18 6 6 18" />
            </svg>
          </IconButton>
        </div>
        {denied && message && <LocationPermissionGuidance onRetry={requestLocation} />}
      </div>
    </div>
  )
}

function LocationPermissionGuidance({ onRetry }: { readonly onRetry: () => void }) {
  const [expanded, setExpanded] = useState(false)
  const guidanceId = useId()
  const settingsButton = useRef<HTMLButtonElement>(null)
  return (
    <div onKeyDown={event => {
      if (event.key !== 'Escape' || !expanded) return
      event.preventDefault()
      event.stopPropagation()
      setExpanded(false)
      settingsButton.current?.focus()
    }}>
      <button type="button" ref={settingsButton} className={styles.settingsButton}
        aria-expanded={expanded} aria-controls={guidanceId}
        onClick={() => setExpanded(value => !value)}>
        설정 방법
        <span aria-hidden="true">{expanded ? '−' : '+'}</span>
      </button>
      <section id={guidanceId} className={styles.guidance} aria-label="위치 사용 설정 방법" hidden={!expanded}>
        <p>이 화면에서 설정을 직접 열 수는 없어요. 사용 중인 브라우저에서 위치를 허용해 주세요.</p>
        <h3>iPhone · Safari</h3>
        <ol>
          <li>주소창의 페이지 메뉴 → 더 보기 → 웹사이트 설정에서 위치를 허용해 주세요.</li>
          <li>계속 차단되면 iPhone 설정 → 개인정보 보호 및 보안 → 위치 서비스에서 위치 서비스를 켜고, Safari 웹사이트의 위치 접근을 허용해 주세요.</li>
        </ol>
        <h3>Android · Chrome</h3>
        <ol>
          <li>주소창 왼쪽 사이트 정보 → 권한 → 위치에서 허용해 주세요.</li>
          <li>항목이 없으면 Chrome 더보기 → 설정 → 사이트 설정 → 위치에서 이 사이트의 차단을 해제해 주세요. 기기의 위치와 Chrome 앱의 위치 권한도 확인해 주세요.</li>
        </ol>
        <p className={styles.note}>기기·브라우저 버전에 따라 메뉴 이름이 다를 수 있어요. 설정 후 돌아와 다시 시도해 주세요.</p>
        <Button onClick={onRetry}>다시 시도</Button>
      </section>
    </div>
  )
}
