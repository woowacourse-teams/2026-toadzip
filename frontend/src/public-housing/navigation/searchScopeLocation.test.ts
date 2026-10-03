import { describe, expect, it } from 'vitest'
import { parseSearchScope, searchScopeSignature, setSearchScopeQuery } from './searchScopeLocation.ts'

const bounds = { southWestLat: 37.4, southWestLng: 126.8, northEastLat: 37.6, northEastLng: 127.1 }

describe('검색 범위 URL', () => {
  it('지역 변경 시 경계와 두 목록의 지역 조건을 함께 바꾸고 카메라·필터는 유지한다', () => {
    const original = new URLSearchParams('searchMode=area&searchBounds=1,2,3,4&boundaryRegionCode=11&complexRegionCode=41&announcementRegionCode=48&mapZoom=12&complexRentalTypes=HAPPY_HOUSING')
    const next = setSearchScopeQuery(original, { mode: 'region', regionCode: '11380' })
    expect(parseSearchScope(next)).toEqual({ mode: 'region', regionCode: '11380' })
    for (const key of ['boundaryRegionCode', 'complexRegionCode', 'announcementRegionCode']) {
      expect(next.getAll(key)).toEqual(['11380'])
    }
    expect(next.has('searchBounds')).toBe(false)
    expect(next.get('mapZoom')).toBe('12')
    expect(next.get('complexRentalTypes')).toBe('HAPPY_HOUSING')
    expect(original.get('searchMode')).toBe('area')
  })

  it('지도 영역 검색으로 전환하면 모든 지역 키를 해제하고 적용한 영역을 복원한다', () => {
    const original = new URLSearchParams('searchMode=region&boundaryRegionCode=11&complexRegionCode=11&announcementRegionCode=11&mapLat=37.7')
    const next = setSearchScopeQuery(original, { mode: 'area', bounds })
    expect(parseSearchScope(next)).toEqual({ mode: 'area', bounds })
    for (const key of ['boundaryRegionCode', 'complexRegionCode', 'announcementRegionCode']) expect(next.has(key)).toBe(false)
    expect(next.get('mapLat')).toBe('37.7')
  })

  it('검색 모드가 없는 기존 지역 링크도 복원한다', () => {
    expect(parseSearchScope(new URLSearchParams('complexRegionCode=11380')))
      .toEqual({ mode: 'region', regionCode: '11380' })
    expect(parseSearchScope(new URLSearchParams('announcementRegionCode=41')))
      .toEqual({ mode: 'region', regionCode: '41' })
  })

  it.each([
    '', 'searchMode=other', 'searchMode=region&searchMode=area&complexRegionCode=11',
    'searchMode=region&complexRegionCode=113800', 'searchMode=area',
    'searchMode=area&searchBounds=37,126,37,127',
    'searchMode=area&searchBounds=38,126,37,127',
    'searchMode=area&searchBounds=37,127,38,126',
    'searchMode=area&searchBounds=37,126,91,127',
    'searchMode=area&searchBounds=NaN,126,38,127',
    'searchMode=area&searchBounds=37,126,38,127&searchBounds=37,126,38,127',
  ])('잘못된 검색 범위를 거부한다: %s', (query) => {
    expect(parseSearchScope(new URLSearchParams(query))).toBeNull()
  })

  it('잘못된 내부 범위를 URL로 저장하지 않는다', () => {
    expect(() => setSearchScopeQuery(new URLSearchParams(), { mode: 'region', regionCode: 'bad' })).toThrow(RangeError)
    expect(() => setSearchScopeQuery(new URLSearchParams(), {
      mode: 'area', bounds: { ...bounds, northEastLat: bounds.southWestLat },
    })).toThrow(RangeError)
  })

  it('검색 서명은 카메라에 관계없이 검색 범위만 나타낸다', () => {
    expect(searchScopeSignature(null)).toBe('')
    expect(searchScopeSignature({ mode: 'region', regionCode: '11380' })).toBe('region:11380')
    expect(searchScopeSignature({ mode: 'area', bounds })).toBe('area:37.4,126.8,37.6,127.1')
  })
})
