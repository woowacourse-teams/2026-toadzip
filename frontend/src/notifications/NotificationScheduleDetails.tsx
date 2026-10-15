import type { NotificationSchedule } from './notificationRepository'
import styles from './NotificationInbox.module.css'

function dateTime(date: string, time: string | null) {
  const [year, month, day] = date.split('-')
  return `${year}. ${Number(month)}. ${Number(day)}.${time ? ` ${time.slice(0, 5)}` : ''}`
}
export function NotificationScheduleDetails({ schedule }: { readonly schedule: NotificationSchedule }) {
  const start = dateTime(schedule.startDate, schedule.startTime)
  const end = dateTime(schedule.endDate, schedule.endTime)
  return <div className={styles.scheduleDetails}>
    <p className={styles.scheduleName}>{schedule.name}</p>
    <dl>
      <div><dt>기간</dt><dd>{start === end ? start : `${start} – ${end}`}<span className={styles.timezone}>한국 시간{!schedule.startTime || !schedule.endTime ? ' · 미정 시간은 원문에서 확인' : ''}</span></dd></div>
      {schedule.scope && <div><dt>대상</dt><dd>{schedule.scope}</dd></div>}
      {schedule.condition && <div><dt>조건</dt><dd>{schedule.condition}</dd></div>}
    </dl>
    {schedule.sourceUrl && <a className={styles.sourceLink} href={schedule.sourceUrl} target="_blank" rel="noopener noreferrer">원문 확인{schedule.sourcePage ? ` · ${schedule.sourcePage}쪽` : ''}</a>}
  </div>
}
