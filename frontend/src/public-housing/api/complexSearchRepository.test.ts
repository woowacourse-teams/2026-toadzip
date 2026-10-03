import { describe, expect, it, vi } from 'vitest'
import type { SearchScope } from '../model/publicHousing.ts'
import { MINIMAL_PUBLIC_HOUSING_SNAPSHOT as fixture } from '../testing/minimalPublicHousingSnapshot.ts'
import { decodeComplexSearchEnvelope, PublicHousingContractError } from './publicHousingContract.ts'
import { createHttpPublicHousingRepository } from './publicHousingRepository.ts'

const bounds = { southWestLat: 37.4, southWestLng: 126.8, northEastLat: 37.8, northEastLng: 127.3 }
const snapshot = {
  totalCount: 1, locatedCount: 1, complexIds: [17], bounds,
  mapItems: fixture.mapComplexItems,
  page: { items: fixture.complexListItems, nextCursor: null, hasNext: false },
}

describe('통합 단지 검색 API', () => {
  it.each<SearchScope>([{ mode: 'region', regionCode: '11380' }, { mode: 'area', bounds }])(
    '$mode 검색은 확정한 범위만 전달하고 집계·핀·첫 페이지를 함께 변환한다', async (scope) => {
      const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify({ data: snapshot })))
      const repository = createHttpPublicHousingRepository({ apiBaseUrl: 'https://api.example.test', fetcher })
      const signal = new AbortController().signal
      const result = await repository.findComplexSearch?.(scope, 20, signal,
        { regionCode: '41', rentalTypes: ['HAPPY_HOUSING'], applicationStatuses: ['APPLYING'] })
      const [request, init] = fetcher.mock.calls[0]
      const url = new URL(String(request))
      expect(url.pathname).toBe('/api/v2/complexes/search')
      expect(url.searchParams.get('scope')).toBe(scope.mode.toUpperCase())
      expect(url.searchParams.get('size')).toBe('20')
      expect(url.searchParams.getAll('rentalTypes')).toEqual(['HAPPY_HOUSING'])
      expect(url.searchParams.getAll('applicationStatuses')).toEqual(['APPLYING'])
      expect(init?.signal).toBe(signal)
      if (scope.mode === 'region') {
        expect(url.searchParams.get('regionCode')).toBe('11380')
        expect(url.searchParams.has('southWestLat')).toBe(false)
      } else {
        expect(url.searchParams.has('regionCode')).toBe(false)
        for (const [key, value] of Object.entries(bounds)) expect(url.searchParams.get(key)).toBe(String(value))
      }
      expect(result).toMatchObject({ totalCount: 1, locatedCount: 1, complexIds: ['17'],
        mapItems: [{ complexId: '17' }], page: { items: [{ complexId: '17' }] } })
    },
  )

  it.each<SearchScope>([{ mode: 'region', regionCode: '11380' }, { mode: 'area', bounds }])(
    '$mode 단지 다음 페이지와 공고 페이지도 같은 공간 조건을 사용한다', async (scope) => {
      const fetcher = vi.fn<typeof fetch>().mockImplementation(async () => new Response(JSON.stringify({
        data: { items: [], hasNext: false, nextCursor: null, totalCount: 0 },
      })))
      const repository = createHttpPublicHousingRepository({ apiBaseUrl: 'https://api.example.test', fetcher })
      const signal = new AbortController().signal
      await repository.findComplexPage(bounds, 'next', 20, signal, { regionCode: '41', scope })
      await repository.findAnnouncementPage(null, 20, signal, { regionCode: '41', scope })
      for (const [request] of fetcher.mock.calls) {
        const params = new URL(String(request)).searchParams
        expect(params.get('scope')).toBe(scope.mode.toUpperCase())
        expect(params.get('regionCode')).toBe(scope.mode === 'region' ? '11380' : null)
        expect(params.has('southWestLat')).toBe(scope.mode === 'area')
      }
    },
  )

  it('좌표 없는 단지는 전체 결과에 포함하고 지도 수와 구별한다', () => {
    expect(decodeComplexSearchEnvelope({ data: { ...snapshot, locatedCount: 0, mapItems: [], bounds: null } }))
      .toMatchObject({ totalCount: 1, locatedCount: 0, bounds: null, page: { items: [{ complexId: 17 }] } })
  })

  it('한 단지의 동일한 남서·북동 좌표도 허용한다', () => {
    const point = snapshot.mapItems[0]
    const singleBounds = { southWestLat: point.latitude, northEastLat: point.latitude,
      southWestLng: point.longitude, northEastLng: point.longitude }
    expect(decodeComplexSearchEnvelope({ data: { ...snapshot, bounds: singleBounds } }).bounds).toEqual(singleBounds)
  })

  it.each([
    { totalCount: 2 }, { locatedCount: 2 }, { complexIds: [17, 17], totalCount: 2 },
    { complexIds: [99] }, { bounds: null },
    { mapItems: [...snapshot.mapItems, ...snapshot.mapItems], locatedCount: 2, totalCount: 2, complexIds: [17, 18] },
    { bounds: { ...bounds, northEastLat: 37.41 } },
  ])('집계·지도·목록이 불일치하는 응답을 거부한다: %j', (overrides) => {
    expect(() => decodeComplexSearchEnvelope({ data: { ...snapshot, ...overrides } })).toThrow(PublicHousingContractError)
  })
})
