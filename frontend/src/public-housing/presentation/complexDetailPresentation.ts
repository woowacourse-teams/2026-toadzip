import type { HousingComplexDetailData } from '../components/HousingComplexDetailPanel.tsx'
import type { ComplexDetail } from '../model/publicHousing.ts'

export function toHousingComplexDetailData(
  detail: ComplexDetail,
): HousingComplexDetailData {
  return {
    agencyName: detail.agency?.name ?? '공급기관 정보 확인 중',
    buildingTypeLabel: buildingTypeLabel(detail.buildingType),
    completionDate: detail.completionDate,
    complexId: detail.complexId,
    corridorTypeLabel: corridorTypeLabel(detail.corridorType),
    currentAnnouncements: detail.currentAnnouncements.map((announcement) => ({
      ...announcement,
      publicationTypeLabel: publicationTypeLabel(announcement.publicationType),
    })),
    hasElevator: detail.hasElevator,
    heatingTypeLabel: heatingTypeLabel(detail.heatingType),
    housingTypes: detail.housingTypes,
    images: detail.images,
    moveOutCountLastYear: detail.moveOutCountLastYear,
    name: detail.name ?? '단지명 정보 확인 중',
    overviewImageUrl: detail.overviewImageUrl,
    regionName: detail.address?.regionName ?? '지역 정보 확인 중',
    rentalTypeLabel: rentalTypeLabel(detail.rentalType),
    roadAddress: detail.address?.roadAddress ?? '주소 정보 확인 중',
    totalHouseholdCount: detail.totalHouseholdCount,
    totalParkingCount: detail.totalParkingCount,
  }
}

function rentalTypeLabel(value: string | null) {
  return codeLabel(value, {
    ETC: '기타 공공임대',
    HAPPY_HOUSING: '행복주택',
    INTEGRATED_PUBLIC_RENTAL: '통합공공임대',
    NATIONAL_RENTAL: '국민임대',
    PERMANENT_RENTAL: '영구임대',
    PUBLIC_RENTAL_50Y: '50년 공공임대',
    REDEVELOPMENT_RENTAL: '재개발임대',
  }, '임대유형 정보 확인 중')
}

function buildingTypeLabel(value: string | null) {
  return codeLabel(value, {
    APARTMENT: '아파트',
    ETC: '기타',
    OFFICETEL: '오피스텔',
  }, '건물형태 정보 확인 중')
}

function heatingTypeLabel(value: string | null) {
  return codeLabel(value, {
    CENTRAL: '중앙난방',
    DISTRICT: '지역난방',
    ETC: '기타',
    INDIVIDUAL: '개별난방',
  }, '난방종류 정보 확인 중')
}

function corridorTypeLabel(value: string | null) {
  return codeLabel(value, {
    CORRIDOR: '복도식',
    MIXED: '혼합식',
    STAIR: '계단식',
    UNKNOWN: '정보 확인 중',
  }, '복도유형 정보 확인 중')
}

function publicationTypeLabel(value: string | null) {
  return codeLabel(value, {
    CORRECTION: '정정공고',
    ORIGINAL: '원공고',
  }, '공고유형 정보 확인 중')
}

function codeLabel(
  value: string | null,
  labels: Readonly<Record<string, string>>,
  fallback: string,
) {
  if (value === null) {
    return fallback
  }
  return labels[value] ?? fallback
}
