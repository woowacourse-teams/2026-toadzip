import { describe, expect, it } from 'vitest'
import { findRegionBoundaryMetadata, findRegionBoundaryName } from './regionBoundaryCatalog.ts'

describe('bundled region boundary catalog', () => {
  it('finds a parent city and its coordinate-less search districts without a network request', () => {
    const city = findRegionBoundaryMetadata('41110')
    const district = findRegionBoundaryMetadata('41111')
    expect(city?.name).toBe('경기도 수원시')
    expect(district?.name).toBe('경기도 수원시 장안구')
    expect(city?.bounds.southWestLat).toBeLessThanOrEqual(district?.bounds.southWestLat ?? -90)
    expect(city?.bounds.northEastLat).toBeGreaterThanOrEqual(district?.bounds.northEastLat ?? 90)
    expect(district?.path).toMatch(/^\/region-boundaries\/[^/]+\/41111\.geojson$/)
  })
  it.each(['41', '99999', '../41110', '', '4111000000'])('does not invent geometry for unsupported code %s', (code) => {
    expect(findRegionBoundaryMetadata(code)).toBeNull()
  })
  it('restores the known name of an unavailable boundary without inventing geometry', () => {
    expect(findRegionBoundaryName('26290')).toBe('부산광역시 남구')
    expect(findRegionBoundaryMetadata('26290')).toBeNull()
    expect(findRegionBoundaryName('99999')).toBeNull()
  })
})
