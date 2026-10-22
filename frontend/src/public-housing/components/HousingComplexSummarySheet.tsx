import { useEffect, useId, useRef, useState, type PointerEvent } from 'react'
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
}

export function HousingComplexSummarySheet({ recent, detail, status, onExpand, onClose, onRetry }: Props) {
  const sheet = useRef<HTMLElement>(null)
  const descriptionId = useId()
  const drag = useRef<{ pointerId: number; y: number; time: number } | null>(null)
  const suppressClick = useRef(false)
  const [dragDistance, setDragDistance] = useState(0)
  const name = detail?.name ?? recent?.name ?? '선택한 단지'
  const agencyCode = (detail?.agency?.code ?? recent?.agencyCode)?.trim().toUpperCase()
  const agency = agencyCode || detail?.agency?.name || recent?.agencyName || MISSING_DATA_LABEL
  const rental = rentalTypeLabel(detail?.rentalType ?? recent?.rentalType ?? null) ?? MISSING_DATA_LABEL
  const address = detail?.address?.roadAddress ?? recent?.address

  useEffect(() => { sheet.current?.focus({ preventScroll: true }) }, [])

  function endDrag(event: PointerEvent<HTMLElement>, cancelled = false) {
    const current = drag.current
    if (!current || current.pointerId !== event.pointerId) return
    const distance = current.y - event.clientY
    const velocity = distance / Math.max(1, event.timeStamp - current.time)
    drag.current = null
    setDragDistance(0)
    suppressClick.current = cancelled || Math.abs(distance) > 8
    if (!cancelled && (distance >= 60 || (distance > 24 && velocity > 0.45))) onExpand()
  }

  return <section ref={sheet} className={styles.sheet} data-complex-summary aria-label="단지 요약" tabIndex={-1}
    aria-describedby={detail && status === 'ready' ? descriptionId : undefined}
    style={dragDistance > 0 ? { height: `calc(45dvh + ${dragDistance}px)` } : undefined}
    onPointerDown={(event) => {
      if (!event.isPrimary || event.button !== 0) return
      const control = event.target instanceof Element ? event.target.closest('button, a, input, select, textarea') : null
      if (control && !control.hasAttribute('data-summary-handle')) return
      suppressClick.current = false
      drag.current = { pointerId: event.pointerId, y: event.clientY, time: event.timeStamp }
      const captureTarget = control instanceof HTMLElement ? control : event.currentTarget
      captureTarget.setPointerCapture(event.pointerId)
    }}
    onPointerMove={(event) => {
      if (drag.current?.pointerId === event.pointerId) setDragDistance(Math.max(0, drag.current.y - event.clientY))
    }}
    onPointerUp={(event) => endDrag(event)}
    onPointerCancel={(event) => endDrag(event, true)}
    onLostPointerCapture={(event) => endDrag(event, true)}
    onKeyDown={(event) => {
      if (event.key === 'Escape') { event.stopPropagation(); onClose() }
    }}>
    <button type="button" className={styles.handle} data-summary-handle aria-label="단지 상세 펼치기"
      onClick={() => {
        if (suppressClick.current) { suppressClick.current = false; return }
        onExpand()
      }}
      onKeyDown={(event) => {
        if (event.key === 'ArrowUp' || event.key === 'End') { event.preventDefault(); onExpand() }
        else if (event.key === 'Enter' || event.key === ' ') suppressClick.current = false
      }}>
      <span aria-hidden="true" />
    </button>
    {detail && status === 'ready'
      ? <>
          <p id={descriptionId} className={styles.accessibleDescription}>
            {agency} {rental}.
            {address ?? MISSING_DATA_LABEL}.
            {detail.currentAnnouncements.length > 0
              ? ` 현재 모집 공고 ${detail.currentAnnouncements.length}건. ${detail.currentAnnouncements[0].title ?? ''}`
              : ' 현재 연결된 모집 공고가 없습니다.'}
          </p>
          <HousingComplexDetailPanel detail={toHousingComplexDetailData(detail)} mobile preview onClose={onClose} />
        </>
      : <>
    <header className={styles.header}>
      <h2>{name}</h2>
      <DetailCloseButton label="단지 요약 닫기" onClose={onClose} />
    </header>
    <div className={styles.content}>
      <p className={styles.metadata} aria-label={`공급기관 ${agency}, 임대유형 ${rental}`}>
        <strong data-agency={agencyCode}>{agency}</strong><span aria-hidden="true">·</span><span>{rental}</span>
      </p>
      {address && <p className={styles.address}>{address}</p>}
      {status === 'loading' && <p className={styles.status} role="status">단지 정보를 불러오고 있어요.</p>}
      {(status === 'error' || status === 'not-found') && <div className={styles.status} role="alert">
        <p>{status === 'not-found' ? '단지를 찾을 수 없어요.' : '단지 정보를 불러오지 못했어요.'}</p>
        {status === 'error' && <button type="button" onClick={onRetry}>다시 시도</button>}
      </div>}
    </div>
    </>}
  </section>
}
