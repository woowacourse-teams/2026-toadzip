import type { HousingAnnouncementDetailSchedule } from '../components/HousingAnnouncementDetailPanel'
import type { AnnouncementApplicationSchedule } from '../model/publicHousing'
import { MISSING_DATA_LABEL } from './missingData'

export interface ScheduleTarget {
  readonly key: string
  readonly name: string
  readonly scope: 'complex' | 'common' | 'unknown'
}

interface ScheduleGroup<T> {
  readonly key: string
  readonly name: string
  readonly targets: readonly ScheduleTarget[]
  readonly schedules: T[]
}

/**
 * 단지가 적힌 일정이 하나도 없으면 일정은 공고 전체의 것이다. 단지가 하나뿐인 공고는 그 단지 이름을, 여럿이면 '전체 단지 공통'을
 * 제목으로 쓴다. 일부 일정에만 단지가 적혀 있거나 공급 단지를 모르면 단지가 빠진 일정의 대상을 알 수 없어 '공고문 확인'으로 둔다.
 */
export function groupAnnouncementSchedules(
  schedules: readonly HousingAnnouncementDetailSchedule[],
  complexes: readonly { name: string }[],
) {
  const anyComplex = schedules.some((schedule) => schedule.complexName?.trim())
  const onlyComplex = complexes.length === 1 && complexes[0].name.trim() !== MISSING_DATA_LABEL ? complexes[0].name.trim() : ''
  const unnamed: ScheduleTarget = anyComplex || complexes.length === 0
    ? { key: 'unknown', name: `대상 단지: ${MISSING_DATA_LABEL}`, scope: 'unknown' }
    : onlyComplex
      ? { key: onlyComplex, name: onlyComplex, scope: 'complex' }
      : { key: 'common', name: '전체 단지 공통', scope: 'common' }
  return groupSchedules(
    [...schedules].sort((a, b) => compareDates(a.startAt ?? a.endAt, b.startAt ?? b.endAt)),
    (schedule) => JSON.stringify([schedule.type, schedule.name, schedule.startAt, schedule.endAt]),
    (schedule) => {
      const name = schedule.complexName?.trim()
      return name ? { key: name, name, scope: 'complex' } : unnamed
    },
  )
}

export function groupApplicationSchedules(
  schedules: readonly AnnouncementApplicationSchedule[],
  complexes: readonly { complexId: string | null; name: string }[],
) {
  return groupSchedules(
    [...schedules].sort((a, b) => compareDates(
      `${a.startDate}T${a.startTime ?? ''}`, `${b.startDate}T${b.startTime ?? ''}`,
    )),
    (schedule) => JSON.stringify([
      schedule.supplyRank, schedule.state, schedule.condition,
      schedule.startDate, schedule.startTime, schedule.endDate, schedule.endTime,
      schedule.sourceUrl, schedule.sourcePage,
    ]),
    (schedule) => {
      const complexId = schedule.housingComplexId
      const name = schedule.complexName?.trim()
        || (complexId !== null ? complexes.find((complex) => complex.complexId === complexId)?.name.trim() : null)
      if (complexId === null && !name) {
        return { key: 'common', name: '전체 단지 공통', scope: 'common' }
      }
      return {
        key: JSON.stringify([complexId, complexId === null ? name : null]),
        name: name || `단지 정보: ${MISSING_DATA_LABEL}`,
        scope: name ? 'complex' : 'unknown',
      }
    },
  )
}

// 같은 일정은 대상 목록을 합치고, 같은 대상 목록의 일정들을 한 제목 아래에 모은다.
// 대상 미확인/전체 공통은 특정 단지 일정과 합치지 않는다.
function groupSchedules<T>(
  schedules: readonly T[],
  contentKey: (schedule: T) => string,
  targetOf: (schedule: T) => ScheduleTarget,
): ScheduleGroup<T>[] {
  const unique = new Map<string, { schedule: T; targets: Map<string, ScheduleTarget> }>()
  for (const schedule of schedules) {
    const target = targetOf(schedule)
    const key = JSON.stringify([contentKey(schedule), target.scope,
      target.scope === 'unknown' ? target.key : null])
    const item = unique.get(key) ?? { schedule, targets: new Map<string, ScheduleTarget>() }
    item.targets.set(target.key, target)
    unique.set(key, item)
  }
  const groups = new Map<string, ScheduleGroup<T>>()
  for (const { schedule, targets } of unique.values()) {
    const key = JSON.stringify([...targets.values()].map((target) => [target.scope, target.key]).sort())
    const sortedTargets = [...targets.values()].sort((a, b) => a.name.localeCompare(b.name, 'ko'))
    const group = groups.get(key) ?? {
      key,
      name: sortedTargets.map((target) => target.name).join(' · '),
      targets: sortedTargets,
      schedules: [],
    }
    group.schedules.push(schedule)
    groups.set(key, group)
  }
  return [...groups.values()]
}

function compareDates(a: string | null, b: string | null): number {
  if (a === b) return 0
  if (a === null) return 1
  if (b === null) return -1
  return a < b ? -1 : 1
}
