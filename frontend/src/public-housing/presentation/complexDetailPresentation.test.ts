import { describe, expect, it } from 'vitest'
import type { ComplexDetail, RawComplexDetail } from '../model/publicHousing.ts'
import { toHousingComplexDetailData } from './complexDetailPresentation.ts'

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
