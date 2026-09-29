import { MISSING_DATA_LABEL } from '../presentation/missingData'
import styles from './ScheduleGroups.module.css'

interface Props {
  readonly startAt: string | null
  readonly endAt: string | null
  readonly hideMidnight?: boolean
}

export function SchedulePeriod({ startAt, endAt, hideMidnight = false }: Props) {
  const hasEnd = endAt !== null && startAt !== endAt
  return <dl className={styles.period} data-range={hasEnd || undefined}>
    <div>
      <dt>{hasEnd ? '시작' : '일정'}</dt>
      <dd><ScheduleDate value={startAt} hideMidnight={hideMidnight} /></dd>
    </div>
    {hasEnd && <div>
      <dt>종료</dt>
      <dd><ScheduleDate value={endAt} hideMidnight={hideMidnight} /></dd>
    </div>}
  </dl>
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
