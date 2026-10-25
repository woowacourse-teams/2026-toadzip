import { describe, expect, it } from 'vitest'
import type { ComplexDetail, RawComplexDetail } from '../model/publicHousing.ts'
import {
  formatRentalConditionCollectedDate,
  housingTypeLabels,
  toHousingComplexDetailData,
} from './complexDetailPresentation.ts'

it.each([
  ['2026-10-09T18:30:00Z', '2026.10.10'],
  [null, null],
  ['invalid', null],
])('수집 시각 %s를 한국 시간 기준 날짜로 표시한다', (value, expected) => {
  expect(formatRentalConditionCollectedDate(value)).toBe(expected)
})

describe('toHousingComplexDetailData', () => {
  it.each([
    ['ETC', '기타 공공임대'],
    ['HAPPY_HOUSING', '행복주택'],
    ['INTEGRATED_PUBLIC_RENTAL', '통합공공임대'],
    ['NATIONAL_RENTAL', '국민임대'],
    ['PERMANENT_RENTAL', '영구임대'],
    ['PUBLIC_RENTAL_50Y', '50년 공공임대'],
    ['REDEVELOPMENT_RENTAL', '재개발임대'],
    [null, ''],
    ['UNRECOGNIZED', ''],
  ])('%s 임대유형의 표시와 누락 정책을 보존한다', (rentalType, expected) => {
    expect(toHousingComplexDetailData(complexDetail({ rentalType })).rentalTypeLabel).toBe(expected)
  })

  it('API 상세 값을 확정 화면 라벨로 변환하고 0과 null을 보존한다', () => {
    const result = toHousingComplexDetailData(complexDetail())

    expect(result).toMatchObject({
      agencyCode: 'LH',
      agencyName: '한국토지주택공사',
      buildingTypeLabel: '아파트',
      corridorTypeLabel: '계단식',
      heatingTypeLabel: '개별난방',
      rentalTypeLabel: '행복주택',
      moveOutCountLastYear: 0,
      totalParkingCount: 0,
    })
    expect(result.currentAnnouncements[0]).toMatchObject({
      publicationTypeLabel: '정정공고',
      actualCompetitionRate: 0,
    })
    expect(result.housingTypes[0].maintenanceFee).toBeNull()
  })

  it('알 수 없는 코드와 누락 속성은 빈값으로 표시한다', () => {
    const detail = complexDetail({
      address: null,
      agency: null,
      buildingType: 'NEW_BUILDING_CODE',
      corridorType: 'UNKNOWN',
      heatingType: null,
      name: null,
      rentalType: null,
    })

    expect(toHousingComplexDetailData(detail)).toMatchObject({
      agencyName: '',
      buildingTypeLabel: '',
      corridorTypeLabel: '',
      heatingTypeLabel: '',
      name: '',
      regionName: '',
      rentalTypeLabel: '',
      roadAddress: '',
    })
  })
})

function complexDetail(changes: Partial<ComplexDetail> = {}): ComplexDetail {
  const raw = {} as RawComplexDetail
  return {
    address: {
      latitude: 37.5,
      longitude: 126.9,
      regionName: '서울특별시 중구',
      roadAddress: '서울특별시 중구 세종대로 110',
    },
    agency: { code: 'LH', name: '한국토지주택공사' },
    buildingType: 'APARTMENT',
    completionDate: '2020-01-01',
    complexId: '17',
    corridorType: 'STAIR',
    currentAnnouncements: [{
      actualCompetitionRate: 0,
      announcementId: '201',
      applicationEndAt: '2026-08-27',
      applicationStartAt: '2026-08-20',
      applicationStatus: 'APPLYING',
      dDay: 0,
      publicationType: 'CORRECTION',
      targets: ['청년'],
      title: '행복주택 모집 공고',
    }],
    hasElevator: false,
    heatingType: 'INDIVIDUAL',
    housingTypes: [{
      currentSupplyConditions: [{
        convertibleDeposit: null,
        deposit: 0,
        monthlyRent: 0,
        target: null,
      }],
      exclusiveArea: 36.12,
      floorPlan3dImageUrl: null,
      floorPlanImageUrl: null,
      housingTypeId: '101',
      isDuplex: false,
      maintenanceFee: null,
      basicRentalCondition: null,
      name: '36A',
      supplyArea: null,
    }],
    images: [],
    moveOutCountLastYear: 0,
    name: '행복 단지',
    overviewImageUrl: null,
    raw,
    rentalType: 'HAPPY_HOUSING',
    totalHouseholdCount: 100,
    totalParkingCount: 0,
    depositMin: 0,
    depositMax: null,
    monthlyRentMin: 200_000,
    monthlyRentMax: null,
    ...changes,
  }
}

describe('housingTypeLabels', () => {
  const type = (housingTypeId: string, name: string | null, exclusiveArea: number | null, supplyArea: number | null) =>
    ({ housingTypeId, name, exclusiveArea, supplyArea })

  it('단지 안에서 이름이 겹치지 않으면 이름만 표시한다', () => {
    const labels = housingTypeLabels([type('1', '26', 26.37, 38.54), type('2', '31', 31.32, 45.34)], 'sqm')

    expect([...labels.values()]).toEqual(['26', '31'])
  })

  it('이름이 같으면 전용면적으로, 전용면적까지 같으면 공급면적으로 구분한다', () => {
    const labels = housingTypeLabels([
      type('1', '26', 26.37, 38.54),
      type('2', '26', 26.37, 39.93),
      type('3', '16', 16.64, 26.87),
      type('4', '16', 16.79, 27.11),
      type('5', '31', 31.32, 45.34),
    ], 'sqm')

    expect([...labels.values()]).toEqual([
      '26 · 공급 38.54㎡',
      '26 · 공급 39.93㎡',
      '16 · 전용 16.64㎡',
      '16 · 전용 16.79㎡',
      '31',
    ])
  })

  it('구분 면적은 선택한 면적 단위를 따른다', () => {
    const labels = housingTypeLabels([type('1', '26', 26.37, 38.54), type('2', '26', 26.37, 39.93)], 'pyeong')

    expect(labels.get('1')).toBe('26 · 공급 11.66평')
  })

  it('이름이 없거나 구분 면적이 없으면 붙일 값만 붙인다', () => {
    const labels = housingTypeLabels([
      type('1', null, 26.37, 38.54),
      type('2', null, 26.37, 39.93),
      type('3', '26', 26.37, null),
      type('4', '26', 26.37, 39.93),
    ], 'sqm')

    expect([...labels.values()]).toEqual(['', '', '26', '26 · 공급 39.93㎡'])
  })
})
