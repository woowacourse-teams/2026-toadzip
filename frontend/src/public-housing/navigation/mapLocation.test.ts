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

describe('setMapLocationQuery', () => {
  it('관련 없는 query를 보존하고 지도 query를 5, 5, 2자리로 설정한다', () => {
    const current = new URLSearchParams(
      'tab=map&filter=one&mapLat=1&filter=two&mapLng=2&mapZoom=3&empty=',
    )

    const next = setMapLocationQuery(current, {
      center: { latitude: 37.5666103, longitude: 126.9783882 },
      zoom: 14.256,
    })

    expect(next.toString()).toBe(
      'tab=map&filter=one&filter=two&empty=&mapLat=37.56661&mapLng=126.97839&mapZoom=14.26',
    )
    expect(current.toString()).toBe(
      'tab=map&filter=one&mapLat=1&filter=two&mapLng=2&mapZoom=3&empty=',
    )
  })

  it('중복된 지도 query를 각각 하나의 정규 값으로 바꾼다', () => {
    const current = new URLSearchParams(
      'mapLat=1&mapLat=2&tab=map&mapLng=3&mapLng=4&mapZoom=5&mapZoom=6',
    )

    expect(
      setMapLocationQuery(current, {
        center: { latitude: -0, longitude: -0 },
        zoom: 14,
      }).toString(),
    ).toBe('tab=map&mapLat=0.00000&mapLng=0.00000&mapZoom=14.00')
  })

  it('유효하지 않은 지도 위치로 URL을 만들지 않는다', () => {
    expect(() =>
      setMapLocationQuery(new URLSearchParams(), {
        center: { latitude: Number.NaN, longitude: 127 },
        zoom: 14,
      }),
    ).toThrowError('유효한 지도 위치가 아닙니다.')

    expect(() =>
      setMapLocationQuery(new URLSearchParams(), {
        center: { latitude: 37.5, longitude: 127 },
        zoom: DEFAULT_MAXIMUM_MAP_ZOOM + 1,
      }),
    ).toThrowError('유효한 지도 위치가 아닙니다.')
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
