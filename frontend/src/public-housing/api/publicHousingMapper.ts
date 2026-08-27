import type {
  AnnouncementListItem,
  AnnouncementPage,
  ComplexCurrentAnnouncement,
  ComplexDetail,
  ComplexHousingType,
  ComplexListItem,
  ComplexPage,
  MapComplex,
  RawAnnouncementListItem,
  RawAnnouncementPage,
  RawComplexCurrentAnnouncement,
  RawComplexDetail,
  RawComplexHousingType,
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

export function toAnnouncementPage(raw: RawAnnouncementPage): AnnouncementPage {
  return {
    items: raw.items.map(toAnnouncementListItem),
    nextCursor: raw.nextCursor,
    hasNext: raw.hasNext,
    raw,
  }
}

export function toComplexDetail(raw: RawComplexDetail): ComplexDetail {
  return {
    complexId: canonicalId(raw.complexId),
    name: raw.name,
    rentalType: raw.rentalType,
    agency: raw.agency,
    address: raw.address,
    completionDate: raw.completionDate,
    buildingType: raw.buildingType,
    hasElevator: raw.hasElevator,
    heatingType: raw.heatingType,
    corridorType: raw.corridorType,
    moveOutCountLastYear: raw.moveOutCountLastYear,
    totalHouseholdCount: raw.totalHouseholdCount,
    totalParkingCount: raw.totalParkingCount,
    images: raw.images,
    overviewImageUrl: raw.overviewImageUrl,
    housingTypes: raw.housingTypes.map(toComplexHousingType),
    currentAnnouncements: raw.currentAnnouncements.map(
      toComplexCurrentAnnouncement,
    ),
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

function toAnnouncementListItem(
  raw: RawAnnouncementListItem,
): AnnouncementListItem {
  return {
    announcementId: canonicalId(raw.announcementId),
    publicationType: raw.publicationType,
    applicationStatus: raw.applicationStatus,
    rentalType: raw.rentalType,
    recruitmentType: raw.recruitmentType,
    title: raw.title,
    regionNames: raw.regionNames,
    publishedAt: raw.publishedAt,
    applicationStartAt: raw.applicationStartAt,
    applicationEndAt: raw.applicationEndAt,
    dDay: raw.dDay,
    viewCount: raw.viewCount,
    supplyComplexCount: raw.supplyComplexCount,
    supplyHouseholdCount: raw.supplyHouseholdCount,
    agency: raw.agency,
    actualCompetitionRate: raw.actualCompetitionRate,
    predictedCompetitionRate: raw.predictedCompetitionRate,
    thumbnailImageUrl: raw.thumbnailImageUrl,
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

function toComplexHousingType(raw: RawComplexHousingType): ComplexHousingType {
  return {
    housingTypeId: canonicalId(raw.housingTypeId),
    name: raw.name,
    exclusiveArea: raw.exclusiveArea,
    supplyArea: raw.supplyArea,
    floorPlanImageUrl: raw.floorPlanImageUrl,
    floorPlan3dImageUrl: raw.floorPlan3dImageUrl,
    isDuplex: raw.isDuplex,
    maintenanceFee: raw.maintenanceFee,
    currentSupplyConditions: raw.currentSupplyConditions.map((condition) => ({
      target: condition.target,
      deposit: condition.deposit,
      monthlyRent: condition.monthlyRent,
      convertibleDeposit: condition.convertibleDeposit,
    })),
  }
}

function toComplexCurrentAnnouncement(
  raw: RawComplexCurrentAnnouncement,
): ComplexCurrentAnnouncement {
  return {
    announcementId: canonicalId(raw.announcementId),
    title: raw.title,
    publicationType: raw.publicationType,
    applicationStatus: raw.applicationStatus,
    targets: raw.targets,
    applicationStartAt: raw.applicationStartAt,
    applicationEndAt: raw.applicationEndAt,
    dDay: raw.dDay,
    actualCompetitionRate: raw.actualCompetitionRate,
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
