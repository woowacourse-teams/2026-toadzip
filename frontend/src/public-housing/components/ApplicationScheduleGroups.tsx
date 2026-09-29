import type { AnnouncementApplicationSchedule } from '../model/publicHousing'
import { MISSING_DATA_LABEL } from '../presentation/missingData'
import { groupApplicationSchedules } from '../presentation/announcementSchedulePresentation'
import { ScheduleGroupCard } from './ScheduleGroupCard'
import { SchedulePeriod } from './SchedulePeriod'
import styles from './ScheduleGroups.module.css'

interface Props {
  readonly schedules: readonly AnnouncementApplicationSchedule[]
  readonly complexes: readonly { complexId: string | null; name: string }[]
}

export function ApplicationScheduleGroups({ schedules, complexes }: Props) {
  const groups = groupApplicationSchedules(schedules, complexes)

  return (
    <div className={styles.groups}>
      {groups.map((group) => (
        <ScheduleGroupCard key={group.key} name={group.name} targets={group.targets} caption="접수 일정">
          {group.schedules.map((schedule) => <ScheduleCard key={schedule.scheduleId} schedule={schedule} />)}
        </ScheduleGroupCard>
      ))}
    </div>
  )
}

function ScheduleCard({ schedule }: { schedule: AnnouncementApplicationSchedule }) {
  const confirmed = schedule.state === 'CONFIRMED'
  const conditional = schedule.state === 'CONDITIONAL'
  const sourceUrl = scheduleSourceUrl(schedule)
  return (
    <li className={styles.item}>
      <header className={styles.heading}>
        <h5>{schedule.supplyRank?.trim() || '공통 접수'}</h5>
        <span className={styles.state} data-confirmed={confirmed}>
          {confirmed ? '확정' : conditional ? '조건부' : MISSING_DATA_LABEL}
        </span>
      </header>
      {schedule.condition?.trim() && <p className={styles.condition}>{schedule.condition}</p>}
      <SchedulePeriod
        startAt={schedule.startTime ? `${schedule.startDate}T${schedule.startTime}` : schedule.startDate}
        endAt={schedule.endTime ? `${schedule.endDate}T${schedule.endTime}` : schedule.endDate}
      />
      {sourceUrl && <a className={styles.source} href={sourceUrl} target="_blank" rel="noreferrer">
        공고문 {schedule.sourcePage}쪽
      </a>}
    </li>
  )
}

function scheduleSourceUrl(schedule: AnnouncementApplicationSchedule): string | null {
  try {
    const url = new URL(schedule.sourceUrl)
    if (url.protocol !== 'https:' && url.protocol !== 'http:') {
      return null
    }
    url.hash = `page=${schedule.sourcePage}`
    return url.href
  } catch {
    return null
  }
}
