import { expect, it } from 'vitest'
import { housingTypeLabel } from './housingTypeLabel'
import type { MatchingHousingType } from './supplyMatchingApi'

it('같은 이름·전용면적의 주택형은 공급면적으로 구분하고 그래도 같으면 번호를 붙인다', () => {
  const types: MatchingHousingType[] = [
    { id: 10111, name: '26', exclusiveArea: 26.37, supplyArea: 38.9756 },
    { id: 10112, name: '26', exclusiveArea: 26.37, supplyArea: 39.6011 },
    { id: 10120, name: '31', exclusiveArea: 31.32, supplyArea: null, householdCount: 298 },
    { id: 10121, name: '31', exclusiveArea: 31.32, supplyArea: null, householdCount: 298 },
  ]
  expect(types.map(type => housingTypeLabel(type, types))).toEqual([
    '26 · 전용 26.37㎡ · 공급 38.9756㎡',
    '26 · 전용 26.37㎡ · 공급 39.6011㎡',
    '31 · 전용 31.32㎡ · 298세대 · #10120',
    '31 · 전용 31.32㎡ · 298세대 · #10121',
  ])
})
