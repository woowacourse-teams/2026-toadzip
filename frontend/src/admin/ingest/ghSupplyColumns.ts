type Scope = 'district' | 'housingType' | 'detail'

/** 공급 표 열. 원천 JSON의 지구·주택형·팝업 상세 항목 가운데 값 하나로 표시되는 것만 둔다. */
export const ghSupplyValueColumns: Array<{ scope: Scope; key: string; label: string }> = [
  { scope: 'district', key: 'name', label: '지구' },
  { scope: 'district', key: 'address', label: '지번 주소' },
  { scope: 'housingType', key: 'name', label: '주택형' },
  { scope: 'housingType', key: 'exclusiveArea', label: '전용면적' },
  { scope: 'housingType', key: 'supplyCount', label: '금회공급' },
  { scope: 'housingType', key: 'rentalDeposit', label: '보증금' },
  { scope: 'housingType', key: 'monthlyRent', label: '월임대료' },
  { scope: 'housingType', key: 'applicationStatus', label: '인터넷청약 상태' },
  { scope: 'housingType', key: 'houseTypeCode', label: '주택형 코드' },
  { scope: 'housingType', key: 'businessCode', label: '사업 코드' },
  { scope: 'housingType', key: 'recruitmentRound', label: '모집 회차' },
  { scope: 'district', key: 'recruitmentCount', label: '지구 모집호수' },
  { scope: 'district', key: 'exclusiveAreaRange', label: '지구 전용면적 범위' },
  { scope: 'district', key: 'heatingType', label: '난방방식' },
  { scope: 'district', key: 'expectedMoveIn', label: '입주예정월' },
  { scope: 'detail', key: 'supplyArea', label: '팝업 공급면적' },
  { scope: 'detail', key: 'residentialCommonArea', label: '팝업 주거공용면적' },
  { scope: 'detail', key: 'otherCommonArea', label: '팝업 기타공용면적' },
  { scope: 'detail', key: 'undergroundParkingArea', label: '팝업 지하주차장면적' },
  { scope: 'detail', key: 'contractArea', label: '팝업 계약면적' },
  { scope: 'detail', key: 'householdCount', label: '팝업 세대수' },
  { scope: 'detail', key: 'currentSupplyCount', label: '팝업 금회분양 세대수' },
  { scope: 'detail', key: 'rentalDeposit', label: '팝업 임대보증금' },
  { scope: 'detail', key: 'monthlyRent', label: '팝업 월임대료' },
]

const shown = (scope: Scope) => ghSupplyValueColumns.filter(column => column.scope === scope).map(column => column.key).join('|')
const suppliedPath = new RegExp(`^districts\\[\\d+\\]\\.(?:(?:${shown('district')})|housingTypes\\[\\d+\\]\\.(?:(?:${shown('housingType')})|detail\\.(?:${shown('detail')})))$`)

/** 공급 표가 보여 주는 경로인지. 그 밖의 지구·주택형 항목(일정·이미지·안내·요청 값)은 공고 표에 남긴다. */
export function isGhSupplyTablePath(path: string): boolean {
  return suppliedPath.test(path)
}
