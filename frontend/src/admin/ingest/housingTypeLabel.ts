import type { MatchingHousingType } from './supplyMatchingApi'

/** 같은 이름·전용면적의 주택형이 여럿이면 공급면적으로, 그래도 같으면 번호로 구분해 고를 수 있게 한다. */
export function housingTypeLabel(type: MatchingHousingType, all: readonly MatchingHousingType[] = []) {
  const base = `${type.name} · 전용 ${type.exclusiveArea}㎡${type.supplyArea != null ? ` · 공급 ${type.supplyArea}㎡` : ''}${type.householdCount != null ? ` · ${type.householdCount}세대` : ''}`
  const twins = all.filter(other => other.id !== type.id && other.name === type.name && other.exclusiveArea === type.exclusiveArea
    && other.supplyArea === type.supplyArea && other.householdCount === type.householdCount)
  return twins.length ? `${base} · #${type.id}` : base
}
