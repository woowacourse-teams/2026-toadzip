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

it('주택타입 열이 없어 신청형 이름으로 잇는 행은 이름이 같은 주택타입의 면적이 다르고 면적이 같은 주택타입이 따로 있으면 그쪽에 잇는다', () => {
  // 평택송화: 공고문 병합 칸 '46'이 45.79㎡(13호)·46.22㎡(253호) 두 줄을 덮는다. 단지는 45(45.79)·46(46.22)로 나뉘어 있다.
  const types = [{ id: 4216, name: '45', exclusiveArea: 45.79 }, { id: 4217, name: '46', exclusiveArea: 46.22 }] as never
  expect(announcementHousingType(types, '46', 45.79, [], false)?.id).toBe(4216)
  // 주택타입 열에 적힌 이름이면 공고문 이름이 우선이다(단지 주택타입 이름 기준).
  expect(announcementHousingType(types, '46', 45.79, [], true)?.id).toBe(4217)
  // 면적이 같은 다른 주택타입이 없으면(면적만 조금 다름) 지금처럼 이름이 같은 주택타입에 잇는다.
  expect(announcementHousingType(types, '46', 46.21, [], false)?.id).toBe(4217)
})
