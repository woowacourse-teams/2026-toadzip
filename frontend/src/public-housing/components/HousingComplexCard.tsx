import { useRef, type FocusEvent } from 'react'
import styles from './HousingComplexCard.module.css'

export interface HousingComplexCardAnnouncement {
  announcementId: string
  applicationStatus: string
  applicationEndAt: string | null
  dDay: number | null
}

export interface HousingComplexCardData {
  complexId: string
  name: string
  regionName: string
  agencyName: string
  rentalTypeLabel: string
  exclusiveAreaMin: number | null
  exclusiveAreaMax: number | null
  depositMin: number | null
  depositMax: number | null
  monthlyRentMin: number | null
  monthlyRentMax: number | null
  representativeAnnouncement: HousingComplexCardAnnouncement | null
}

export interface HousingComplexCardProps {
  complex: HousingComplexCardData
  selected?: boolean
  hovered?: boolean
  cardRef?: (node: HTMLElement | null) => void
  onSelect: (complexId: string) => void
  onHover?: (complexId: string | null) => void
  onOpenAnnouncement?: (announcementId: string) => void
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
  const announcement = complex.representativeAnnouncement
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

      <header className={styles.titleRow} data-card-row="title">
        <h3 id={titleId}>{complex.name}</h3>
      </header>

      <div className={styles.context} data-card-row="context">
        <span className={styles.region}>{complex.regionName}</span>
        <p>
          <strong>{complex.agencyName}</strong>
          <i aria-hidden="true">·</i>
          <strong>{complex.rentalTypeLabel}</strong>
        </p>
      </div>

      <dl
        className={styles.conditions}
        data-card-row="conditions"
        role="group"
        aria-label="주요 임대 조건"
      >
        <ComplexMetric
          className={styles.areaMetric}
          label="전용면적"
          value={formatRange(
            complex.exclusiveAreaMin,
            complex.exclusiveAreaMax,
            formatArea,
          )}
        />
        <ComplexMetric
          label="임대보증금"
          value={formatRange(complex.depositMin, complex.depositMax, formatMoney)}
        />
        <ComplexMetric
          label="월 임대료"
          value={formatRange(
            complex.monthlyRentMin,
            complex.monthlyRentMax,
            formatMoney,
          )}
        />
      </dl>

      {announcement && (
        <RepresentativeAnnouncement
          announcement={announcement}
          onOpenAnnouncement={onOpenAnnouncement}
        />
      )}
    </article>
  )
}

function ComplexMetric({
  className,
  label,
  value,
}: {
  className?: string
  label: string
  value: string
}) {
  return (
    <div className={className}>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  )
}

function RepresentativeAnnouncement({
  announcement,
  onOpenAnnouncement,
}: {
  announcement: HousingComplexCardAnnouncement
  onOpenAnnouncement?: (announcementId: string) => void
}) {
  const urgent = isUrgent(announcement)

  return (
    <section
      className={styles.announcement}
      data-card-row="announcement"
      data-status={statusTone(announcement.applicationStatus)}
      data-urgency={urgent ? 'urgent' : undefined}
      role="group"
      aria-label="대표 공고"
    >
      <AnnouncementMetric
        label="모집 상태"
        value={statusLabel(announcement.applicationStatus)}
        emphasized
      />
      <AnnouncementDate value={announcement.applicationEndAt} />
      <AnnouncementMetric
        label="마감까지"
        value={dDayLabel(announcement)}
        emphasized
      />
      {onOpenAnnouncement && (
        <button
          type="button"
          className={styles.announcementAction}
          aria-label="대표 공고 상세 보기"
          data-representative-announcement-detail-trigger={
            announcement.announcementId
          }
          onClick={() => onOpenAnnouncement(announcement.announcementId)}
        >
          공고 확인
        </button>
      )}
    </section>
  )
}

function AnnouncementMetric({
  label,
  value,
  emphasized = false,
}: {
  label: string
  value: string
  emphasized?: boolean
}) {
  return (
    <dl className={styles.announcementMetric}>
      <dt>{label}</dt>
      <dd className={emphasized ? styles.emphasized : undefined}>{value}</dd>
    </dl>
  )
}

function AnnouncementDate({ value }: { value: string | null }) {
  const formatted = formatDate(value)

  return (
    <dl className={styles.announcementMetric}>
      <dt>접수 마감일</dt>
      <dd>
        {value && formatted !== null ? (
          <time dateTime={value}>{formatted}</time>
        ) : (
          '정보 확인 중'
        )}
      </dd>
    </dl>
  )
}

function formatRange(
  minimum: number | null,
  maximum: number | null,
  formatter: (value: number) => string,
) {
  const values = [minimum, maximum].filter(isFiniteNumber)
  if (values.length === 0) {
    return '정보 확인 중'
  }
  if (values.length === 1 || values[0] === values[1]) {
    return formatter(values[0])
  }
  return `${formatter(values[0])} ~ ${formatter(values[1])}`
}

function isFiniteNumber(value: number | null): value is number {
  return value !== null && Number.isFinite(value)
}

function formatArea(value: number) {
  return `${value.toLocaleString('ko-KR', { maximumFractionDigits: 2 })}㎡`
}

function formatMoney(value: number) {
  const amountWon = Math.max(0, Math.round(value))
  if (amountWon === 0) {
    return '0원'
  }
  if (amountWon < 10_000) {
    return `${amountWon.toLocaleString('ko-KR')}원`
  }
  if (amountWon < 100_000_000) {
    return `${formatManWon(amountWon)}만 원`
  }

  const eokWon = Math.floor(amountWon / 100_000_000)
  const remainderWon = amountWon % 100_000_000
  if (remainderWon < 10_000) {
    return `${eokWon.toLocaleString('ko-KR')}억 원`
  }
  return `${eokWon.toLocaleString('ko-KR')}억 ${formatManWon(remainderWon)}만 원`
}

function formatManWon(value: number) {
  const manWon = value / 10_000
  return manWon.toLocaleString('ko-KR', {
    maximumFractionDigits: Number.isInteger(manWon) ? 0 : 1,
  })
}

function statusLabel(status: string) {
  if (status === 'BEFORE_APPLICATION') {
    return '모집예정'
  }
  if (status === 'APPLYING') {
    return '접수중'
  }
  if (status === 'CLOSED') {
    return '접수마감'
  }
  return '정보 확인 중'
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
  return 'unknown'
}

function dDayLabel(announcement: HousingComplexCardAnnouncement) {
  if (announcement.applicationStatus === 'CLOSED') {
    return '종료'
  }
  if (!Number.isInteger(announcement.dDay) || announcement.dDay === null) {
    return '정보 확인 중'
  }
  if (announcement.dDay < 0) {
    return '정보 확인 중'
  }
  return `D-${announcement.dDay}`
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

function formatDate(value: string | null) {
  if (value === null || !/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    return null
  }
  return value.replaceAll('-', '.')
}
