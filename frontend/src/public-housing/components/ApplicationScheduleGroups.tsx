import { useId } from 'react'
import type { AnnouncementApplicationSchedule } from '../model/publicHousing'
import { MISSING_DATA_LABEL } from '../presentation/missingData'
import { groupApplicationSchedules } from '../presentation/announcementSchedulePresentation'
import styles from './ApplicationScheduleGroups.module.css'

interface Props {
  readonly schedules: readonly AnnouncementApplicationSchedule[]
  readonly complexes: readonly { complexId: string | null; name: string }[]
}

export function ApplicationScheduleGroups({ schedules, complexes }: Props) {
  const id = useId()
  const groups = groupApplicationSchedules(schedules, complexes)

  return (
    <div className={styles.groups}>
      {groups.map(({ key, name, schedules: items }, index) => {
        const headingId = `${id}-group-${index}`
        return (
          <section key={key} aria-label={`${name} 접수 일정`}>
            <h4 id={headingId} className={styles.groupTitle}>{name}</h4>
            <ul className={styles.list} aria-labelledby={headingId}>
              {items.map((schedule) => <ScheduleCard key={schedule.scheduleId} schedule={schedule} />)}
            </ul>
          </section>
        )
      })}
    </div>
  )
}

function ScheduleCard({ schedule }: { schedule: AnnouncementApplicationSchedule }) {
  const confirmed = schedule.state === 'CONFIRMED'
  const conditional = schedule.state === 'CONDITIONAL'
  const sameDate = schedule.startDate === schedule.endDate && schedule.startTime === schedule.endTime
  const sourceUrl = scheduleSourceUrl(schedule)
  return (
    <li className={styles.card}>
      <header className={styles.heading}>
        <h5>{schedule.supplyRank?.trim() || '공통 접수'}</h5>
        <span className={styles.state} data-confirmed={confirmed}>
          {confirmed ? '확정' : conditional ? '조건부' : MISSING_DATA_LABEL}
        </span>
      </header>
      {schedule.condition?.trim() && <p className={styles.condition}>{schedule.condition}</p>}
      <dl className={styles.dates}>
        <div>
          <dt>{sameDate ? '접수일' : '시작'}</dt>
          <dd><ScheduleDate date={schedule.startDate} time={schedule.startTime} /></dd>
        </div>
        {!sameDate && <div>
          <dt>종료</dt>
          <dd><ScheduleDate date={schedule.endDate} time={schedule.endTime} /></dd>
        </div>}
      </dl>
      {sourceUrl && <a className={styles.source} href={sourceUrl} target="_blank" rel="noreferrer">
        공고문 {schedule.sourcePage}쪽
      </a>}
    </li>
  )
}

function ScheduleDate({ date, time }: { date: string; time: string | null }) {
  return <time dateTime={time ? `${date}T${time}` : date}>
    {date.replaceAll('-', '.')}{time ? ` ${time.slice(0, 5)}` : ''}
  </time>
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
