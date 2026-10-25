import { rentalTypeLabel } from './rentalTypeLabel.ts'
import type {
  HousingComplexDetailData,
  HousingComplexDetailHousingType,
} from '../components/HousingComplexDetailPanel.tsx'
import { formatHousingArea, type AreaUnit } from './housingArea.ts'
import type { ComplexDetail } from '../model/publicHousing.ts'

export function formatRentalConditionCollectedDate(value: string | null): string | null {
  if (value === null) return null
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return null
  return new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Seoul' }).format(date).replaceAll('-', '.')
}

type LabeledHousingType = Pick<HousingComplexDetailHousingType, 'housingTypeId' | 'name' | 'exclusiveArea' | 'supplyArea'>

/**
 * 주택형 표시 이름. 마이홈 단지 원천은 면적만 다른 같은 이름의 주택형을 따로 주므로(ADR-001),
 * 단지 안에서 이름이 겹칠 때만 전용면적을, 전용면적까지 같으면 공급면적을 붙여 구분한다.
 */
export function housingTypeLabels(
  housingTypes: readonly LabeledHousingType[],
  unit: AreaUnit,
): ReadonlyMap<string, string> {
  const labels = new Map<string, string>()
  for (const housingType of housingTypes) {
    const name = housingType.name ?? ''
    const siblings = housingTypes.filter((other) => other !== housingType && name !== '' && other.name === name)
    if (siblings.length === 0) {
      labels.set(housingType.housingTypeId, name)
      continue
    }
    const sameExclusiveArea = siblings.some((other) => other.exclusiveArea === housingType.exclusiveArea)
    const area = sameExclusiveArea ? housingType.supplyArea : housingType.exclusiveArea
    const formatted = formatHousingArea(area, unit, '')
    labels.set(
      housingType.housingTypeId,
      formatted === '' ? name : `${name} · ${sameExclusiveArea ? '공급' : '전용'} ${formatted}`,
    )
  }
  return labels
}

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
