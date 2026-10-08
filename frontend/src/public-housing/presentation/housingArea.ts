import { MISSING_DATA_LABEL } from './missingData'

export type AreaUnit = 'sqm' | 'pyeong'

export function formatHousingArea(value: number | null, unit: AreaUnit, missingLabel = MISSING_DATA_LABEL) {
  if (value === null || !Number.isFinite(value)) return missingLabel
  // 원본 ㎡에서 매번 계산해 왕복 전환 시 반올림 오차가 누적되지 않도록 한다.
  const area = unit === 'pyeong' ? value * 0.3025 : value
  return `${area.toLocaleString('ko-KR', { maximumFractionDigits: 2 })}${unit === 'pyeong' ? '평' : '㎡'}`
}
