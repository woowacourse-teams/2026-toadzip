import { useEffect, useId, useLayoutEffect, useRef } from 'react'
import { StreetViewFrame } from './StreetViewFrame'
import type { StreetViewController } from './useStreetView'
import styles from './StreetView.module.css'

export function StreetViewPanel({ controller }: { readonly controller: StreetViewController }) {
  const { state } = controller
  const returnButton = useRef<HTMLButtonElement>(null)
  const titleId = useId()
  const active = controller.active
  useLayoutEffect(() => {
    if (active) returnButton.current?.focus({ preventScroll: true })
  }, [active])
  const { panelRef, panelFocusedRef } = controller
  useEffect(() => {
    if (!active) return
    const trackDocumentFocus = () => {
      // Breakpoint/dialog autofocus can precede React's media-query commit. Preserve the last PC focus.
      if (isMobileViewport()) return
      if (panelRef.current?.isConnected) panelFocusedRef.current = panelRef.current.contains(document.activeElement)
    }
    const trackFrameFocus = () => {
      // Focus within a child document need not dispatch React focus on the iframe element.
      queueMicrotask(trackDocumentFocus)
    }
    document.addEventListener('focusin', trackDocumentFocus)
    window.addEventListener('blur', trackFrameFocus)
    return () => {
      document.removeEventListener('focusin', trackDocumentFocus)
      window.removeEventListener('blur', trackFrameFocus)
    }
  }, [active, panelRef, panelFocusedRef])

  if (!state) return null
  return <div id={controller.panelId} ref={controller.panelRef} className={styles.panel}
    role="region" aria-labelledby={titleId}
    onFocusCapture={() => { if (!isMobileViewport()) controller.panelFocusedRef.current = true }}
    onBlurCapture={event => {
      if (isMobileViewport()) return
      if (!(event.relatedTarget instanceof Node) || !event.currentTarget.contains(event.relatedTarget)) {
        controller.panelFocusedRef.current = false
      }
    }}
    onKeyDown={event => {
      if (event.key !== 'Escape' || event.defaultPrevented) return
      event.preventDefault()
      event.stopPropagation()
      controller.close('USER_CLOSED', true, 'escape')
    }}>
    <header className={styles.header}>
      <h2 id={titleId}>단지 주변 거리뷰</h2>
      <button ref={returnButton} type="button" className={styles.secondaryButton}
        onClick={() => controller.close('USER_CLOSED', true, 'map_button')}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" aria-hidden="true" focusable="false">
          <path d="m14 5-7 7 7 7M7 12h14" />
        </svg>
        지도 보기
      </button>
    </header>
    <div className={styles.viewer}>
      {state.kind === 'viewing' && <StreetViewFrame session={state.session} ready={state.ready}
        onReady={controller.onReady} onLocation={controller.onLocation} onFailure={controller.onFailure}
        onMarkerStatus={controller.onMarkerStatus} onClose={() => controller.close('USER_CLOSED', true, 'escape')} />}
      {(state.kind === 'checking' || (state.kind === 'viewing' && !state.ready))
        && <div className={styles.status} role="status">거리뷰를 불러오는 중이에요.</div>}
      {(state.kind === 'blocked' || state.kind === 'error') && <div className={styles.status}>
        <p role={state.kind === 'error' ? 'alert' : 'status'}>{state.message}</p>
        {state.kind === 'error' && <>
          {state.refreshPage && <p>계속 연결되지 않으면 화면을 새로고침한 뒤 다시 열어 주세요.</p>}
          <button type="button" className={styles.secondaryButton} onClick={() => { void controller.open() }}>다시 시도</button>
        </>}
      </div>}
    </div>
    <footer className={styles.footer}>
      <span>{state.kind === 'viewing' && state.photodate ? `촬영일 ${state.photodate}` : '촬영일 미제공'}</span>
      {state.kind === 'viewing' && state.ready && !state.aligned
        && <span>초기 방향을 맞추지 못했어요. 화면을 움직여 주변을 확인해 주세요.</span>}
      {state.kind === 'viewing' && state.markerStatus === 'UNAVAILABLE'
        && <span role="status">출입구 위치 표시를 불러오지 못했어요. 거리뷰는 계속 둘러볼 수 있어요.</span>}
    </footer>
  </div>
}

function isMobileViewport(): boolean {
  return window.matchMedia?.('(max-width: 767px)').matches ?? false
}
