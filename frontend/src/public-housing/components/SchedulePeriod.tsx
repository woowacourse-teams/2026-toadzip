import { MISSING_DATA_LABEL } from '../presentation/missingData'
import styles from './ScheduleGroups.module.css'

interface Props {
  readonly startAt: string | null
  readonly endAt: string | null
  readonly hideMidnight?: boolean
}

const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토']

/**
 * 세로 타임라인 한 줄의 날짜 부분.
 * 화면에는 왼쪽 날짜 열(월.일·요일)과 짧은 시간 범위만 보이고,
 * 화면 낭독기에는 시작·종료의 전체 날짜와 시각을 그대로 읽어 준다.
 */
export function SchedulePeriod({ startAt, endAt, hideMidnight = false }: Props) {
  const hasEnd = endAt !== null && startAt !== endAt
  const start = parseScheduleDate(startAt, hideMidnight)
  const end = hasEnd ? parseScheduleDate(endAt, hideMidnight) : null
  return <>
    <span className={styles.entryDate} aria-hidden="true">
      {start ? <>{start.monthDay}<small>{start.weekday}</small></> : <small>{MISSING_DATA_LABEL}</small>}
    </span>
    {compactRange(start, end) && <p className={styles.entryRange} aria-hidden="true">{compactRange(start, end)}</p>}
    <dl className={styles.period} data-range={hasEnd || undefined}>
      <div>
        <dt>{hasEnd ? '시작' : '일정'}</dt>
        <dd><ScheduleDate value={startAt} hideMidnight={hideMidnight} /></dd>
      </div>
      {hasEnd && <div>
        <dt>종료</dt>
        <dd><ScheduleDate value={endAt} hideMidnight={hideMidnight} /></dd>
      </div>}
    </dl>
  </>
}

interface ParsedDate {
  readonly date: string
  readonly monthDay: string
  readonly weekday: string
  readonly time: string | null
}

function parseScheduleDate(value: string | null, hideMidnight: boolean): ParsedDate | null {
  const date = value?.match(/^(\d{4})-(\d{2})-(\d{2})/)
  if (!date) return null
  const [, year, month, day] = date
  const time = value?.match(/T(\d{2}:\d{2})/)?.[1] ?? null
  const weekday = WEEKDAYS[new Date(Date.UTC(Number(year), Number(month) - 1, Number(day))).getUTCDay()] ?? ''
  return {
    date: `${year}-${month}-${day}`,
    monthDay: `${month}.${day}`,
    weekday,
    time: time && !(hideMidnight && time === '00:00') ? time : null,
  }
}

/** 같은 날이면 시각 범위만, 다른 날이면 끝나는 날을 짧게 보여 준다. */
function compactRange(start: ParsedDate | null, end: ParsedDate | null) {
  if (!start) return end ? `~ ${end.monthDay}(${end.weekday})${end.time ? ` ${end.time}` : ''}` : null
  if (!end) return start.time
  if (end.date === start.date) {
    if (start.time && end.time) return `${start.time} ~ ${end.time}`
    return start.time ?? (end.time ? `~ ${end.time}` : null)
  }
  return `${start.time ? `${start.time} ` : ''}~ ${end.monthDay}(${end.weekday})${end.time ? ` ${end.time}` : ''}`
}

function ScheduleDate({ value, hideMidnight }: { value: string | null; hideMidnight: boolean }) {
  const date = value?.match(/^(\d{4})-(\d{2})-(\d{2})/)
  if (!date) return <span className={styles.missingDate}>{MISSING_DATA_LABEL}</span>
  const time = value?.match(/T(\d{2}:\d{2})/)?.[1]
  const showTime = time && !(hideMidnight && time === '00:00')
  return <time dateTime={value ?? undefined}>
    {`${date[1]}.${date[2]}.${date[3]}${showTime ? `\n${time}` : ''}`}
  </time>
}
