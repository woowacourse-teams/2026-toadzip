import { expect, it } from 'vitest'
import { matchesRegionPath, parseRegionPath, regionPath, rentalTypeSlug } from './regionPath.ts'
import { explorerPagePath, isInvalidExplorerPath, toExplorerQueryLocation, toPublicLocation } from './detailLocation.ts'

it('공급 유형 값을 주소용 이름으로 바꾼다', () => {
  expect(rentalTypeSlug('NATIONAL_RENTAL')).toBe('national-rental')
  expect(rentalTypeSlug('PUBLIC_RENTAL_50Y')).toBe('public-rental-50y')
  expect(regionPath('28200', 'NATIONAL_RENTAL')).toBe('/regions/28200/national-rental')
})

it('지역 주소에서 지역과 공급 유형을 읽고 모르는 값은 invalid로 본다', () => {
  expect(parseRegionPath('/regions/28')).toEqual({ kind: 'region', regionCode: '28', rentalType: null })
  expect(parseRegionPath('/regions/28200/national-rental'))
    .toEqual({ kind: 'region', regionCode: '28200', rentalType: 'NATIONAL_RENTAL' })
  for (const path of ['/regions/2820', '/regions/abc', '/regions/28200/unknown', '/regions/28200/NATIONAL_RENTAL']) {
    expect(parseRegionPath(path)).toEqual({ kind: 'invalid' })
    expect(isInvalidExplorerPath(path)).toBe(true)
  }
  expect(parseRegionPath('/explore')).toEqual({ kind: 'none' })
})

it('지역 주소는 지도 화면이고 같은 조건의 쿼리로 읽는다', () => {
  expect(explorerPagePath('/regions/28200/national-rental')).toBe('/explore')
  expect(explorerPagePath('/regions/abc')).toBe('/regions/abc')
  expect(toExplorerQueryLocation({ pathname: '/regions/28200/national-rental', search: '?mapZoom=12&complexRentalTypes=ETC' }))
    .toEqual({ pathname: '/explore', search: '?mapZoom=12&boundaryRegionCode=28200&complexRentalTypes=NATIONAL_RENTAL' })
})

it('지역 주소에서 같은 조건을 유지하는 이동은 지역 주소로 남기고 조건을 바꾸면 지도 주소로 넘어간다', () => {
  const current = { pathname: '/regions/28200/national-rental', search: '' }
  expect(toPublicLocation({ pathname: '/explore', search: '?boundaryRegionCode=28200&complexRentalTypes=NATIONAL_RENTAL&mapZoom=12' }, current))
    .toEqual({ pathname: '/regions/28200/national-rental', search: '?mapZoom=12' })
  expect(toPublicLocation({ pathname: '/explore', search: '?boundaryRegionCode=28200' }, current))
    .toEqual({ pathname: '/explore', search: '?boundaryRegionCode=28200' })
  expect(toPublicLocation({ pathname: '/explore', search: '?boundaryRegionCode=28200&complexRentalTypes=NATIONAL_RENTAL&complexId=7' }, current))
    .toEqual({ pathname: '/complexes/7', search: '?boundaryRegionCode=28200&complexRentalTypes=NATIONAL_RENTAL' })
  expect(toPublicLocation({ pathname: '/explore', search: '?boundaryRegionCode=28200' }, { pathname: '/explore', search: '' }))
    .toEqual({ pathname: '/explore', search: '?boundaryRegionCode=28200' })
})

it('지역 조건 일치는 지역 코드와 공급 유형 목록이 정확히 같을 때만이다', () => {
  const region = { regionCode: '28200', rentalType: null }
  expect(matchesRegionPath(new URLSearchParams('boundaryRegionCode=28200'), region)).toBe(true)
  expect(matchesRegionPath(new URLSearchParams('boundaryRegionCode=28200&complexRentalTypes=ETC'), region)).toBe(false)
  expect(matchesRegionPath(new URLSearchParams('boundaryRegionCode=28'), region)).toBe(false)
})
