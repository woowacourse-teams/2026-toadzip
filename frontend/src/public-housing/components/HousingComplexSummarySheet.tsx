import { useEffect, useId, useRef, useState, type PointerEvent, type ReactNode } from 'react'
import { IconButton } from '../../design-system/components/IconButton'
import type { ComplexDetail } from '../model/publicHousing'
import type { RecentComplex } from '../navigation/recentComplexes'
import { MISSING_DATA_LABEL } from '../presentation/missingData'
import { rentalTypeLabel } from '../presentation/rentalTypeLabel'
import { toHousingComplexDetailData } from '../presentation/complexDetailPresentation'
import { HousingComplexDetailPanel } from './HousingComplexDetailPanel'
import { DetailCloseButton } from './DetailPrimitives'
import type { DetailStatus } from './HousingDetailStatePanel'
import styles from './HousingComplexSummarySheet.module.css'

interface Props {
  readonly recent: RecentComplex | undefined
  readonly detail: ComplexDetail | null
  readonly status: DetailStatus
  readonly onExpand: () => void
  readonly onClose: () => void
  readonly onRetry: () => void
  readonly expanded?: boolean
  readonly onBackToSummary?: () => void
  readonly children?: ReactNode
}

export function HousingComplexSummarySheet({ recent, detail, status, onExpand, onClose, onRetry,
  expanded = false, onBackToSummary, children }: Props) {
  const sheet = useRef<HTMLElement>(null)
  const handle = useRef<HTMLButtonElement>(null)
  const content = useRef<HTMLDivElement>(null)
  const contentId = useId()
  const descriptionId = useId()
  const drag = useRef<{ pointerId: number; y: number; time: number; height: number; bottom: number } | null>(null)
  const suppressClick = useRef(false)
  const [collapsed, setCollapsed] = useState(false)
  const name = detail?.name ?? recent?.name ?? '선택한 단지'
  const agencyCode = (detail?.agency?.code ?? recent?.agencyCode)?.trim().toUpperCase()
  const agency = agencyCode || detail?.agency?.name || recent?.agencyName || MISSING_DATA_LABEL
  const rental = rentalTypeLabel(detail?.rentalType ?? recent?.rentalType ?? null) ?? MISSING_DATA_LABEL
  const address = detail?.address?.roadAddress ?? recent?.address

  useEffect(() => {
    if (!expanded) sheet.current?.focus({ preventScroll: true })
  }, [expanded])

  function stepUp() {
    if (collapsed) setCollapsed(false)
    else if (!expanded) onExpand()
  }

  function stepDown() {
    if (expanded) onBackToSummary?.()
    else {
      // Do not leave keyboard focus inside the content we are about to hide.
      if (content.current?.contains(document.activeElement)) {
        handle.current?.focus({ preventScroll: true })
      }
      setCollapsed(true)
    }
  }

  function startDrag(event: PointerEvent<HTMLElement>) {
    if (!event.isPrimary || event.button !== 0 || !sheet.current || drag.current) return
    const control = event.target instanceof Element ? event.target.closest('button, a, input, select, textarea') : null
    if (control && !control.hasAttribute('data-summary-handle')) return
    suppressClick.current = false
    drag.current = { pointerId: event.pointerId, y: event.clientY, time: event.timeStamp,
      height: sheet.current.getBoundingClientRect().height,
      bottom: Number.parseFloat(getComputedStyle(sheet.current).bottom) || 0 }
    const captureTarget = control instanceof HTMLElement ? control : event.currentTarget
    captureTarget.setPointerCapture(event.pointerId)
  }

  function moveDrag(event: PointerEvent<HTMLElement>) {
    const current = drag.current
    if (!current || current.pointerId !== event.pointerId || !sheet.current) return
    const height = Math.max(84, Math.min(window.innerHeight - current.bottom, current.height + current.y - event.clientY))
    // Keep continuous pointer movement out of React's render cycle.
    sheet.current.style.setProperty('--summary-drag-height', `${height}px`)
    sheet.current.dataset.dragging = 'true'
  }

  function endDrag(event: PointerEvent<HTMLElement>, cancelled = false) {
    const current = drag.current
    if (!current || current.pointerId !== event.pointerId) return
    const distance = current.y - event.clientY
    const velocity = distance / Math.max(1, event.timeStamp - current.time)
    drag.current = null
    sheet.current?.removeAttribute('data-dragging')
    sheet.current?.style.removeProperty('--summary-drag-height')
    suppressClick.current = cancelled || Math.abs(distance) > 8
    if (event.currentTarget.hasPointerCapture?.(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId)
    if (cancelled || (Math.abs(distance) < 60 && !(Math.abs(distance) > 24 && Math.abs(velocity) > 0.45))) return
    if (distance > 0) stepUp()
    else stepDown()
  }

  const dragHandlers = {
    onPointerDown: startDrag, onPointerMove: moveDrag,
    onPointerUp: (event: PointerEvent<HTMLElement>) => endDrag(event),
    onPointerCancel: (event: PointerEvent<HTMLElement>) => endDrag(event, true),
    onLostPointerCapture: (event: PointerEvent<HTMLElement>) => endDrag(event, true),
  }

  const collapseButton = <IconButton label={collapsed ? '단지 미리보기 복원' : '단지 미리보기 접기'}
    aria-controls={contentId} aria-expanded={!collapsed} onClick={() => collapsed ? setCollapsed(false) : stepDown()}>
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
      <path d={collapsed ? 'm7 14 5-5 5 5' : 'm7 10 5 5 5-5'} />
    </svg>
  </IconButton>
  const ready = detail !== null && status === 'ready'

  return <section ref={sheet} className={styles.sheet} data-complex-summary={!expanded || undefined}
    data-mobile-detail-sheet={expanded || undefined} data-expanded={expanded} data-collapsed={collapsed}
    aria-label={expanded ? '선택한 단지' : '단지 요약'} tabIndex={-1}
    aria-describedby={!expanded && !collapsed && ready ? descriptionId : undefined}
    {...(expanded ? {} : dragHandlers)}
    onKeyDown={(event) => {
      if (event.key === 'Escape') { event.stopPropagation(); onClose() }
    }}>
    <button ref={handle} type="button" className={styles.handle} data-summary-handle
      aria-label={expanded ? '단지 미리보기로 접기' : collapsed ? '단지 미리보기 펼치기' : '단지 상세 펼치기'}
      {...(expanded ? dragHandlers : {})}
      onClick={() => {
        if (suppressClick.current) { suppressClick.current = false; return }
        if (expanded) stepDown()
        else stepUp()
      }}
      onKeyDown={(event) => {
        if (event.key === 'ArrowUp') { event.preventDefault(); stepUp() }
        else if (event.key === 'End') { event.preventDefault(); setCollapsed(false); if (!expanded) onExpand() }
        else if (event.key === 'ArrowDown' || event.key === 'Home') { event.preventDefault(); stepDown() }
        else if (event.key === 'Enter' || event.key === ' ') suppressClick.current = false
      }}>
      <span aria-hidden="true" />
    </button>
    <div className={styles.summary} hidden={expanded}>
      {(collapsed || !ready) && <header className={styles.header}>
        <h2>{name}</h2>
        {collapseButton}
        <DetailCloseButton label="단지 요약 닫기" onClose={onClose} />
      </header>}
      <div ref={content} id={contentId} className={ready ? styles.preview : styles.content}
        aria-hidden={collapsed || undefined} inert={collapsed || undefined}>
        {ready ? <>
          <p id={descriptionId} className={styles.accessibleDescription}>
            {agency} {rental}.
            {address ?? MISSING_DATA_LABEL}.
            {detail.currentAnnouncements.length > 0
              ? ` 현재 모집 공고 ${detail.currentAnnouncements.length}건. ${detail.currentAnnouncements[0].title ?? ''}`
              : ' 현재 연결된 모집 공고가 없습니다.'}
          </p>
          <HousingComplexDetailPanel detail={toHousingComplexDetailData(detail)} mobile preview
            onClose={onClose} headerActions={collapseButton} />
        </> : <>
          <p className={styles.metadata} aria-label={`공급기관 ${agency}, 임대유형 ${rental}`}>
            <strong data-agency={agencyCode}>{agency}</strong><span aria-hidden="true">·</span><span>{rental}</span>
          </p>
          {address && <p className={styles.address}>{address}</p>}
          {status === 'loading' && <p className={styles.status} role="status">단지 정보를 불러오고 있어요.</p>}
          {(status === 'error' || status === 'not-found') && <div className={styles.status} role="alert">
            <p>{status === 'not-found' ? '단지를 찾을 수 없어요.' : '단지 정보를 불러오지 못했어요.'}</p>
            {status === 'error' && <button type="button" onClick={onRetry}>다시 시도</button>}
          </div>}
        </>}
      </div>
    </div>
    {expanded && <div className={styles.detail}>{children}</div>}
  </section>
}
