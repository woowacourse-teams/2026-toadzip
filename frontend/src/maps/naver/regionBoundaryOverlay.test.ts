import { describe, expect, it } from 'vitest'
import { createRegionBoundaryOverlay } from './regionBoundaryOverlay.ts'

describe('regionBoundaryOverlay', () => {
  it('하나의 클릭 투과 레이어에서 섬과 구멍을 별도 닫힌 경로로 그리고 이동 후 갱신·해제한다', () => {
    const pane = document.createElement('div')
    let center = { x: 100, y: 80 }
    const map = { getSize: () => ({ width: 200, height: 160 }), getCenter: () => center }
    class OverlayView {
      map: unknown = null
      onAdd() {}
      onRemove() {}
      draw() {}
      getMap() { return this.map }
      getPanes() { return { overlayLayer: pane } }
      getProjection() { return { fromCoordToOffset: (p: unknown) => p } }
      setMap(next: unknown) { this.map = next; if (next) { this.onAdd(); this.draw() } else this.onRemove() }
    }
    const maps = {
      OverlayView,
      LatLng: class { x: number; y: number; constructor(lat: number, lng: number) { this.x = lng; this.y = lat } },
    } as unknown as typeof naver.maps
    const boundary = { regionCode: 'test', version: 'test', polygons: [
      [[[0, 0], [10, 0], [10, 10], [0, 0]], [[2, 2], [3, 3], [4, 2], [2, 2]]],
      [[[20, 0], [30, 0], [30, 10], [20, 0]]],
    ] } as const
    const original = structuredClone(boundary)
    const overlay = createRegionBoundaryOverlay(maps, map as unknown as naver.maps.Map, boundary)
    const svg = pane.querySelector('svg')!
    const path = pane.querySelector('path')!
    expect(pane.childElementCount).toBe(1)
    expect(svg.style.pointerEvents).toBe('none')
    expect(svg.getAttribute('aria-hidden')).toBe('true')
    expect(path.getAttribute('d')).toBe('M0,0L10,0L10,10L0,0ZM2,2L3,3L4,2L2,2ZM20,0L30,0L30,10L20,0Z')
    expect(path.getAttribute('fill-rule')).toBe('evenodd')
    expect(path.getAttribute('fill')).toBe('#D34F3E')
    expect(path.getAttribute('fill-opacity')).toBe('0.08')
    expect(path.getAttribute('stroke')).toBe('#D34F3E')
    expect(path.getAttribute('stroke-width')).toBe('2')
    center = { x: 110, y: 90 }
    overlay.draw()
    expect(svg.style.left).toBe('10px')
    expect(svg.style.top).toBe('10px')
    expect(path.getAttribute('d')).toContain('M-10,-10L0,-10')
    expect(pane.childElementCount).toBe(1)
    expect(boundary).toEqual(original)
    overlay.setMap(null)
    expect(pane.childElementCount).toBe(0)
  })
})
