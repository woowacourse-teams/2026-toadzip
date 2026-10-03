import type { HousingComplexCardData } from '../components/HousingComplexCard.tsx'
import type { ComplexListItem } from '../model/publicHousing.ts'
import { MISSING_DATA_LABEL } from './missingData.ts'
import { rentalTypeLabel } from './rentalTypeLabel.ts'

export function toHousingComplexCardData(complex: ComplexListItem): HousingComplexCardData {
  return {
    agencyCode: complex.agency?.code ?? null,
    agencyName: complex.agency?.name ?? MISSING_DATA_LABEL,
    complexId: complex.complexId,
    depositMax: complex.depositMax,
    depositMin: complex.depositMin,
    exclusiveAreaMax: complex.exclusiveAreaMax,
    exclusiveAreaMin: complex.exclusiveAreaMin,
    monthlyRentMax: complex.monthlyRentMax,
    monthlyRentMin: complex.monthlyRentMin,
    name: complex.name ?? MISSING_DATA_LABEL,
    regionName: complex.regionName ?? MISSING_DATA_LABEL,
    thumbnailImageUrl: complex.thumbnailImageUrl,
    rentalTypeLabel: rentalTypeLabel(complex.rentalType) ?? MISSING_DATA_LABEL,
    representativeAnnouncement: complex.representativeAnnouncement
      ? {
          announcementId: complex.representativeAnnouncement.announcementId,
          applicationEndAt:
            complex.representativeAnnouncement.applicationEndAt,
          applicationStatus:
            complex.representativeAnnouncement.applicationStatus ?? 'UNKNOWN',
          dDay: complex.representativeAnnouncement.dDay,
        }
      : null,
  }
}
