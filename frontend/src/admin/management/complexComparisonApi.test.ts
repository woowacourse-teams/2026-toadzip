import { expect, it } from 'vitest'
import { parseComparison } from './complexComparisonApi'

const current = { version: 2, name: '테스트 단지', agencyCode: 'LH', rentalType: 'HAPPY_HOUSING', totalHouseholdCount: 10, totalParkingCount: 0, completionDate: null, heatingType: null, buildingType: null, corridorType: null, hasElevator: false, moveOutCountLastYear: null, overviewImageUrl: null,
  address: { roadAddress: '서울특별시 중구 세종대로 110', pnu: null, legalDongCode: '1114010100', provinceCode: '11', cityCountyDistrictCode: '11140', latitude: 37.5665, longitude: 126.978 } }

it('지번을 모르는 단지(PNU 없음)의 원천 비교 응답을 받는다', () => {
  expect(parseComparison({ snapshotToken: 'a'.repeat(64), current, sources: [] }).current.address).toMatchObject({ pnu: null })
})
