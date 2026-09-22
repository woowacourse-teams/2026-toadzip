import { MISSING_DATA_LABEL } from '../presentation/missingData.ts'
import { formatHousingMoney } from '../presentation/housingMoney.ts'
import { useRef, useState, type FocusEvent } from 'react'
import { AnnouncementStatusBadge } from './AnnouncementStatusBadge'
import styles from './HousingComplexCard.module.css'

export interface HousingComplexCardAnnouncement {
  readonly announcementId: string
  readonly applicationStatus: string
  readonly applicationEndAt: string | null
  readonly dDay: number | null
}

export interface HousingComplexCardData {
  readonly agencyCode: string | null
  readonly agencyName: string
  readonly complexId: string
  readonly depositMax: number | null
  readonly depositMin: number | null
  readonly exclusiveAreaMax: number | null
  readonly exclusiveAreaMin: number | null
  readonly monthlyRentMax: number | null
  readonly monthlyRentMin: number | null
  readonly name: string
  readonly regionName: string
  readonly rentalTypeLabel: string
  readonly representativeAnnouncement: HousingComplexCardAnnouncement | null
  readonly thumbnailImageUrl: string | null
}

export interface HousingComplexCardProps {
  readonly complex: HousingComplexCardData
  readonly selected?: boolean
  readonly hovered?: boolean
  readonly cardRef?: (node: HTMLElement | null) => void
  readonly onSelect: (complexId: string) => void
  readonly onHover?: (complexId: string | null) => void
  readonly onOpenAnnouncement?: (announcementId: string) => void
}

interface RangePresentation {
  readonly accessible: string
  readonly visible: string
}

export function HousingComplexCard({
  complex,
  selected = false,
  hovered = false,
  cardRef,
  onSelect,
  onHover,
  onOpenAnnouncement,
}: HousingComplexCardProps) {
  const titleId = `housing-complex-card-title-${complex.complexId}`
  const announcement = complex.representativeAnnouncement?.applicationStatus === 'CLOSED'
    ? null
    : complex.representativeAnnouncement
  const safeImageUrl = safeHttpUrl(complex.thumbnailImageUrl)
  const [imageLoadState, setImageLoadState] = useState<{
    readonly failed: boolean
    readonly url: string | null
  }>(() => ({ failed: false, url: safeImageUrl }))
  if (imageLoadState.url !== safeImageUrl) {
    setImageLoadState({ failed: false, url: safeImageUrl })
  }
  const hasImage = safeImageUrl !== null && !(
    imageLoadState.url === safeImageUrl && imageLoadState.failed
  )
  const focusInsideRef = useRef(false)
  const pointerInsideRef = useRef(false)
  const cardClassName = [
    styles.card,
    selected ? styles.selected : '',
    hovered ? styles.hovered : '',
  ].filter(Boolean).join(' ')

  function updateHover() {
    const active = focusInsideRef.current || pointerInsideRef.current
    onHover?.(active ? complex.complexId : null)
  }

  function handleBlur(event: FocusEvent<HTMLElement>) {
    if (event.currentTarget.contains(event.relatedTarget)) {
      return
    }
    focusInsideRef.current = false
    updateHover()
  }

  function handleFocus() {
    focusInsideRef.current = true
    updateHover()
  }

  function handleMouseEnter() {
    pointerInsideRef.current = true
    updateHover()
  }

  function handleMouseLeave() {
    pointerInsideRef.current = false
    updateHover()
  }

  return (
    <article
      ref={cardRef}
      className={cardClassName}
      aria-current={selected ? 'true' : undefined}
      aria-labelledby={titleId}
      data-has-announcement={announcement !== null ? 'true' : 'false'}
      data-has-image={hasImage ? 'true' : 'false'}
      data-hovered={hovered || undefined}
      onMouseEnter={handleMouseEnter}
      onMouseLeave={handleMouseLeave}
      onFocus={handleFocus}
      onBlur={handleBlur}
    >
      <button
        type="button"
        className={styles.primaryAction}
        aria-label={`${complex.name} 단지 상세 보기`}
        data-complex-detail-trigger={complex.complexId}
        onClick={() => onSelect(complex.complexId)}
      />

      <div className={styles.cardContent}>
        {announcement ? (
          <div className={styles.topRow}>
            <RepresentativeAnnouncement
              announcement={announcement}
              descriptionId={`${titleId}-announcement-description`}
              onOpenAnnouncement={onOpenAnnouncement}
            />
            <AgencyMeta complex={complex} />
          </div>
        ) : (
          <header className={styles.titleRow} data-card-row="title">
            <h3 id={titleId}>{complex.name}</h3>
            <AgencyMeta complex={complex} />
          </header>
        )}

        <div className={styles.bodyGrid}>
          <div className={styles.summary}>
            {announcement && (
              <header className={styles.titleRow} data-card-row="title">
                <h3 id={titleId}>{complex.name}</h3>
              </header>
            )}

            <p className={styles.region} data-card-row="context">
              {complex.regionName}
            </p>

            <ComplexConditions complex={complex} />
          </div>

          {hasImage && (
            <img
              className={styles.image}
              src={safeImageUrl}
              alt={`${complex.name} 단지 대표 이미지`}
              loading="lazy"
              decoding="async"
              onError={() => setImageLoadState((current) => (
                current.url === safeImageUrl && current.failed
                  ? current
                  : { failed: true, url: safeImageUrl }
              ))}
            />
          )}
        </div>
      </div>
    </article>
  )
}

function AgencyMeta({ complex }: { complex: HousingComplexCardData }) {
  const normalizedCode = complex.agencyCode?.trim().toUpperCase() || null
  const agencyLabel = normalizedCode ?? complex.agencyName
  const bothMissing = agencyLabel === MISSING_DATA_LABEL
    && complex.rentalTypeLabel === MISSING_DATA_LABEL

  return (
    <p
      className={styles.agencyMeta}
      aria-label={`공급기관 ${agencyLabel}, 임대유형 ${complex.rentalTypeLabel}`}
    >
      <strong data-agency={agencyTone(normalizedCode)}>{agencyLabel}</strong>
      {!bothMissing && <>
        <i aria-hidden="true">·</i>
        <span>{complex.rentalTypeLabel}</span>
      </>}
    </p>
  )
}

function ComplexConditions({ complex }: { complex: HousingComplexCardData }) {
  const deposit = formatRange(
    complex.depositMin,
    complex.depositMax,
    formatHousingMoney,
  )
  const monthlyRent = formatRange(
    complex.monthlyRentMin,
    complex.monthlyRentMax,
    formatHousingMoney,
  )
  const area = formatRange(
    complex.exclusiveAreaMin,
    complex.exclusiveAreaMax,
    formatArea,
  )

  return (
    <dl
      className={styles.conditions}
      data-card-row="conditions"
      role="group"
      aria-label="주요 임대 조건"
    >
      <ComplexMetric label="임대보증금" value={deposit} />
      <ComplexMetric label="월 임대료" value={monthlyRent} />
      <ComplexMetric className={styles.areaMetric} label="전용" value={area} />
    </dl>
  )
}

function ComplexMetric({
  className,
  label,
  value,
}: {
  readonly className?: string
  readonly label: string
  readonly value: RangePresentation
}) {
  return (
    <div className={className}>
      <dt>{label}</dt>
      <dd aria-label={`${label} ${value.accessible}`}>{value.visible}</dd>
    </div>
  )
}

function RepresentativeAnnouncement({
  announcement,
  descriptionId,
  onOpenAnnouncement,
}: {
  readonly announcement: HousingComplexCardAnnouncement
  readonly descriptionId: string
  readonly onOpenAnnouncement?: (announcementId: string) => void
}) {
  const status = statusLabel(announcement.applicationStatus)
  const countdown = countdownPresentation(announcement)
  const description = countdown
    ? `대표 공고 상태 ${status}, ${countdown.accessible}`
    : `대표 공고 상태 ${status}`
  const content = (
    <AnnouncementStatusBadge
      label={status}
      tone={statusTone(announcement.applicationStatus)}
      countdown={countdown}
    />
  )

  return (
    <section
      className={styles.announcement}
      data-card-row="announcement"
      data-status={statusTone(announcement.applicationStatus)}
      data-urgency={isUrgent(announcement) ? 'urgent' : undefined}
      role="group"
      aria-label="대표 공고"
    >
      {onOpenAnnouncement ? (
        <button
          type="button"
          className={styles.announcementAction}
          aria-label="대표 공고 상세 보기"
          aria-describedby={descriptionId}
          data-representative-announcement-detail-trigger={
            announcement.announcementId
          }
          onClick={() => onOpenAnnouncement(announcement.announcementId)}
        >
          {content}
          <span id={descriptionId} className={styles.visuallyHidden}>
            {description}
          </span>
        </button>
      ) : (
        content
      )}
    </section>
  )
}

function formatRange(
  minimum: number | null,
  maximum: number | null,
  formatter: (value: number) => string,
): RangePresentation {
  const values = [minimum, maximum].filter(isValidRangeValue)
  if (values.length === 0) {
    return { accessible: MISSING_DATA_LABEL, visible: MISSING_DATA_LABEL }
  }

  const first = formatter(values[0])
  if (values.length === 1 || values[0] === values[1]) {
    return { accessible: first, visible: first }
  }

  const last = formatter(values[1])
  return {
    accessible: `${first}부터 ${last}까지`,
    visible: `${first} ~`,
  }
}

function isValidRangeValue(value: number | null): value is number {
  return value !== null && Number.isFinite(value) && value >= 0
}

function formatArea(value: number) {
  return `${value.toLocaleString('ko-KR', { maximumFractionDigits: 2 })}m²`
}

function statusLabel(status: string) {
  if (status === 'BEFORE_APPLICATION') {
    return '공고중'
  }
  if (status === 'APPLYING') {
    return '접수중'
  }
  if (status === 'CLOSED') {
    return '접수마감'
  }
  if (status === 'CANCELLED') {
    return '공고취소'
  }
  return MISSING_DATA_LABEL
}

function statusTone(status: string) {
  if (status === 'BEFORE_APPLICATION') {
    return 'upcoming'
  }
  if (status === 'APPLYING') {
    return 'applying'
  }
  if (status === 'CLOSED') {
    return 'closed'
  }
  if (status === 'CANCELLED') {
    return 'cancelled'
  }
  return 'unknown'
}

function countdownPresentation(announcement: HousingComplexCardAnnouncement) {
  if (
    announcement.applicationStatus === 'CLOSED'
    || announcement.applicationStatus === 'CANCELLED'
  ) {
    return null
  }
  if (
    announcement.dDay === null
    || !Number.isInteger(announcement.dDay)
    || announcement.dDay < 0
  ) {
    if (statusLabel(announcement.applicationStatus) === MISSING_DATA_LABEL) {
      return null
    }
    return {
      accessible: `접수 마감일 ${MISSING_DATA_LABEL}`,
      visible: MISSING_DATA_LABEL,
    }
  }
  return {
    accessible: `접수 마감까지 ${announcement.dDay}일`,
    visible: countdownLabel(announcement.applicationStatus, announcement.dDay),
  }
}

function countdownLabel(status: string, dDay: number) {
  const value = dDay === 0 ? 'D-Day' : `D-${dDay}`
  return status === 'BEFORE_APPLICATION' ? `마감 ${value}` : value
}

function isUrgent(announcement: HousingComplexCardAnnouncement) {
  if (announcement.applicationStatus !== 'APPLYING') {
    return false
  }
  if (announcement.dDay === null) {
    return false
  }
  return announcement.dDay >= 0 && announcement.dDay <= 3
}

function agencyTone(code: string | null) {
  if (code === 'LH' || code === 'SH' || code === 'GH') {
    return code
  }
  return 'unknown'
}

function safeHttpUrl(value: string | null) {
  if (!value) {
    return null
  }
  try {
    const url = new URL(value)
    if (url.protocol === 'http:' || url.protocol === 'https:') {
      return url.href
    }
  } catch {
    return null
  }
  return null
}
