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

interface DeadlinePresentation {
  readonly label: string
  readonly value: string
  readonly accessibleLabel: string
}

export function HousingAnnouncementCard({
  announcement,
  cardRef,
  selected = false,
  onSelect,
}: HousingAnnouncementCardProps) {
  const title = displayText(announcement.title, '공고명 정보 확인 중')
  const status = applicationStatusLabel(announcement.applicationStatus)
  const deadline = deadlinePresentation(announcement)
  const accessibleLabel = cardAccessibleLabel(title, status, deadline)
  const urgent = isUrgent(announcement)
  const meta = viewCountLabel(announcement.viewCount)
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
        <header className={styles.titleRow} data-summary-row="title">
          <h3>{title}</h3>
        </header>

        <AnnouncementContext announcement={announcement} />

        <section
          className={styles.schedule}
          data-summary-row="schedule"
          data-priority="primary"
          role="group"
          aria-label="접수 일정"
        >
          <div className={styles.signal} data-schedule-row="status">
            {status !== null && (
              <div className={styles.statusGroup} data-status-kind="application">
                <span className={styles.status}>{status}</span>
              </div>
            )}
            <p className={styles.deadline} aria-label={deadline.accessibleLabel}>
              <span>{deadline.label}</span>
              <strong>{deadline.value}</strong>
            </p>
          </div>
          <ApplicationPeriod announcement={announcement} />
        </section>

        <dl
          className={styles.supply}
          data-summary-row="supply"
          data-summary-group="supply"
          data-priority="secondary"
        >
          <SupplyMetric
            label="공급 세대수"
            value={formatCount(announcement.supplyHouseholdCount, '세대')}
          />
        </dl>

        {meta && (
          <footer
            className={styles.footer}
            data-summary-row="meta"
            data-priority="secondary"
          >
            <p>{meta}</p>
          </footer>
        )}
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
  const agency = displayText(announcement.agencyLabel, '공사 정보 확인 중')
  const rentalType = displayText(
    announcement.rentalTypeLabel,
    '주택유형 정보 확인 중',
  )
  const recruitmentType = displayText(
    announcement.recruitmentTypeLabel,
    '모집유형 정보 확인 중',
  )

  return (
    <div
      className={styles.context}
      data-summary-row="context"
      data-context-layout="inline"
    >
      <span
        className={styles.region}
        data-context-group="region"
        aria-label={`지역 ${region}`}
      >
        {region}
      </span>
      <p
        className={styles.interest}
        data-context-group="interest"
        aria-label={`공사 ${agency}, 주택 유형 ${rentalType}, 모집 구분 ${recruitmentType}`}
      >
        <strong data-context-role="provider">{agency}</strong>
        <i aria-hidden="true">·</i>
        <strong data-context-role="housing-type">{rentalType}</strong>
        <i aria-hidden="true">·</i>
        <span data-context-role="recruitment">{recruitmentType}</span>
      </p>
    </div>
  )
}

function ApplicationPeriod({
  announcement,
}: {
  announcement: HousingAnnouncementCardData
}) {
  return (
    <dl
      className={styles.period}
      data-summary-group="period"
      data-schedule-row="period"
    >
      <div className={styles.periodRow}>
        <dt>접수기간</dt>
        <dd className={styles.periodValues}>
          <DateWithSuffix value={announcement.applicationStartAt} suffix="부터" />
          <DateWithSuffix value={announcement.applicationEndAt} suffix="까지" />
        </dd>
      </div>
    </dl>
  )
}

function DateWithSuffix({
  value,
  suffix,
}: {
  value: string | null
  suffix: string
}) {
  const formatted = formatDate(value)

  return (
    <span>
      {value !== null && formatted !== null ? (
        <time dateTime={value}>{formatted}</time>
      ) : (
        <b>정보 확인 중</b>
      )}
      <small>{suffix}</small>
    </span>
  )
}

function SupplyMetric({ label, value }: { label: string; value: string }) {
  return (
    <div className={styles.supplyMetric} data-emphasis="neutral">
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  )
}

function applicationStatusLabel(status: string | null) {
  if (status === 'BEFORE_APPLICATION') {
    return '접수예정'
  }
  if (status === 'APPLYING') {
    return '접수중'
  }
  if (status === 'CLOSED' || status === 'CANCELLED') {
    return null
  }
  return '정보 확인 중'
}

function cardAccessibleLabel(
  title: string,
  status: string | null,
  deadline: DeadlinePresentation,
) {
  if (status === null) {
    return `${title}, ${deadline.accessibleLabel}`
  }
  return `${title}, ${status}, ${deadline.accessibleLabel}`
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

function deadlinePresentation(
  announcement: HousingAnnouncementCardData,
): DeadlinePresentation {
  if (announcement.applicationStatus === 'CLOSED') {
    return deadline('접수', '종료', '접수 마감 완료')
  }
  if (announcement.applicationStatus === 'CANCELLED') {
    return deadline('공고', '취소', '공고 취소')
  }
  if (
    announcement.dDay === null
    || !Number.isInteger(announcement.dDay)
    || announcement.dDay < 0
  ) {
    return deadline('마감일', '확인 중', '접수 마감일 정보 확인 중')
  }
  return deadline(
    '접수 마감까지',
    `${announcement.dDay}일`,
    `접수 마감까지 ${announcement.dDay}일`,
  )
}

function deadline(label: string, value: string, accessibleLabel: string) {
  return { label, value, accessibleLabel }
}

function isUrgent(announcement: HousingAnnouncementCardData) {
  if (announcement.applicationStatus !== 'APPLYING' || announcement.dDay === null) {
    return false
  }
  return Number.isInteger(announcement.dDay)
    && announcement.dDay >= 0
    && announcement.dDay <= 3
}

function displayText(value: string | null, fallback: string) {
  if (value === null || value.trim().length === 0) {
    return fallback
  }
  return value
}

function regionLabel(regionNames: readonly string[]) {
  const labels = regionNames.filter((region) => region.trim().length > 0)
  if (labels.length === 0) {
    return '지역 정보 확인 중'
  }
  return labels.join(' · ')
}

function formatDate(value: string | null) {
  if (value === null || !/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    return null
  }
  return value.replaceAll('-', '.')
}

function formatCount(value: number | null, suffix: string) {
  if (value === null || !Number.isInteger(value) || value < 0) {
    return '정보 확인 중'
  }
  return `${value.toLocaleString('ko-KR')}${suffix}`
}

function viewCountLabel(value: number | null) {
  if (value === null || !Number.isInteger(value) || value < 0) {
    return null
  }
  return `조회 ${value.toLocaleString('ko-KR')}`
}
