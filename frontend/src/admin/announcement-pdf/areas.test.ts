import { expect, it } from 'vitest'
import { areaHousingType, areaKey, sameArea } from './areas'

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

it('같은 면적 주택형이 여럿이면 서버와 같이 이름이 같은 주택형, 없으면 먼저 만든 주택형을 고르고, 없으면 null이다', () => {
  const type = (id: number, name: string, exclusiveArea: number) => ({ id, name, exclusiveArea, supplyArea: null })
  const types = [type(8576, '26B', 26.37), type(8575, '26', 26.37), type(8577, '31', 31.32)]
  expect(areaHousingType(types, 26.3700, '26B 형')?.id).toBe(8576)
  expect(areaHousingType(types, 26.37, '26C')?.id).toBe(8575)
  expect(areaHousingType(types, 39.5, '39')).toBeNull()
})
