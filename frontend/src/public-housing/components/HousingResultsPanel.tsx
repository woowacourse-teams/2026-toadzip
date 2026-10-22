import { captureProductEvent } from '../../analytics/productAnalytics'
import { useEffect, useLayoutEffect, useRef, useState, type CSSProperties, type PointerEvent, type ReactNode } from 'react'
import { useMobileViewport } from './useMobileViewport'

const HALF_HEIGHT = 50
const DRAG_THRESHOLD = 12

interface Props {
  readonly visible: boolean
  readonly title: string
  readonly onCollapse: () => void
  readonly children: ReactNode
}

export function HousingResultsPanel({ visible, title, onCollapse, children }: Props) {
  const mobile = useMobileViewport()
  const panel = useRef<HTMLElement>(null)
  const [viewport, setViewport] = useState(readViewport)
  const compact = mobile && viewport.landscape && viewport.short
  const heightMode = viewport.landscape ? 'landscape' : 'portrait'
  const [heightsByMode, setHeightsByMode] = useState<{ portrait: number; landscape: number | null }>({ portrait: HALF_HEIGHT, landscape: null })
  const heightPercent = heightsByMode[heightMode] ?? (compact ? 100 : HALF_HEIGHT)
  const setHeightPercent = (height: number) => setHeightsByMode(previous => ({ ...previous, [heightMode]: height }))

  useEffect(() => {
    const update = () => setViewport(readViewport())
    window.addEventListener('resize', update)
    return () => window.removeEventListener('resize', update)
  }, [])
  const [dragHeight, setDragHeight] = useState<number | null>(null)
  const [searchCovered, setSearchCovered] = useState(false)
  const resetOnOpen = useRef(false)
  const drag = useRef<{ pointerId: number; y: number; height: number; half: number; max: number } | null>(null)

  useLayoutEffect(() => {
    if (visible && resetOnOpen.current) {
      resetOnOpen.current = false
      setHeightsByMode(previous => ({ ...previous, [heightMode]: compact ? 100 : HALF_HEIGHT }))
    }
    // A drag started before rotation has coordinates from the previous layout.
    drag.current = null
    setDragHeight(null)
  }, [visible, mobile, compact, heightMode])

  useLayoutEffect(() => {
    const search = panel.current?.parentElement?.querySelector('.housing-search-overlay')
    if (!mobile || !visible || !search) {
      setSearchCovered(false)
      return
    }
    const updateCoverage = () => {
      const heights = readHeights()
      if (!heights || !panel.current) return
      const bottom = Number.parseFloat(getComputedStyle(panel.current).bottom) || 0
      const height = Math.min(dragHeight ?? window.innerHeight * heightPercent / 100, heights.max)
      setSearchCovered(window.innerHeight - bottom - height < search.getBoundingClientRect().bottom)
    }
    updateCoverage()
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(updateCoverage)
    observer?.observe(search)
    window.addEventListener('resize', updateCoverage)
    return () => {
      observer?.disconnect()
      window.removeEventListener('resize', updateCoverage)
    }
  }, [mobile, visible, heightPercent, dragHeight])

  function readHeights() {
    if (!panel.current) return null
    const style = getComputedStyle(panel.current)
    const bottom = Number.parseFloat(style.bottom) || 0
    const max = Number.parseFloat(style.maxHeight) || window.innerHeight - bottom - 8
    return { height: panel.current.getBoundingClientRect().height, half: Math.min(window.innerHeight / 2, max), max }
  }

  function changeHeight(next: number, method: 'drag' | 'keyboard') {
    if (next === heightPercent) return
    const label = (height: number) => height === HALF_HEIGHT ? 'half' : height === 100 ? 'full' : 'custom'
    captureProductEvent('results_panel_snap_changed', {
      from_snap: label(heightPercent), to_snap: label(next), method,
      list_view_id: panel.current?.dataset.listViewId ?? 'unavailable',
    })
    setHeightPercent(next)
  }

  function dismiss() {
    resetOnOpen.current = true
    onCollapse()
  }

  function startDrag(event: PointerEvent<HTMLDivElement>) {
    if (!mobile || !visible || !event.isPrimary || event.button !== 0
      || (event.target instanceof Element && event.target.closest('button'))) return
    const heights = readHeights()
    if (!heights) return
    drag.current = { pointerId: event.pointerId, y: event.clientY, ...heights }
    event.currentTarget.setPointerCapture(event.pointerId)
  }

  function moveDrag(event: PointerEvent<HTMLDivElement>) {
    const current = drag.current
    if (!current || current.pointerId !== event.pointerId) return
    setDragHeight(Math.max(0, Math.min(current.max, current.height + current.y - event.clientY)))
  }

  function endDrag(event: PointerEvent<HTMLDivElement>, cancelled = false) {
    const current = drag.current
    if (!current || current.pointerId !== event.pointerId) return
    drag.current = null
    setDragHeight(null)
    const delta = current.y - event.clientY
    if (cancelled || Math.abs(delta) < DRAG_THRESHOLD) return
    const height = Math.max(0, Math.min(current.max, current.height + delta))
    if (height < current.half) {
      // Close from the release height instead of jumping back up first.
      setHeightPercent(height / window.innerHeight * 100)
      dismiss()
    } else {
      changeHeight(height >= current.max ? 100 : height / window.innerHeight * 100, 'drag')
    }
  }

  return (
    <aside ref={panel} className="housing-results" id="housing-list-page" aria-label="공공임대주택 검색 결과"
      aria-hidden={!visible} inert={!visible} data-mobile={mobile || undefined}
      data-search-covered={searchCovered || undefined}
      data-dragging={dragHeight !== null || undefined}
      style={mobile ? {
        '--sheet-height': `${heightPercent}dvh`,
        ...(dragHeight !== null ? { '--sheet-drag-height': `${dragHeight}px` } : {}),
      } as CSSProperties : undefined}>
      <div className="housing-results__sheet-top"
        onPointerDown={startDrag} onPointerMove={moveDrag} onPointerUp={(event) => endDrag(event)}
        onPointerCancel={(event) => endDrag(event, true)} onLostPointerCapture={(event) => endDrag(event, true)}>
        {mobile && <div className="housing-results__sheet-handle" role="slider" tabIndex={0}
          aria-label="목록 높이" aria-orientation="vertical" aria-valuemin={0} aria-valuemax={100}
          aria-valuenow={heightPercent}
          aria-valuetext={heightPercent === HALF_HEIGHT ? '절반 펼침' : heightPercent === 100 ? '크게 펼침' : `화면 높이의 ${Math.round(heightPercent)}%`}
          onKeyDown={(event) => {
            if (!['ArrowUp', 'ArrowDown', 'Home', 'End', 'Enter', ' ', 'Escape'].includes(event.key)) return
            event.preventDefault()
            if (event.key === 'Escape' || event.key === 'Home') dismiss()
            else if (event.key === 'End') changeHeight(100, 'keyboard')
            else if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
              const heights = readHeights()
              if (!heights) return
              const next = heights.height + window.innerHeight * (event.key === 'ArrowUp' ? 0.1 : -0.1)
              if (next < heights.half) dismiss()
              else changeHeight(next >= heights.max ? 100 : next / window.innerHeight * 100, 'keyboard')
            } else changeHeight(heightPercent === 100 ? HALF_HEIGHT : 100, 'keyboard')
          }}><span aria-hidden="true" /></div>}
        <header className="housing-results__page-header">
          <h2>{title}</h2>
          <button type="button" className="housing-results__collapse" aria-label="목록 접기"
            aria-controls="housing-list-content" aria-expanded={visible} onClick={dismiss}>
            <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true">
              <path d="m12 4-6 6 6 6" />
            </svg>
          </button>
        </header>
      </div>
      <div className="housing-results__content" id="housing-list-content">
        {children}
      </div>
    </aside>
  )
}

// Matches the compact mobile CSS query; resize also covers the on-screen keyboard.
function readViewport() {
  return { landscape: window.innerWidth > window.innerHeight, short: window.innerHeight <= 500 }
}
