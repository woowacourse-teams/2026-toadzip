import { describe, expect, it } from 'vitest'
import type { ComplexDetail, RawComplexDetail } from '../model/publicHousing.ts'
import { toHousingComplexDetailData } from './complexDetailPresentation.ts'

describe('toHousingComplexDetailData', () => {
  it('API 상세 값을 확정 화면 라벨로 변환하고 0과 null을 보존한다', () => {
    const result = toHousingComplexDetailData(complexDetail())

    expect(result).toMatchObject({
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

  it('알 수 없는 코드와 주소 null은 raw code 대신 확인 중 문구로 바꾼다', () => {
    const detail = complexDetail({
      address: null,
      buildingType: 'NEW_BUILDING_CODE',
      name: null,
      rentalType: null,
    })

    expect(toHousingComplexDetailData(detail)).toMatchObject({
      buildingTypeLabel: '건물형태 정보 확인 중',
      name: '단지명 정보 확인 중',
      regionName: '지역 정보 확인 중',
      rentalTypeLabel: '임대유형 정보 확인 중',
      roadAddress: '주소 정보 확인 중',
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
    ...changes,
  }
}
