import { rentalTypeLabel } from './rentalTypeLabel.ts'
import { MISSING_DATA_LABEL } from './missingData.ts'
import type { HousingComplexDetailData } from '../components/HousingComplexDetailPanel.tsx'
import type { ComplexDetail } from '../model/publicHousing.ts'

export function toHousingComplexDetailData(
  detail: ComplexDetail,
): HousingComplexDetailData {
  return {
    agencyCode: detail.agency?.code ?? null,
    agencyName: detail.agency?.name ?? MISSING_DATA_LABEL,
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
    name: detail.name ?? MISSING_DATA_LABEL,
    overviewImageUrl: detail.overviewImageUrl,
    regionName: detail.address?.regionName ?? MISSING_DATA_LABEL,
    rentalTypeLabel: rentalTypeLabel(detail.rentalType) ?? MISSING_DATA_LABEL,
    roadAddress: detail.address?.roadAddress ?? MISSING_DATA_LABEL,
    totalHouseholdCount: detail.totalHouseholdCount,
    totalParkingCount: detail.totalParkingCount,
  }
}

function buildingTypeLabel(value: string | null) {
  return codeLabel(value, {
    APARTMENT: '아파트',
    ETC: '기타',
    OFFICETEL: '오피스텔',
  }, MISSING_DATA_LABEL)
}

function heatingTypeLabel(value: string | null) {
  return codeLabel(value, {
    CENTRAL: '중앙난방',
    DISTRICT: '지역난방',
    ETC: '기타',
    INDIVIDUAL: '개별난방',
  }, MISSING_DATA_LABEL)
}

function corridorTypeLabel(value: string | null) {
  return codeLabel(value, {
    CORRIDOR: '복도식',
    MIXED: '혼합식',
    STAIR: '계단식',
    UNKNOWN: MISSING_DATA_LABEL,
  }, MISSING_DATA_LABEL)
}

function publicationTypeLabel(value: string | null) {
  return codeLabel(value, {
    CORRECTION: '정정공고',
    ORIGINAL: '원공고',
  }, MISSING_DATA_LABEL)
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
