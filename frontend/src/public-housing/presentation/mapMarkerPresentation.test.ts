import { describe, expect, it } from 'vitest'
import type { MapComplex } from '../model/publicHousing.ts'
import {
  presentComplexDetailMarker,
  presentMapComplexMarker,
} from './mapMarkerPresentation.ts'

describe('map marker presentation', () => {
  it('지도 단지의 최소 보증금과 월세 및 짧은 이름과 원문을 표시한다', () => {
    expect(presentMapComplexMarker(mapComplex())).toEqual({
      agencyLabel: 'LH',
      agencyName: '한국토지주택공사',
      rentalTypeLabel: '행복',
      rentalTypeName: '행복주택',
      deposit: { digits: '5000', unit: '만', exactLabel: '50,000,000원' },
      monthlyRent: { digits: '20', unit: '만', exactLabel: '200,000원' },
    })
  })

  it('최소가 없으면 최대 금액으로 대체하지 않는다', () => {
    expect(presentMapComplexMarker(mapComplex({
      depositMin: null,
      monthlyRentMin: null,
    }))).toMatchObject({ deposit: null, monthlyRent: null })
  })

  it('결측 기관과 임대유형은 미상으로 표시한다', () => {
    expect(presentMapComplexMarker(mapComplex({
      agency: null,
      rentalType: null,
      depositMin: null,
      monthlyRentMin: null,
    }))).toEqual({
      agencyLabel: '미상',
      agencyName: '기관 정보 없음',
      rentalTypeLabel: '미상',
      rentalTypeName: '임대유형 정보 없음',
      deposit: null,
      monthlyRent: null,
    })
  })

  it.each([
    [{ code: null, name: '한국토지주택공사' }, 'LH', '한국토지주택공사'],
    [{ code: '', name: ' 서울주택도시공사 ' }, 'SH', '서울주택도시공사'],
    [{ code: null, name: '경기주택도시공사' }, 'GH', '경기주택도시공사'],
    [{ code: 'SH', name: null }, 'SH', '서울주택도시공사'],
    [{ code: 'ETC', name: null }, '기타', '기타'],
    [{ code: 'ETC', name: '지방 공급기관' }, '기타', '지방 공급기관'],
    [{ code: '지자체', name: '서울특별시' }, '지자체', '서울특별시'],
    [{ code: null, name: '새로운 지역 공급기관' }, '새로운 지역 공급기관', '새로운 지역 공급기관'],
    [{ code: 'NEW', name: null }, 'NEW', 'NEW'],
    [{ code: ' ', name: '' }, '미상', '기관 정보 없음'],
  ])('기관 %j의 알려진 약칭 또는 원문을 유지한다', (agency, agencyLabel, agencyName) => {
    expect(presentMapComplexMarker(mapComplex({ agency }))).toMatchObject({ agencyLabel, agencyName })
  })

  it.each([
    ['HAPPY_HOUSING', '행복', '행복주택'],
    ['NATIONAL_RENTAL', '국민', '국민임대'],
    ['PERMANENT_RENTAL', '영구', '영구임대'],
    ['PUBLIC_RENTAL_5Y', '5년', '5년 공공임대'],
    ['PUBLIC_RENTAL_10Y', '10년', '10년 공공임대'],
    ['PUBLIC_RENTAL_50Y', '50년', '50년 공공임대'],
    ['INTEGRATED_PUBLIC_RENTAL', '통합', '통합공공임대'],
    ['REDEVELOPMENT_RENTAL', '재개발', '재개발임대'],
    ['LONG_TERM_JEONSE', '전세', '장기전세'],
    ['ETC', '기타', '기타 공공임대'],
    ['NEW_RENTAL_TYPE', '미상', 'NEW_RENTAL_TYPE'],
    ['__proto__', '미상', '__proto__'],
    [' ', '미상', '임대유형 정보 없음'],
  ])('유형 %s을 짧게 표시하고 원문 의미를 보존한다', (rentalType, rentalTypeLabel, rentalTypeName) => {
    expect(presentMapComplexMarker(mapComplex({ rentalType }))).toMatchObject({ rentalTypeLabel, rentalTypeName })
  })

  it.each([
    [0, '0', '원'],
    [9_999, '9999', '원'],
    [10_000, '1', '만'],
    [234_900, '23.4', '만'],
    [580_000, '58', '만'],
    [999_999, '99.9', '만'],
    [1_000_000, '100', '만'],
    [9_999_999, '999.9', '만'],
    [10_000_000, '1000', '만'],
    [99_990_000, '9999', '만'],
    [99_999_999, '9999', '만'],
    [100_000_000, '1', '억'],
    [212_340_000, '2.12', '억'],
    [999_999_999, '9.99', '억'],
    [9_999_999_999, '99.99', '억'],
    [10_000_000_000, '100', '억'],
    [99_999_999_999, '999.9', '억'],
    [100_000_000_000, '1000', '억'],
    [999_999_999_999, '9999', '억'],
    [1_000_000_000_000, '1', '조'],
    [2_123_400_000_000, '2.12', '조'],
    [99_999_999_999_999, '99.99', '조'],
    [100_000_000_000_000, '100', '조'],
    [999_999_999_999_999, '999.9', '조'],
    [1_000_000_000_000_000, '1000', '조'],
    [Number.MAX_SAFE_INTEGER, '9007', '조'],
  ])('%s원을 버림 축약하면서 정확한 원 금액을 보존한다', (value, digits, unit) => {
    const result = presentMapComplexMarker(mapComplex({ depositMin: value, monthlyRentMin: value }))
    const expected = { digits, unit, exactLabel: `${value.toLocaleString('ko-KR')}원` }
    expect(result.deposit).toEqual(expected)
    expect(result.monthlyRent).toEqual(expected)
    expect(digits.length).toBeLessThanOrEqual(5)
  })

  it.each([null, -1, Number.NaN, Number.POSITIVE_INFINITY, Number.NEGATIVE_INFINITY])(
    '유효하지 않은 금액 %s을 무료 조건으로 표시하지 않는다',
    value => {
      expect(presentMapComplexMarker(mapComplex({ depositMin: value, monthlyRentMin: value })))
        .toMatchObject({ deposit: null, monthlyRent: null })
    },
  )

  it('상세의 보증금과 월세 최솟값은 서로 다른 조건에서도 각각 찾는다', () => {
    expect(presentComplexDetailMarker({
      agency: { code: 'SH', name: '서울주택도시공사' },
      rentalType: 'NATIONAL_RENTAL',
      housingTypes: [
        { currentSupplyConditions: [
          { deposit: 50_000_000, monthlyRent: 180_000 },
          { deposit: 10_000_000, monthlyRent: 260_000 },
          { deposit: -1, monthlyRent: null },
        ] },
        { currentSupplyConditions: [
          { deposit: null, monthlyRent: 310_000 },
          { deposit: Number.NaN, monthlyRent: Number.POSITIVE_INFINITY },
        ] },
      ],
    })).toEqual({
      agencyLabel: 'SH',
      agencyName: '서울주택도시공사',
      rentalTypeLabel: '국민',
      rentalTypeName: '국민임대',
      deposit: { digits: '1000', unit: '만', exactLabel: '10,000,000원' },
      monthlyRent: { digits: '18', unit: '만', exactLabel: '180,000원' },
    })
  })

  it('상세의 0원은 최소로 유지하고 조건이 없으면 결측으로 표시한다', () => {
    const detail = { agency: null, rentalType: null, housingTypes: [
      { currentSupplyConditions: [{ deposit: 0, monthlyRent: 0 }, { deposit: 20_000_000, monthlyRent: 300_000 }] },
    ] }
    expect(presentComplexDetailMarker(detail)).toMatchObject({
      deposit: { digits: '0', unit: '원', exactLabel: '0원' },
      monthlyRent: { digits: '0', unit: '원', exactLabel: '0원' },
    })
    expect(presentComplexDetailMarker({ ...detail, housingTypes: [] }))
      .toMatchObject({ deposit: null, monthlyRent: null })
    expect(presentComplexDetailMarker({ ...detail, housingTypes: [{ currentSupplyConditions: [] }] }))
      .toMatchObject({ deposit: null, monthlyRent: null })
  })
})

function mapComplex(overrides: Partial<MapComplex> = {}): MapComplex {
  const raw = {
    agency: { code: 'LH', name: '한국토지주택공사' },
    complexId: 17,
    depositMax: 70_000_000,
    depositMin: 50_000_000,
    exclusiveAreaMax: 44.87,
    exclusiveAreaMin: 36.12,
    latitude: 37.56,
    longitude: 126.98,
    monthlyRentMax: 300_000,
    monthlyRentMin: 200_000,
    name: '서울가람 행복주택',
    rentalType: 'HAPPY_HOUSING',
  }
  return { ...raw, complexId: '17', raw, ...overrides }
}
