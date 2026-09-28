import type { HousingAnnouncementDetailSchedule } from '../components/HousingAnnouncementDetailPanel'
import type { AnnouncementApplicationSchedule } from '../model/publicHousing'

export function displayAnnouncementSchedules(schedules: readonly HousingAnnouncementDetailSchedule[]) {
  const seen = new Set<string>()
  return schedules.filter((schedule) => {
    const key = JSON.stringify([schedule.type, schedule.name, schedule.startAt, schedule.endAt])
    if (seen.has(key)) return false
    seen.add(key)
    return true
  }).sort((a, b) => compareDates(a.startAt ?? a.endAt, b.startAt ?? b.endAt))
}

export function displayApplicationSchedules(schedules: readonly AnnouncementApplicationSchedule[]) {
  const seen = new Set<string>()
  return schedules.filter((schedule) => {
    // 같은 날짜여도 대상·조건·출처가 다르면 별도 일정으로 보존한다.
    const key = JSON.stringify([
      schedule.housingComplexId, schedule.complexName, schedule.supplyRank,
      schedule.state, schedule.condition, schedule.startDate, schedule.startTime,
      schedule.endDate, schedule.endTime, schedule.sourceUrl, schedule.sourcePage,
    ])
    if (seen.has(key)) return false
    seen.add(key)
    return true
  }).sort((a, b) => compareDates(
    `${a.startDate}T${a.startTime ?? ''}`, `${b.startDate}T${b.startTime ?? ''}`,
  ))
}

function compareDates(a: string | null, b: string | null): number {
  if (a === b) return 0
  if (a === null) return 1
  if (b === null) return -1
  return a < b ? -1 : 1
}
