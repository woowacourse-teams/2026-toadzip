import { MISSING_DATA_LABEL } from '../presentation/missingData.ts'
import { AnnouncementStatusBadge } from './AnnouncementStatusBadge'
import styles from './HousingAnnouncementCard.module.css'

export interface HousingAnnouncementCardData {
  readonly announcementId: string
  readonly title: string | null
  readonly regionNames: readonly string[]
  readonly agencyLabel: string | null
  readonly rentalTypeLabel: string | null
  readonly recruitmentTypeLabel: string | null
  readonly applicationStatus: string | null
  readonly applicationStartAt: string | null
  readonly applicationEndAt: string | null
  readonly dDay: number | null
  readonly viewCount: number | null
  readonly supplyHouseholdCount: number | null
}

export interface HousingAnnouncementCardProps {
  readonly announcement: HousingAnnouncementCardData
  readonly cardRef?: (node: HTMLElement | null) => void
  readonly selected?: boolean
  readonly onSelect?: (announcementId: string) => void
}

interface StatusPresentation {
  readonly statusLabel: string
  readonly accessibleLabel: string
  readonly countdown: string | null
}

interface DateParts {
  readonly isoDate: string
  readonly year: string
  readonly month: string
  readonly day: string
}

export function HousingAnnouncementCard({
  announcement,
  cardRef,
  selected = false,
  onSelect,
}: HousingAnnouncementCardProps) {
  const title = displayText(announcement.title)
  const status = statusPresentation(announcement)
  const accessibleLabel = status.statusLabel === MISSING_DATA_LABEL
    ? `${title}, ${status.accessibleLabel}`
    : `${title}, ${status.statusLabel}, ${status.accessibleLabel}`
  const urgent = isUrgent(announcement)
  const className = [styles.card, selected ? styles.selected : '']
    .filter(Boolean)
    .join(' ')

  return (
    <article
      ref={cardRef}
      className={className}
      data-status={statusTone(announcement.applicationStatus)}
      data-urgency={urgent ? 'urgent' : undefined}
      data-interactive={onSelect ? 'true' : undefined}
      aria-current={selected ? 'true' : undefined}
      aria-label={accessibleLabel}
    >
      <div className={styles.summary} data-card-zone="summary">
        <header className={styles.statusRow} data-summary-row="status">
          <AnnouncementStatusBadge
            label={status.statusLabel}
            tone={statusTone(announcement.applicationStatus)}
            countdown={status.countdown === null ? null : {
              visible: status.countdown,
              accessible: status.accessibleLabel,
            }}
          />
          <span
            className={styles.agency}
            data-agency-tone={agencyTone(announcement.agencyLabel)}
          >
            {displayText(announcement.agencyLabel)}
          </span>
        </header>

        <header className={styles.titleRow} data-summary-row="title">
          <h3>{title}</h3>
        </header>

        <AnnouncementContext announcement={announcement} />

        <section
          className={styles.schedule}
          data-summary-row="schedule"
          role="group"
          aria-label="접수기간"
        >
          <ApplicationPeriod announcement={announcement} />
        </section>

        <AnnouncementFooter announcement={announcement} />
      </div>

      {onSelect && (
        <button
          className={styles.primaryAction}
          type="button"
          aria-label={`${title} 상세 보기`}
          data-announcement-detail-trigger={announcement.announcementId}
          onClick={() => onSelect(announcement.announcementId)}
        >
          <span className={styles.visuallyHidden}>상세 보기</span>
        </button>
      )}
    </article>
  )
}

function AnnouncementContext({
  announcement,
}: {
  announcement: HousingAnnouncementCardData
}) {
  const region = regionLabel(announcement.regionNames)
  const rentalType = displayText(announcement.rentalTypeLabel)
  const bothMissing = region === MISSING_DATA_LABEL && rentalType === MISSING_DATA_LABEL

  return (
    <p
      className={styles.context}
      data-summary-row="context"
      role={bothMissing ? 'group' : undefined}
      aria-label={bothMissing ? `지역 및 주택유형 ${MISSING_DATA_LABEL}` : undefined}
    >
      <span>{region}</span>
      {!bothMissing && <>
        {' '}
        <i aria-hidden="true">·</i>
        {' '}
        <span>{rentalType}</span>
      </>}
    </p>
  )
}

function ApplicationPeriod({
  announcement,
}: {
  announcement: HousingAnnouncementCardData
}) {
  const startDate = parseDate(announcement.applicationStartAt)
  const endDate = parseDate(announcement.applicationEndAt)
  const omitEndYear = startDate !== null
    && endDate !== null
    && startDate.year === endDate.year

  return (
    <dl className={styles.period} data-summary-group="period">
      <div className={styles.periodRow}>
        <dt>접수기간</dt>
        <dd className={styles.periodValues}>
          <DateValue date={startDate} omitYear={false} />
          {(startDate !== null || endDate !== null) && <>
            {' '}
            <i aria-hidden="true">~</i>
            {' '}
            <DateValue date={endDate} omitYear={omitEndYear} />
          </>}
        </dd>
      </div>
    </dl>
  )
}

function DateValue({
  date,
  omitYear,
}: {
  date: DateParts | null
  omitYear: boolean
}) {
  if (date === null) {
    return <b>{MISSING_DATA_LABEL}</b>
  }

  return (
    <time dateTime={date.isoDate}>
      {omitYear ? `${date.month}.${date.day}` : `${date.year}.${date.month}.${date.day}`}
    </time>
  )
}

function AnnouncementFooter({
  announcement,
}: {
  announcement: HousingAnnouncementCardData
}) {
  const recruitmentType = displayText(announcement.recruitmentTypeLabel)
  const supply = supplyLabel(announcement.supplyHouseholdCount)
  const viewCount = viewCountLabel(announcement.viewCount)
  const bothMissing = recruitmentType === MISSING_DATA_LABEL && supply === MISSING_DATA_LABEL

  return (
    <footer className={styles.footer} data-summary-row="footer">
      <p
        className={styles.supplySummary}
        role={bothMissing ? 'group' : undefined}
        aria-label={bothMissing ? `모집유형 및 공급 세대수 ${MISSING_DATA_LABEL}` : undefined}
      >
        <span>{recruitmentType}</span>
        {!bothMissing && <>
          {' '}
          <i aria-hidden="true">·</i>
          {' '}
          <span>{supply}</span>
        </>}
      </p>
      {viewCount !== null && <p className={styles.viewCount}>{viewCount}</p>}
    </footer>
  )
}

function statusPresentation(
  announcement: HousingAnnouncementCardData,
): StatusPresentation {
  if (announcement.applicationStatus === 'CLOSED') {
    return status('접수마감', '접수 마감 완료')
  }
  if (announcement.applicationStatus === 'CANCELLED') {
    return status('공고취소', '공고 취소')
  }
  if (announcement.applicationStatus === 'BEFORE_APPLICATION') {
    return activeStatus('공고중', announcement.dDay, true)
  }
  if (announcement.applicationStatus === 'APPLYING') {
    return activeStatus('접수중', announcement.dDay, false)
  }
  return status(MISSING_DATA_LABEL, `공고 상태 ${MISSING_DATA_LABEL}`)
}

function activeStatus(
  statusLabel: string,
  dDay: number | null,
  includeDeadlinePrefix: boolean,
): StatusPresentation {
  if (dDay === null || !Number.isInteger(dDay) || dDay < 0) {
    return status(statusLabel, `접수 마감일 ${MISSING_DATA_LABEL}`, MISSING_DATA_LABEL)
  }

  const dDayLabel = dDay === 0 ? 'D-Day' : `D-${dDay}`
  return status(
    statusLabel,
    dDay === 0 ? '접수 마감일 당일' : `접수 마감까지 ${dDay}일`,
    includeDeadlinePrefix ? `마감 ${dDayLabel}` : dDayLabel,
  )
}

function status(
  statusLabel: string,
  accessibleLabel: string,
  countdown: string | null = null,
): StatusPresentation {
  return { statusLabel, accessibleLabel, countdown }
}

function statusTone(status: string | null) {
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

function isUrgent(announcement: HousingAnnouncementCardData) {
  if (announcement.applicationStatus !== 'APPLYING' || announcement.dDay === null) {
    return false
  }
  return Number.isInteger(announcement.dDay)
    && announcement.dDay >= 0
    && announcement.dDay <= 3
}

function displayText(value: string | null) {
  if (value === null || value.trim().length === 0) {
    return MISSING_DATA_LABEL
  }
  return value
}

function regionLabel(regionNames: readonly string[]) {
  const labels = regionNames
    .map((region) => region.trim())
    .filter((region) => region.length > 0)
  if (labels.length === 0) {
    return MISSING_DATA_LABEL
  }
  if (labels.length === 1) {
    return labels[0]
  }
  return `${labels[0]} 외 ${labels.length - 1}개`
}

function parseDate(value: string | null): DateParts | null {
  if (value === null) {
    return null
  }

  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  if (match === null) {
    return null
  }

  const [, year, month, day] = match
  const parsed = new Date(Date.UTC(Number(year), Number(month) - 1, Number(day)))
  if (
    parsed.getUTCFullYear() !== Number(year)
    || parsed.getUTCMonth() + 1 !== Number(month)
    || parsed.getUTCDate() !== Number(day)
  ) {
    return null
  }

  return { isoDate: value, year, month, day }
}

function supplyLabel(value: number | null) {
  if (value === null || !Number.isInteger(value) || value < 0) {
    return MISSING_DATA_LABEL
  }
  return `공급 ${value.toLocaleString('ko-KR')}세대`
}

function viewCountLabel(value: number | null) {
  if (value === null || !Number.isInteger(value) || value < 0) {
    return null
  }
  return `조회 ${value.toLocaleString('ko-KR')}`
}

function agencyTone(value: string | null) {
  const code = value?.trim().toUpperCase()
  if (code === 'LH' || code === 'SH' || code === 'GH') {
    return code.toLowerCase()
  }
  return 'neutral'
}
