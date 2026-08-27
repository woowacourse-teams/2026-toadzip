import type {
  AnnouncementAttachment,
  AnnouncementDetail,
  AnnouncementHousingType,
  AnnouncementListItem,
  AnnouncementPage,
  AnnouncementSchedule,
  AnnouncementSupplyComplex,
  AnnouncementSupplyRow,
  AnnouncementSupplyTarget,
  ComplexCurrentAnnouncement,
  ComplexDetail,
  ComplexHousingType,
  ComplexListItem,
  ComplexPage,
  MapComplex,
  RawAnnouncementAttachment,
  RawAnnouncementDetail,
  RawAnnouncementHousingType,
  RawAnnouncementListItem,
  RawAnnouncementPage,
  RawAnnouncementSchedule,
  RawAnnouncementSupplyComplex,
  RawAnnouncementSupplyRow,
  RawAnnouncementSupplyTarget,
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

export function toAnnouncementDetail(
  raw: RawAnnouncementDetail,
): AnnouncementDetail {
  return {
    announcementId: canonicalId(raw.announcementId),
    publicationType: raw.publicationType,
    correctionOrCancellationReason: raw.correctionOrCancellationReason,
    applicationStatus: raw.applicationStatus,
    rentalType: raw.rentalType,
    recruitmentType: raw.recruitmentType,
    title: raw.title,
    regionNames: raw.regionNames,
    agency: raw.agency,
    publishedAt: raw.publishedAt,
    applicationStartAt: raw.applicationStartAt,
    applicationEndAt: raw.applicationEndAt,
    dDay: raw.dDay,
    winnerAnnouncementAt: raw.winnerAnnouncementAt,
    viewCount: raw.viewCount,
    targets: raw.targets,
    supplyComplexCount: raw.supplyComplexCount,
    supplyHouseholdCount: raw.supplyHouseholdCount,
    documentLinkUrl: raw.documentLinkUrl,
    receptionPlaces: raw.receptionPlaces,
    schedules: raw.schedules.map(toAnnouncementSchedule),
    attachments: raw.attachments.map(toAnnouncementAttachment),
    supplyRows: raw.supplyRows.map(toAnnouncementSupplyRow),
    competition: raw.competition,
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

function toAnnouncementSchedule(
  raw: RawAnnouncementSchedule,
): AnnouncementSchedule {
  return {
    scheduleId: canonicalId(raw.scheduleId),
    type: raw.type,
    name: raw.name,
    startAt: raw.startAt,
    endAt: raw.endAt,
  }
}

function toAnnouncementAttachment(
  raw: RawAnnouncementAttachment,
): AnnouncementAttachment {
  return {
    attachmentId: canonicalId(raw.attachmentId),
    fileName: raw.fileName,
    fileType: raw.fileType,
    fileUrl: raw.fileUrl,
  }
}

function toAnnouncementSupplyRow(
  raw: RawAnnouncementSupplyRow,
): AnnouncementSupplyRow {
  return {
    supplyRowId: canonicalId(raw.supplyRowId),
    sourceComplexName: raw.sourceComplexName,
    sourceHousingTypeName: raw.sourceHousingTypeName,
    complex: toNullableAnnouncementSupplyComplex(raw.complex),
    housingType: toNullableAnnouncementHousingType(raw.housingType),
    occupancyExpectedYearMonth: raw.occupancyExpectedYearMonth,
    supplyType: raw.supplyType,
    totalSupplyHouseholdCount: raw.totalSupplyHouseholdCount,
    targets: raw.targets.map(toAnnouncementSupplyTarget),
  }
}

function toNullableAnnouncementSupplyComplex(
  raw: RawAnnouncementSupplyComplex | null,
): AnnouncementSupplyComplex | null {
  if (raw === null) {
    return null
  }

  return {
    complexId: canonicalId(raw.complexId),
    name: raw.name,
    address: raw.address,
    totalHouseholdCount: raw.totalHouseholdCount,
    overviewImageUrl: raw.overviewImageUrl,
  }
}

function toNullableAnnouncementHousingType(
  raw: RawAnnouncementHousingType | null,
): AnnouncementHousingType | null {
  if (raw === null) {
    return null
  }

  return {
    housingTypeId: canonicalId(raw.housingTypeId),
    name: raw.name,
    exclusiveArea: raw.exclusiveArea,
    supplyArea: raw.supplyArea,
    floorPlanImageUrl: raw.floorPlanImageUrl,
    floorPlan3dImageUrl: raw.floorPlan3dImageUrl,
  }
}

function toAnnouncementSupplyTarget(
  raw: RawAnnouncementSupplyTarget,
): AnnouncementSupplyTarget {
  return {
    supplyTargetId: canonicalId(raw.supplyTargetId),
    target: raw.target,
    priority: raw.priority,
    supplyHouseholdCount: raw.supplyHouseholdCount,
    waitlistCount: raw.waitlistCount,
    deposit: raw.deposit,
    monthlyRent: raw.monthlyRent,
    convertibleDeposit: raw.convertibleDeposit,
    applicationCondition: raw.applicationCondition,
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
