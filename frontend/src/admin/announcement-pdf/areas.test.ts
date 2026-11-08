import { expect, it } from 'vitest'
import { announcementHousingType, areaKey, namedHousingType, sameArea } from './areas'

it('전용면적은 서버와 같이 소수 둘째 자리 HALF_UP으로 비교하고, 부동소수 곱셈으로 어긋나는 값도 십진 표기대로 반올림한다', () => {
  expect(areaKey(26.375)).toBe(2638)
  expect(areaKey(1.005)).toBe(101)
  expect(areaKey(26.374)).toBe(2637)
  expect(areaKey(84.9600)).toBe(areaKey(84.96))
  expect(sameArea(84.9600, 84.96)).toBe(true)
  expect(sameArea(26.4673, 26.47)).toBe(true)
  expect(sameArea(26.4649, 26.47)).toBe(false)
  expect(sameArea(null, null)).toBe(false)
  expect(areaKey(-1)).toBeNull()
})

it('단지 주택타입은 공고문 표기 이름으로 고르고 면적이 같아도 이름이 다르면 고르지 않는다(서버 HousingTypeAreas.named)', () => {
  const type = (id: number, name: string, exclusiveArea: number) => ({ id, name, exclusiveArea, supplyArea: null })
  const types = [type(8576, '55C-1', 55.98), type(8575, '26', 26.37), type(8578, '26', 26.4)]
  expect(namedHousingType(types, '55D-1', 55.98)).toBeNull()
  expect(namedHousingType(types, '055C-1형 (주거약자)', 55.98)?.id).toBe(8576)
  // 이름이 같은 주택타입이 여럿이면 면적이 같은 것을 고른다.
  expect(namedHousingType(types, '26', 26.4)?.id).toBe(8578)
  expect(namedHousingType(types, '', 26.37)).toBeNull()
})

it('이름 형식만 다른 같은 집(단지 059.9100A ↔ 공고문 59A)은 같은 면적의 다른 공고문 주택타입(59C)이 있어도 이름으로 잇는다', () => {
  const type = (id: number, name: string, exclusiveArea: number) => ({ id, name, exclusiveArea, supplyArea: null })
  const types = [type(11, '059.9100A', 59.91)]
  expect(announcementHousingType(types, '59A', 59.91, [{ name: '59C', exclusiveArea: 59.91 }])?.id).toBe(11)
  expect(announcementHousingType(types, '59C', 59.91, [{ name: '59A', exclusiveArea: 59.91 }])).toBeNull()
})
