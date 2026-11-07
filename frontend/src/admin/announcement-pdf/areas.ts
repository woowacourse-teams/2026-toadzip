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

/** 같은 주택형 면적인지. 둘 다 있어야 하고 소수 둘째 자리(HALF_UP)까지 같아야 한다. */
export function sameArea(left: unknown, right: unknown) {
  const a = areaKey(left), b = areaKey(right)
  return a !== null && a === b
}

const typeName = (value: string | null | undefined) => value?.replace(/\s+/g, '').replace(/형$/, '').toLowerCase() || null

/**
 * 같은 단지에서 이 전용면적에 연결될 주택형. 서버(HousingTypeAreas.pick)와 같이 같은 면적 주택형 중 이름이 같은 것,
 * 없으면 먼저 만든(id가 작은) 것이다. 같은 면적 주택형이 없으면 null이고, 저장하면 서버가 새로 만든다.
 */
export function areaHousingType(types: MatchingHousingType[], exclusiveArea: unknown, name?: string | null): MatchingHousingType | null {
  const same = types.filter(type => sameArea(type.exclusiveArea, exclusiveArea)).sort((left, right) => left.id - right.id)
  const wanted = typeName(name)
  return same.find(type => wanted !== null && typeName(type.name) === wanted) ?? same[0] ?? null
}
