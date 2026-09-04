import { describe, expect, it } from 'vitest'

import type {
  AnnouncementSearchFilters,
  ComplexSearchFilters,
} from '../api/publicHousingRepository.ts'
import {
  parseAnnouncementSearchFilters,
  parseComplexSearchFilters,
  setAnnouncementSearchFilters,
  setComplexSearchFilters,
} from './searchFilterLocation.ts'

const COMPLEX_COMMON_FILTERS = {
  agencyCodes: ['LH', 'GH'],
  applicationStatuses: ['BEFORE_APPLICATION', 'APPLYING'],
  recruitmentTypes: ['NEW', 'WAITLIST'],
  regionCode: '11',
  rentalTypes: ['NATIONAL_RENTAL', 'HAPPY_HOUSING'],
} as const satisfies ComplexSearchFilters

const ANNOUNCEMENT_FILTERS = {
  agencyCodes: ['SH', 'ETC'],
  applicationStatuses: ['APPLYING', 'CLOSED'],
  recruitmentTypes: ['WAITLIST', 'ETC'],
  regionCode: '41135',
  rentalTypes: ['PERMANENT_RENTAL', 'PUBLIC_RENTAL_50Y'],
} as const satisfies AnnouncementSearchFilters

describe('검색 필터 URL namespace', () => {
  it('단지와 공고 필터를 분리하고 기존 지도와 상세 query를 보존한다', () => {
    const current = new URLSearchParams(
      'mapLat=37.56661&mapLng=126.97839&mapZoom=14.00&complexId=17&announcementId=42',
    )

    const withComplex = setComplexSearchFilters(
      current,
      COMPLEX_COMMON_FILTERS,
    )
    const withBoth = setAnnouncementSearchFilters(
      withComplex,
      ANNOUNCEMENT_FILTERS,
    )

    expect(parseComplexSearchFilters(withBoth)).toEqual(
      COMPLEX_COMMON_FILTERS,
    )
    expect(parseAnnouncementSearchFilters(withBoth)).toEqual(
      ANNOUNCEMENT_FILTERS,
    )
    expect(withBoth.get('mapLat')).toBe('37.56661')
    expect(withBoth.get('mapLng')).toBe('126.97839')
    expect(withBoth.get('mapZoom')).toBe('14.00')
    expect(withBoth.get('complexId')).toBe('17')
    expect(withBoth.get('announcementId')).toBe('42')
    expect(current.toString()).toBe(
      'mapLat=37.56661&mapLng=126.97839&mapZoom=14.00&complexId=17&announcementId=42',
    )
  })

  it('각 enum 배열을 반복 query로 직렬화하고 다시 복원한다', () => {
    const complexSearch = setComplexSearchFilters(
      new URLSearchParams(),
      COMPLEX_COMMON_FILTERS,
    )
    const announcementSearch = setAnnouncementSearchFilters(
      new URLSearchParams(),
      ANNOUNCEMENT_FILTERS,
    )

    expect(complexSearch.getAll('complexRentalTypes')).toEqual([
      'NATIONAL_RENTAL',
      'HAPPY_HOUSING',
    ])
    expect(complexSearch.getAll('complexApplicationStatuses')).toEqual([
      'BEFORE_APPLICATION',
      'APPLYING',
    ])
    expect(complexSearch.getAll('complexAgencyCodes')).toEqual(['LH', 'GH'])
    expect(complexSearch.getAll('complexRecruitmentTypes')).toEqual([
      'NEW',
      'WAITLIST',
    ])
    expect(parseComplexSearchFilters(complexSearch)).toEqual(
      COMPLEX_COMMON_FILTERS,
    )

    expect(announcementSearch.getAll('announcementRentalTypes')).toEqual([
      'PERMANENT_RENTAL',
      'PUBLIC_RENTAL_50Y',
    ])
    expect(
      announcementSearch.getAll('announcementApplicationStatuses'),
    ).toEqual(['APPLYING', 'CLOSED'])
    expect(announcementSearch.getAll('announcementAgencyCodes')).toEqual([
      'SH',
      'ETC',
    ])
    expect(
      announcementSearch.getAll('announcementRecruitmentTypes'),
    ).toEqual(['WAITLIST', 'ETC'])
    expect(parseAnnouncementSearchFilters(announcementSearch)).toEqual(
      ANNOUNCEMENT_FILTERS,
    )
  })

  it('단지의 금액, 면적과 준공년도 범위를 왕복한다', () => {
    const filters = {
      builtYearFrom: 1987,
      builtYearTo: 2026,
      maxDeposit: 30_000_000,
      maxExclusiveArea: 84.99,
      maxMonthlyRent: 750_000,
      minDeposit: 0,
      minExclusiveArea: 16.5,
      minMonthlyRent: 0,
    } satisfies ComplexSearchFilters

    const search = setComplexSearchFilters(new URLSearchParams(), filters)

    expect(search.get('complexMinDeposit')).toBe('0')
    expect(search.get('complexMaxDeposit')).toBe('30000000')
    expect(search.get('complexMinMonthlyRent')).toBe('0')
    expect(search.get('complexMaxMonthlyRent')).toBe('750000')
    expect(search.get('complexMinExclusiveArea')).toBe('16.5')
    expect(search.get('complexMaxExclusiveArea')).toBe('84.99')
    expect(search.get('complexBuiltYearFrom')).toBe('1987')
    expect(search.get('complexBuiltYearTo')).toBe('2026')
    expect(parseComplexSearchFilters(search)).toEqual(filters)
  })

  it.each([
    ['아주 작은 소수', '0.0000001', 0.0000001],
    ['일반 소수', '36.12', 36.12],
    ['아주 큰 유한수', '1000000000000000000000', 1e21],
  ])(
    '면적의 %s를 지수 표기 없이 직렬화하고 같은 값으로 복원한다',
    (_caseName, queryValue, expectedValue) => {
      const parsed = parseComplexSearchFilters(
        new URLSearchParams(`complexMinExclusiveArea=${queryValue}`),
      )

      expect(parsed).toEqual({ minExclusiveArea: expectedValue })

      const serialized = setComplexSearchFilters(
        new URLSearchParams(),
        parsed,
      )

      expect(serialized.get('complexMinExclusiveArea')).toBe(queryValue)
      expect(parseComplexSearchFilters(serialized)).toEqual(parsed)
    },
  )

  it('잘못된 enum, 지역, 숫자와 연도는 API 필터로 복원하지 않는다', () => {
    const search = new URLSearchParams(
      [
        'complexRegionCode=1A',
        'complexRentalTypes=NATIONAL_RENTAL',
        'complexRentalTypes=UNKNOWN_RENTAL',
        'complexApplicationStatuses=CANCELLED',
        'complexAgencyCodes=INVALID_AGENCY',
        'complexRecruitmentTypes=OLD',
        'complexMinDeposit=-1',
        'complexMaxDeposit=1.5',
        'complexMinMonthlyRent=01',
        'complexMaxMonthlyRent=9007199254740992',
        'complexMinExclusiveArea=NaN',
        'complexMaxExclusiveArea=.5',
        'complexBuiltYearFrom=0',
        'complexBuiltYearTo=10000',
        'announcementRegionCode=1234',
        'announcementRentalTypes=UNKNOWN_RENTAL',
        'announcementApplicationStatuses=CANCELLED',
        'announcementAgencyCodes=INVALID_AGENCY',
        'announcementRecruitmentTypes=OLD',
      ].join('&'),
    )

    expect(parseComplexSearchFilters(search)).toEqual({
      rentalTypes: ['NATIONAL_RENTAL'],
    })
    expect(parseAnnouncementSearchFilters(search)).toEqual({})
  })

  it('최솟값이 최댓값보다 큰 범위는 양쪽 모두 복원하지 않는다', () => {
    const search = new URLSearchParams(
      [
        'complexMinDeposit=20000000',
        'complexMaxDeposit=10000000',
        'complexMinMonthlyRent=500000',
        'complexMaxMonthlyRent=100000',
        'complexMinExclusiveArea=84.5',
        'complexMaxExclusiveArea=36.5',
        'complexBuiltYearFrom=2026',
        'complexBuiltYearTo=2000',
      ].join('&'),
    )

    expect(parseComplexSearchFilters(search)).toEqual({})
  })

  it('각 namespace 초기화는 다른 namespace와 기존 query를 보존한다', () => {
    const withBoth = setAnnouncementSearchFilters(
      setComplexSearchFilters(
        new URLSearchParams('mapZoom=14.00&complexId=17'),
        COMPLEX_COMMON_FILTERS,
      ),
      ANNOUNCEMENT_FILTERS,
    )

    const withoutComplex = setComplexSearchFilters(withBoth, {})
    expect(parseComplexSearchFilters(withoutComplex)).toEqual({})
    expect(parseAnnouncementSearchFilters(withoutComplex)).toEqual(
      ANNOUNCEMENT_FILTERS,
    )
    expect(withoutComplex.get('mapZoom')).toBe('14.00')
    expect(withoutComplex.get('complexId')).toBe('17')

    const withoutAnnouncement = setAnnouncementSearchFilters(withBoth, {})
    expect(parseAnnouncementSearchFilters(withoutAnnouncement)).toEqual({})
    expect(parseComplexSearchFilters(withoutAnnouncement)).toEqual(
      COMPLEX_COMMON_FILTERS,
    )
    expect(withoutAnnouncement.get('mapZoom')).toBe('14.00')
    expect(withoutAnnouncement.get('complexId')).toBe('17')
  })
})
