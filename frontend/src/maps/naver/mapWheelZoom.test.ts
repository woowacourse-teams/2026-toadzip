import { describe, expect, it, vi } from 'vitest'

import { speedUpWheelZoom, WHEEL_ZOOM_RATE } from './mapWheelZoom.ts'

function asMap(value: unknown) {
  return value as naver.maps.Map
}

describe('speedUpWheelZoom', () => {
  it('GL 지도의 마우스 휠 배율만 올린다', () => {
    const setWheelZoomRate = vi.fn()
    const setZoomRate = vi.fn()

    speedUpWheelZoom(asMap({ getMapbox: () => ({ scrollZoom: { setWheelZoomRate, setZoomRate } }) }))

    expect(setWheelZoomRate).toHaveBeenCalledExactlyOnceWith(WHEEL_ZOOM_RATE)
    expect(setZoomRate).not.toHaveBeenCalled()
  })

  it.each([
    ['getMapbox가 없는 지도', {}],
    ['Mapbox가 없는 지도', { getMapbox: () => null }],
    ['scrollZoom 모양이 다른 지도', { getMapbox: () => ({ scrollZoom: {} }) }],
    ['getMapbox가 예외를 던지는 지도', { getMapbox: () => { throw new Error('internal') } }],
  ])('%s는 건드리지 않고 예외도 내지 않는다', (_name, map) => {
    expect(() => speedUpWheelZoom(asMap(map))).not.toThrow()
  })
})
