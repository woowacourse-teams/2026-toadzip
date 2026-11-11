import { describe, expect, it } from 'vitest'
import { districtOptions, provinceOptions, regionLabel } from './conditionRegions'

describe('conditionRegions', () => {
  it('lists every province in the service order', () => {
    expect(provinceOptions()[0]).toEqual(['11', '서울특별시'])
    expect(provinceOptions()).toHaveLength(16)
  })

  it('lists five-digit districts of a province', () => {
    const seoul = districtOptions('11')
    expect(seoul[0]).toEqual(['11110', '종로구'])
    expect(seoul.find(([code]) => code === '11140')).toEqual(['11140', '중구'])
    expect(seoul.every(([code]) => code.length === 5 && code.startsWith('11'))).toBe(true)
  })

  it('names a district with a short province name', () => {
    expect(regionLabel('11140')).toBe('서울 중구')
    expect(regionLabel('41110')).toMatch(/^경기 수원시/)
    expect(regionLabel('99999')).toBe('99999')
  })
})
