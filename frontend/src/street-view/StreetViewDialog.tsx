import { useId, useLayoutEffect, useRef } from 'react'
import { createPortal } from 'react-dom'
import { DetailCloseButton } from '../public-housing/components/DetailPrimitives'
import { StreetViewFrame, type StreetViewSession } from './StreetViewFrame'
import styles from './StreetView.module.css'

export type StreetViewDialogState =
  | { readonly kind: 'checking' }
  | { readonly kind: 'blocked'; readonly message: string }
  | { readonly kind: 'error'; readonly message: string }
  | { readonly kind: 'viewing'; readonly session: StreetViewSession; readonly ready: boolean;
      readonly aligned: boolean; readonly photodate: string | null }

export function StreetViewDialog({ name, address, state, onClose, onRetry, onReady, onLocation, onFailure }: {
  readonly name: string
  readonly address: string
  readonly state: StreetViewDialogState
  readonly onClose: () => void
  readonly onRetry: () => void
  readonly onReady: (aligned: boolean) => void
  readonly onLocation: (photodate: string | null) => void
  readonly onFailure: () => void
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  const titleId = useId()
  useLayoutEffect(() => {
    const element = dialog.current
    element?.showModal()
    return () => element?.close()
  }, [])

  return createPortal(<dialog ref={dialog} className={styles.dialog} aria-labelledby={titleId}
    onCancel={(event) => { event.preventDefault(); event.stopPropagation(); onClose() }}
    onKeyDown={(event) => event.stopPropagation()}
    onClick={(event) => {
      if (event.target !== event.currentTarget) return
      const bounds = event.currentTarget.getBoundingClientRect()
      if (event.clientX < bounds.left || event.clientX > bounds.right
        || event.clientY < bounds.top || event.clientY > bounds.bottom) onClose()
    }}>
    <header className={styles.header}>
      <div><h2 id={titleId}>단지 주변 거리뷰</h2><p>{name}</p><span>{address}</span></div>
      <DetailCloseButton label="거리뷰 닫기" onClose={onClose} />
    </header>
    <div className={styles.viewer}>
      {state.kind === 'viewing' && <StreetViewFrame session={state.session} ready={state.ready}
        onReady={onReady} onLocation={onLocation} onFailure={onFailure} onClose={onClose} />}
      {(state.kind === 'checking' || (state.kind === 'viewing' && !state.ready))
        && <div className={styles.status} role="status">거리뷰를 불러오는 중이에요.</div>}
      {(state.kind === 'blocked' || state.kind === 'error') && <div className={styles.status}>
        <p role={state.kind === 'error' ? 'alert' : 'status'}>{state.message}</p>
        {state.kind === 'error' && <button type="button" className={styles.secondaryButton} onClick={onRetry}>다시 시도</button>}
      </div>}
    </div>
    <footer className={styles.footer}>
      <span>{state.kind === 'viewing' && state.photodate ? `촬영일 ${state.photodate}` : '촬영일 미제공'}</span>
      {state.kind === 'viewing' && state.ready && !state.aligned
        && <span>초기 방향을 맞추지 못했어요. 화면을 움직여 주변을 확인해 주세요.</span>}
    </footer>
  </dialog>, document.body)
}
