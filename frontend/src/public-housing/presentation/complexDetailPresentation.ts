import { rentalTypeLabel } from './rentalTypeLabel.ts'
import type { HousingComplexDetailData } from '../components/HousingComplexDetailPanel.tsx'
import type { ComplexDetail } from '../model/publicHousing.ts'

export function toHousingComplexDetailData(
  detail: ComplexDetail,
): HousingComplexDetailData {
  return {
    agencyCode: detail.agency?.code ?? null,
    agencyName: detail.agency?.name ?? '',
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
    name: detail.name ?? '',
    overviewImageUrl: detail.overviewImageUrl,
    regionName: detail.address?.regionName ?? '',
    rentalTypeLabel: rentalTypeLabel(detail.rentalType) ?? '',
    roadAddress: detail.address?.roadAddress ?? '',
    totalHouseholdCount: detail.totalHouseholdCount,
    totalParkingCount: detail.totalParkingCount,
  }
}

function buildingTypeLabel(value: string | null) {
  return codeLabel(value, {
    APARTMENT: '아파트',
    ETC: '기타',
    OFFICETEL: '오피스텔',
  }, '')
}

function heatingTypeLabel(value: string | null) {
  return codeLabel(value, {
    CENTRAL: '중앙난방',
    DISTRICT: '지역난방',
    ETC: '기타',
    INDIVIDUAL: '개별난방',
  }, '')
}

function corridorTypeLabel(value: string | null) {
  return codeLabel(value, {
    CORRIDOR: '복도식',
    MIXED: '혼합식',
    STAIR: '계단식',
    UNKNOWN: '',
  }, '')
}

function publicationTypeLabel(value: string | null) {
  return codeLabel(value, {
    CORRECTION: '정정공고',
    ORIGINAL: '원공고',
  }, '')
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
