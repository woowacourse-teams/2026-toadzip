import { applicationTypeKey, housingTypeKey } from '../supply/applicationType'
import type { MatchingHousingType } from '../ingest/supplyMatchingApi'

/**
 * 전용면적 비교 키. 서버(HousingTypeAreas.key)와 같이 소수 둘째 자리에서 HALF_UP으로 반올림한 0.01㎡ 단위 정수다.
 * 부동소수 곱셈(1.005 * 100 = 100.49…)은 반올림이 어긋나므로 숫자의 십진 표기 그대로 자릿수를 잘라 반올림한다.
 */
export function areaKey(value: unknown): number | null {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) return null
  const written = String(value)
  if (/e/i.test(written)) return Math.round(value * 100)
  const [whole, fraction = ''] = written.split('.')
  return Number(whole) * 100 + Number(fraction.padEnd(2, '0').slice(0, 2)) + (Number(fraction[2] ?? '0') >= 5 ? 1 : 0)
}

/**
 * 같은 단지 주택타입 이름인지(서버 HousingTypeNames.sameType). 이름(applicationTypeKey)이 같으면 같고, 면적 숫자 형명만 맞춰야 같아지는
 * 이름('059.9100A' ↔ '59A')은 전용면적도 같을 때만 같다. 단지 주택타입은 공고문 표기 이름으로 나눈다(59B-1과 59D-1은 면적이 같아도 다르다).
 */
export function sameTypeName(left: string | null | undefined, right: string | null | undefined, leftArea?: unknown, rightArea?: unknown) {
  const key = applicationTypeKey(left)
  return key !== '' && (key === applicationTypeKey(right) || housingTypeKey(left) === housingTypeKey(right) && sameArea(leftArea, rightArea))
}

/** 같은 주택형 면적인지. 둘 다 있어야 하고 소수 둘째 자리(HALF_UP)까지 같아야 한다. */
export function sameArea(left: unknown, right: unknown) {
  const a = areaKey(left), b = areaKey(right)
  return a !== null && a === b
}

/**
 * 공고문(PDF) 보정이 연결할 단지 주택타입. 단지 주택타입은 공고문 표기 이름으로 나누므로(59B-1과 59D-1은 면적이 같아도 다르다)
 * 이름이 같은 것만 고르고, 여럿이면 면적이 같은 것, 그다음 먼저 만든 것이다(서버 HousingTypeAreas.named). 없으면 null이고 저장할 때 새로 만든다.
 */
export function namedHousingType(types: MatchingHousingType[], name: string | null | undefined, exclusiveArea: unknown): MatchingHousingType | null {
  const same = types.filter(type => sameTypeName(type.name, name, type.exclusiveArea, exclusiveArea))
  return same.sort((left, right) => Number(!sameArea(left.exclusiveArea, exclusiveArea)) - Number(!sameArea(right.exclusiveArea, exclusiveArea)) || left.id - right.id)[0] ?? null
}

/** 이 공고의 다른 공고문 주택타입(이름·전용면적). */
export type AnnouncementType = { name: string; exclusiveArea: unknown }
/**
 * 공고문(PDF) 행이 연결할 단지 주택타입. 이름이 같은 주택타입(namedHousingType), 없으면 같은 면적 주택타입이 하나뿐이고, 그 면적의 공고문
 * 주택타입이 이것 하나이며, 그 이름을 이 공고의 다른 주택타입(others)이 쓰지 않을 때 그것이다. 이름 형식만 다른 같은 집(단지 '26' ↔ 공고문
 * '26A')은 잇고, 공고문이 같은 면적에 따로 적은 주택타입(55C-1·55D-1, 36A·36B)은 잇지 않는다. 없으면 null이고 저장할 때 새로 만든다.
 */
export function announcementHousingType(types: MatchingHousingType[], name: string | null | undefined, exclusiveArea: unknown, others: AnnouncementType[] = []): MatchingHousingType | null {
  const named = namedHousingType(types, name, exclusiveArea)
  if (named) return named
  if (others.some(other => sameArea(other.exclusiveArea, exclusiveArea))) return null
  const free = types.filter(type => sameArea(type.exclusiveArea, exclusiveArea) && !others.some(other => sameTypeName(type.name, other.name, type.exclusiveArea, other.exclusiveArea)))
  return free.length === 1 ? free[0] : null
}
