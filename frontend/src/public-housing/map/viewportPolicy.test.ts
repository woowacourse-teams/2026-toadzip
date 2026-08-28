import { describe, expect, it } from 'vitest'

import {
  createBoundsSignature,
  evaluateViewportRequest,
} from './viewportPolicy.ts'
import type { MapBounds } from '../model/publicHousing.ts'

const allowedBounds: MapBounds = {
  southWestLat: 37,
  southWestLng: 126,
  northEastLat: 37.25,
  northEastLng: 126.35,
}
const center = { latitude: 37.125, longitude: 126.175 }

describe('evaluateViewportRequest', () => {
  it('zoom과 지도 범위가 허용 경계값이면 요청을 허용한다', () => {
    const thresholdBounds: MapBounds = {
      southWestLat: 0,
      southWestLng: 0,
      northEastLat: 0.25,
      northEastLng: 0.35,
    }

    expect(
      evaluateViewportRequest({ bounds: thresholdBounds, center, zoom: 13 }),
    ).toEqual({
      allowed: true,
      boundsSignature: createBoundsSignature(thresholdBounds),
    })
  })

  it('zoom을 반올림하지 않고 13 미만 요청을 차단한다', () => {
    expect(
      evaluateViewportRequest({ bounds: allowedBounds, center, zoom: 12.99 }),
    ).toEqual({
      allowed: false,
      reason: 'zoom-too-low',
      boundsSignature: createBoundsSignature(allowedBounds),
    })
  })

  it.each([Number.NaN, Number.POSITIVE_INFINITY, Number.NEGATIVE_INFINITY])(
    '유한하지 않은 zoom %s을 잘못된 값으로 차단한다',
    (zoom) => {
      expect(evaluateViewportRequest({ bounds: allowedBounds, center, zoom })).toEqual({
        allowed: false,
        reason: 'invalid-zoom',
        boundsSignature: createBoundsSignature(allowedBounds),
      })
    },
  )

  it('위도 범위가 0.25를 초과하면 요청을 차단한다', () => {
    expect(
      evaluateViewportRequest({
        bounds: { ...allowedBounds, northEastLat: 37.250_000_1 },
        center,
        zoom: 13,
      }),
    ).toMatchObject({
      allowed: false,
      reason: 'latitude-span-too-large',
    })
  })

  it('경도 범위가 0.35를 초과하면 요청을 차단한다', () => {
    expect(
      evaluateViewportRequest({
        bounds: { ...allowedBounds, northEastLng: 126.350_000_1 },
        center,
        zoom: 13,
      }),
    ).toMatchObject({
      allowed: false,
      reason: 'longitude-span-too-large',
    })
  })

  it('좌표 중 하나라도 NaN이면 잘못된 범위로 차단한다', () => {
    expect(
      evaluateViewportRequest({
        bounds: { ...allowedBounds, southWestLat: Number.NaN },
        center,
        zoom: 13,
      }),
    ).toEqual({
      allowed: false,
      reason: 'invalid-bounds',
      boundsSignature: null,
    })
  })

  it.each([
    {
      ...allowedBounds,
      southWestLat: allowedBounds.northEastLat,
      northEastLat: allowedBounds.southWestLat,
    },
    {
      ...allowedBounds,
      southWestLng: allowedBounds.northEastLng,
      northEastLng: allowedBounds.southWestLng,
    },
    { ...allowedBounds, northEastLat: allowedBounds.southWestLat },
    { ...allowedBounds, northEastLng: allowedBounds.southWestLng },
  ])('역전되거나 너비가 없는 범위를 차단한다', (bounds) => {
    expect(evaluateViewportRequest({ bounds, center, zoom: 13 })).toEqual({
      allowed: false,
      reason: 'invalid-bounds',
      boundsSignature: null,
    })
  })

  it.each([
    { ...allowedBounds, southWestLat: -90.000_001 },
    { ...allowedBounds, northEastLat: 90.000_001 },
    { ...allowedBounds, southWestLng: -180.000_001 },
    { ...allowedBounds, northEastLng: 180.000_001 },
  ])('지리 좌표 범위를 벗어난 bounds를 차단한다', (bounds) => {
    expect(evaluateViewportRequest({ bounds, center, zoom: 13 })).toEqual({
      allowed: false,
      reason: 'invalid-bounds',
      boundsSignature: null,
    })
  })
})

describe('createBoundsSignature', () => {
  it('속성 삽입 순서와 무관한 안정적인 signature를 만든다', () => {
    const reorderedBounds: MapBounds = {
      northEastLng: allowedBounds.northEastLng,
      southWestLat: allowedBounds.southWestLat,
      northEastLat: allowedBounds.northEastLat,
      southWestLng: allowedBounds.southWestLng,
    }

    expect(createBoundsSignature(reorderedBounds)).toBe(
      createBoundsSignature(allowedBounds),
    )
  })

  it('음의 0과 양의 0을 같은 좌표로 정규화한다', () => {
    const negativeZeroBounds: MapBounds = {
      southWestLat: -0,
      southWestLng: -0,
      northEastLat: 0.25,
      northEastLng: 0.35,
    }
    const positiveZeroBounds: MapBounds = {
      ...negativeZeroBounds,
      southWestLat: 0,
      southWestLng: 0,
    }

    expect(createBoundsSignature(negativeZeroBounds)).toBe(
      createBoundsSignature(positiveZeroBounds),
    )
  })

  it('소수점 5자리로 정규화한 좌표가 같으면 같은 signature를 만든다', () => {
    expect(
      createBoundsSignature({
        ...allowedBounds,
        northEastLat: allowedBounds.northEastLat - 0.000_000_1,
      }),
    ).toBe(createBoundsSignature(allowedBounds))
  })

  it('소수점 5자리 정규화 결과가 달라지는 이동은 다른 signature를 만든다', () => {
    expect(
      createBoundsSignature({
        ...allowedBounds,
        northEastLat: allowedBounds.northEastLat - 0.000_01,
      }),
    ).not.toBe(createBoundsSignature(allowedBounds))
  })

  it('잘못된 bounds에는 signature를 만들지 않는다', () => {
    expect(
      createBoundsSignature({
        ...allowedBounds,
        northEastLng: Number.POSITIVE_INFINITY,
      }),
    ).toBeNull()
  })
})
