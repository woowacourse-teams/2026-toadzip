import { useRef, useState, type CSSProperties, type PointerEvent, type ReactNode } from 'react'
import { useMobileViewport } from './useMobileViewport'

const SNAP_LABELS = ['접힘', '절반 펼침', '크게 펼침'] as const
const PEEK_HEIGHT = 76

interface Props {
  readonly visible: boolean
  readonly title: string
  readonly onCollapse: () => void
  readonly children: ReactNode
}

export function HousingResultsPanel({ visible, title, onCollapse, children }: Props) {
  const mobile = useMobileViewport()
  const panel = useRef<HTMLElement>(null)
  const [snap, setSnap] = useState(1)
  const [dragHeight, setDragHeight] = useState<number | null>(null)
  const drag = useRef<{ pointerId: number; y: number; height: number; peek: number; max: number; startTime: number } | null>(null)
  const collapsed = mobile && snap === 0

  function startDrag(event: PointerEvent<HTMLDivElement>) {
    if (!event.isPrimary || event.button !== 0 || !panel.current) return
    const style = getComputedStyle(panel.current)
    const searchBottom = Number.parseFloat(style.getPropertyValue('--explorer-search-bottom')) || 72
    const peek = PEEK_HEIGHT + (Number.parseFloat(style.paddingBottom) || 0)
    const bottom = Number.parseFloat(style.bottom) || 0
    const max = Math.max(peek, window.innerHeight - bottom - Math.max(80, searchBottom + 12))
    drag.current = { pointerId: event.pointerId, y: event.clientY, height: panel.current.getBoundingClientRect().height, peek, max, startTime: event.timeStamp }
    event.currentTarget.setPointerCapture(event.pointerId)
  }

  function moveDrag(event: PointerEvent<HTMLDivElement>) {
    const current = drag.current
    if (!current || current.pointerId !== event.pointerId) return
    setDragHeight(Math.max(current.peek, Math.min(current.max, current.height + current.y - event.clientY)))
  }

  function endDrag(event: PointerEvent<HTMLDivElement>, cancelled = false) {
    const current = drag.current
    if (!current || current.pointerId !== event.pointerId) return
    drag.current = null
    setDragHeight(null)
    if (cancelled) return
    const delta = current.y - event.clientY
    const height = current.height + delta
    const points = [current.peek, Math.min(window.innerHeight * 0.5, current.max), current.max]
    const nearest = points.reduce((index, point, candidate) => Math.abs(point - height) < Math.abs(points[index] - height) ? candidate : index, 0)
    const velocity = delta / Math.max(1, event.timeStamp - current.startTime)
    if (nearest === snap && Math.abs(delta) > 24 && Math.abs(velocity) > 0.45) {
      setSnap((value) => Math.max(0, Math.min(2, value + Math.sign(delta))))
    } else {
      setSnap(nearest)
    }
  }

  return (
    <aside ref={panel} className="housing-results" id="housing-list-page" aria-label="공공임대주택 검색 결과"
      aria-hidden={!visible} inert={!visible} data-mobile={mobile || undefined} data-snap={snap}
      data-dragging={dragHeight !== null || undefined}
      style={mobile && dragHeight !== null ? { '--sheet-drag-height': `${dragHeight}px` } as CSSProperties : undefined}>
      {mobile && <div className="housing-results__sheet-handle" role="slider" tabIndex={0}
        aria-label="목록 높이" aria-orientation="vertical" aria-valuemin={0} aria-valuemax={2}
        aria-valuenow={snap} aria-valuetext={SNAP_LABELS[snap]}
        onPointerDown={startDrag} onPointerMove={moveDrag} onPointerUp={(event) => endDrag(event)}
        onPointerCancel={(event) => endDrag(event, true)} onLostPointerCapture={(event) => endDrag(event, true)}
        onKeyDown={(event) => {
          if (!['ArrowUp', 'ArrowDown', 'Home', 'End', 'Enter', ' '].includes(event.key)) return
          event.preventDefault()
          if (event.key === 'Home') setSnap(0)
          else if (event.key === 'End') setSnap(2)
          else if (event.key === 'ArrowDown') setSnap((value) => Math.max(0, value - 1))
          else if (event.key === 'ArrowUp') setSnap((value) => Math.min(2, value + 1))
          else setSnap((value) => value === 2 ? 0 : value + 1)
        }}><span aria-hidden="true" /></div>}
      <header className="housing-results__page-header">
        <h2>{title}</h2>
        <button type="button" className="housing-results__collapse" aria-label={collapsed ? '목록 펼치기' : '목록 접기'}
          aria-controls="housing-list-content" aria-expanded={visible && !collapsed}
          onClick={() => mobile ? setSnap(collapsed ? 1 : 0) : onCollapse()}>
          <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true">
            <path d="m12 4-6 6 6 6" />
          </svg>
        </button>
      </header>
      <div className="housing-results__content" id="housing-list-content" aria-hidden={collapsed} inert={collapsed}>
        {children}
      </div>
    </aside>
  )
}
