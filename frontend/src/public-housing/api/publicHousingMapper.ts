import type {
  ComplexListItem,
  ComplexPage,
  MapComplex,
  RawComplexListItem,
  RawComplexPage,
  RawMapComplex,
  RawRepresentativeAnnouncement,
  RepresentativeAnnouncement,
} from '../model/publicHousing.ts'

export function toComplexPage(raw: RawComplexPage): ComplexPage {
  return {
    items: raw.items.map(toComplexListItem),
    nextCursor: raw.nextCursor,
    hasNext: raw.hasNext,
    raw,
  }
}

export function toMapComplexes(
  rawItems: readonly RawMapComplex[],
): readonly MapComplex[] {
  return rawItems.filter(hasValidCoordinates).map(toMapComplex)
}

function toComplexListItem(raw: RawComplexListItem): ComplexListItem {
  return {
    complexId: canonicalId(raw.complexId),
    thumbnailImageUrl: raw.thumbnailImageUrl,
    regionName: raw.regionName,
    name: raw.name,
    rentalType: raw.rentalType,
    agency: raw.agency,
    exclusiveAreaMin: raw.exclusiveAreaMin,
    exclusiveAreaMax: raw.exclusiveAreaMax,
    depositMin: raw.depositMin,
    depositMax: raw.depositMax,
    monthlyRentMin: raw.monthlyRentMin,
    monthlyRentMax: raw.monthlyRentMax,
    representativeAnnouncement: toRepresentativeAnnouncement(
      raw.representativeAnnouncement,
    ),
    raw,
  }
}

function toRepresentativeAnnouncement(
  raw: RawRepresentativeAnnouncement | null,
): RepresentativeAnnouncement | null {
  if (raw === null) {
    return null
  }

  return {
    announcementId: canonicalId(raw.announcementId),
    publicationType: raw.publicationType,
    applicationStatus: raw.applicationStatus,
    applicationEndAt: raw.applicationEndAt,
    dDay: raw.dDay,
  }
}

function toMapComplex(raw: RawMapComplex): MapComplex {
  return {
    complexId: canonicalId(raw.complexId),
    name: raw.name,
    latitude: raw.latitude,
    longitude: raw.longitude,
    rentalType: raw.rentalType,
    agency: raw.agency,
    exclusiveAreaMin: raw.exclusiveAreaMin,
    exclusiveAreaMax: raw.exclusiveAreaMax,
    depositMin: raw.depositMin,
    depositMax: raw.depositMax,
    monthlyRentMin: raw.monthlyRentMin,
    monthlyRentMax: raw.monthlyRentMax,
    raw,
  }
}

function canonicalId(id: number): string {
  return String(id)
}

function hasValidCoordinates(raw: RawMapComplex): boolean {
  return (
    raw.latitude >= -90 &&
    raw.latitude <= 90 &&
    raw.longitude >= -180 &&
    raw.longitude <= 180
  )
}
