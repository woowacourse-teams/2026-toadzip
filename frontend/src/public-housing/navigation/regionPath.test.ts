import { expect, it } from 'vitest'
import { regionExplorerSearch, rentalTypeSlug } from './regionPath.ts'

it('공급 유형 값을 주소용 이름으로 바꾼다', () => {
  expect(rentalTypeSlug('NATIONAL_RENTAL')).toBe('national-rental')
  expect(rentalTypeSlug('PUBLIC_RENTAL_50Y')).toBe('public-rental-50y')
})

it('지역 페이지 주소를 지도 화면 필터로 바꾼다', () => {
  expect(regionExplorerSearch('28', undefined)).toBe('?complexRegionCode=28')
  expect(regionExplorerSearch('28200', 'national-rental')).toBe('?complexRegionCode=28200&complexRentalTypes=NATIONAL_RENTAL')
})

it.each([
  ['2820', undefined], ['abc', undefined], ['2820000000', undefined], ['28200', 'unknown'], ['28200', 'NATIONAL_RENTAL'],
])('알 수 없는 지역 %s·유형 %s는 null이다', (code, slug) => {
  expect(regionExplorerSearch(code, slug)).toBeNull()
})
