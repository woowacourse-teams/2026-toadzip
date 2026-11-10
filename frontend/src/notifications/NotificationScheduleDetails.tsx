import { buttonClassName } from '../design-system/components/buttonClassName'
import type { NotificationSchedule } from './notificationRepository'
import styles from './NotificationInbox.module.css'

const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토']

function day(date: string, withYear: boolean) {
  const [year, month, dayOfMonth] = date.split('-').map(Number)
  const weekday = WEEKDAYS[new Date(Date.UTC(year, month - 1, dayOfMonth)).getUTCDay()]
  return `${withYear ? `${year}년 ` : ''}${month}월 ${dayOfMonth}일(${weekday})`
}

function time(value: string | null) {
  return value ? ` ${value.slice(0, 5)}` : ''
}

/** 같은 날이면 날짜를 한 번만, 같은 해면 끝 날짜의 연도를 줄여 쓴다. */
function schedulePeriod(schedule: NotificationSchedule) {
  const start = `${day(schedule.startDate, true)}${time(schedule.startTime)}`
  if (schedule.startDate === schedule.endDate) {
    return schedule.endTime && schedule.endTime !== schedule.startTime ? `${start} – ${schedule.endTime.slice(0, 5)}` : start
  }
  const sameYear = schedule.startDate.slice(0, 4) === schedule.endDate.slice(0, 4)
  return `${start} – ${day(schedule.endDate, !sameYear)}${time(schedule.endTime)}`
}

export function NotificationScheduleDetails({ schedule, onOpen, disabled = false }: {
  readonly schedule: NotificationSchedule
  /** 앱의 공고 상세로 이동. 없으면 버튼을 두지 않는다(확인할 수 없는 공고). */
  readonly onOpen?: () => void
  readonly disabled?: boolean
}) {
  return <div className={styles.scheduleDetails}>
    <div className={styles.scheduleHead}>
      <p className={styles.scheduleName}>{schedule.name}</p>
      {onOpen && <button type="button" className={`${buttonClassName({ variant: 'secondary', size: 'sm' })} ${styles.sourceButton}`} disabled={disabled} onClick={onOpen}>공고 보기</button>}
    </div>
    <p className={styles.period}>{schedulePeriod(schedule)}</p>
    <p className={styles.timezone}>한국 시간{!schedule.startTime || !schedule.endTime ? ' · 미정 시간은 원문에서 확인' : ''}</p>
    {(schedule.scope || schedule.condition) && <dl className={styles.scheduleMeta}>
      {schedule.scope && <div><dt>대상</dt><dd>{schedule.scope}</dd></div>}
      {schedule.condition && <div><dt>조건</dt><dd>{schedule.condition}</dd></div>}
    </dl>}
  </div>
}
