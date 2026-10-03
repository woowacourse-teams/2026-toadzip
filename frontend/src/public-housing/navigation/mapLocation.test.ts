import { describe, expect, it } from 'vitest'

import {
  clearMapLocationQuery,
  DEFAULT_MAXIMUM_MAP_ZOOM,
  DEFAULT_MINIMUM_MAP_ZOOM,
  parseMapLocation,
  setMapLocationQuery,
} from './mapLocation.ts'

describe('parseMapLocation', () => {
  it('지도 query가 모두 없으면 absent로 해석한다', () => {
    expect(parseMapLocation(new URLSearchParams('tab=complex'))).toEqual({
      kind: 'absent',
    })
  })

  it('위치와 zoom이 각각 하나면 지도 위치로 해석한다', () => {
    expect(
      parseMapLocation(
        new URLSearchParams(
          'mapLat=37.56661&mapLng=126.97839&mapZoom=14.25',
        ),
      ),
    ).toEqual({
      kind: 'valid',
      center: { latitude: 37.56661, longitude: 126.97839 },
      zoom: 14.25,
    })
  })

  it.each([
    'mapLat=37.5',
    'mapLng=127&mapZoom=14',
    'mapLat=37.5&mapLng=127',
    'mapLat=37.5&mapLng=127&mapZoom=14&mapZoom=14',
    'mapLat=37.5&mapLat=37.5&mapLng=127&mapZoom=14',
  ])('일부만 있거나 중복된 지도 query %s는 invalid다', (query) => {
    expect(parseMapLocation(new URLSearchParams(query))).toEqual({
      kind: 'invalid',
    })
  })

  it.each([
    'mapLat=&mapLng=127&mapZoom=14',
    'mapLat=37.5&mapLng=127px&mapZoom=14',
    'mapLat=37.5&mapLng=127&mapZoom=NaN',
    'mapLat=37.5&mapLng=127&mapZoom=Infinity',
    'mapLat=37.5&mapLng=127&mapZoom=%2014',
  ])('숫자가 아닌 지도 query %s는 invalid다', (query) => {
    expect(parseMapLocation(new URLSearchParams(query))).toEqual({
      kind: 'invalid',
    })
  })

  it.each([
    `mapLat=-90&mapLng=-180&mapZoom=${DEFAULT_MINIMUM_MAP_ZOOM}`,
    `mapLat=90&mapLng=180&mapZoom=${DEFAULT_MAXIMUM_MAP_ZOOM}`,
  ])('좌표와 기본 zoom 범위의 경계값을 허용한다', (query) => {
    expect(parseMapLocation(new URLSearchParams(query)).kind).toBe('valid')
  })

  it.each([
    'mapLat=-90.00001&mapLng=127&mapZoom=14',
    'mapLat=90.00001&mapLng=127&mapZoom=14',
    'mapLat=37.5&mapLng=-180.00001&mapZoom=14',
    'mapLat=37.5&mapLng=180.00001&mapZoom=14',
    `mapLat=37.5&mapLng=127&mapZoom=${DEFAULT_MINIMUM_MAP_ZOOM - 0.01}`,
    `mapLat=37.5&mapLng=127&mapZoom=${DEFAULT_MAXIMUM_MAP_ZOOM + 0.01}`,
  ])('좌표 또는 기본 zoom 범위를 벗어난 %s는 invalid다', (query) => {
    expect(parseMapLocation(new URLSearchParams(query))).toEqual({
      kind: 'invalid',
    })
  })

  it('호출자가 SDK zoom 범위를 지정할 수 있다', () => {
    const searchParams = new URLSearchParams(
      'mapLat=37.5&mapLng=127&mapZoom=5.5',
    )

    expect(parseMapLocation(searchParams)).toEqual({ kind: 'invalid' })
    expect(
      parseMapLocation(searchParams, { minimumZoom: 5, maximumZoom: 22 }),
    ).toMatchObject({ kind: 'valid', zoom: 5.5 })
  })
})

describe('기존 지도 URL 소비', () => {
  it('기존 소수 좌표를 그대로 읽고 지도 query만 제거하며 다른 값과 원본을 보존한다', () => {
    const original = 'tab=map&filter=one&mapLat=37.5666103&filter=two&mapLng=126.9783882&mapZoom=14.256&empty='
    const current = new URLSearchParams(original)

    expect(parseMapLocation(current)).toEqual({
      kind: 'valid',
      center: { latitude: 37.5666103, longitude: 126.9783882 },
      zoom: 14.256,
    })
    const next = clearMapLocationQuery(current)
    expect(next.toString()).toBe('tab=map&filter=one&filter=two&empty=')
    expect(parseMapLocation(next)).toEqual({ kind: 'absent' })
    expect(current.toString()).toBe(original)
  })

  it('중복된 기존 지도 위치를 선택하지 않고 모든 지도 query를 제거한다', () => {
    const current = new URLSearchParams('mapLat=1&mapLat=2&tab=map&mapLng=3&mapLng=4&mapZoom=14&mapZoom=15')
    expect(parseMapLocation(current)).toEqual({ kind: 'invalid' })
    expect(clearMapLocationQuery(current).toString()).toBe('tab=map')
    expect(current.getAll('mapLat')).toEqual(['1', '2'])
  })

  it.each(['NaN', String(DEFAULT_MAXIMUM_MAP_ZOOM + 1)])('유효하지 않은 zoom %s는 카메라에 적용하지 않고 무관 query를 보존한다', (zoom) => {
    const current = new URLSearchParams(`complexId=7&mapLat=37.5&mapLng=127&mapZoom=${zoom}`)
    expect(parseMapLocation(current)).toEqual({ kind: 'invalid' })
    expect(clearMapLocationQuery(current).toString()).toBe('complexId=7')
  })
})

describe('setMapLocationQuery', () => {
  it('카메라만 갱신하고 검색 범위·필터와 원본 URL은 유지한다', () => {
    const original = new URLSearchParams('searchMode=region&complexRegionCode=11380&complexRentalTypes=HAPPY_HOUSING&mapLat=1&mapLat=2&mapLng=3&mapZoom=14')
    const next = setMapLocationQuery(original, { center: { latitude: 37.5666103, longitude: 126.9783882 }, zoom: 14.256 })
    expect(parseMapLocation(next)).toEqual({ kind: 'valid', center: { latitude: 37.56661, longitude: 126.97839 }, zoom: 14.26 })
    expect(next.get('searchMode')).toBe('region')
    expect(next.get('complexRegionCode')).toBe('11380')
    expect(next.get('complexRentalTypes')).toBe('HAPPY_HOUSING')
    expect(next.getAll('mapLat')).toEqual(['37.56661'])
    expect(original.getAll('mapLat')).toEqual(['1', '2'])
  })

  it('유효하지 않은 좌표나 줌을 저장하지 않는다', () => {
    expect(() => setMapLocationQuery(new URLSearchParams(), { center: { latitude: NaN, longitude: 127 }, zoom: 14 })).toThrow(TypeError)
    expect(() => setMapLocationQuery(new URLSearchParams(), { center: { latitude: 37, longitude: 127 }, zoom: 22 })).toThrow(TypeError)
  })
})

describe('clearMapLocationQuery', () => {
  it('모든 지도 query만 제거하고 관련 없는 query를 보존한다', () => {
    const current = new URLSearchParams(
      'mapLat=1&tab=map&mapLng=2&filter=one&mapZoom=3&mapLat=4&filter=two',
    )

    const next = clearMapLocationQuery(current)

    expect(next.toString()).toBe('tab=map&filter=one&filter=two')
    expect(current.toString()).toBe(
      'mapLat=1&tab=map&mapLng=2&filter=one&mapZoom=3&mapLat=4&filter=two',
    )
  })
})
