import type {
  HousingAgency,
  RawAnnouncementAttachment,
  RawAnnouncementCompetition,
  RawAnnouncementDetail,
  RawAnnouncementHousingType,
  RawAnnouncementListItem,
  RawAnnouncementPage,
  RawAnnouncementReceptionPlace,
  RawAnnouncementSchedule,
  RawAnnouncementSupplyComplex,
  RawAnnouncementSupplyRow,
  RawAnnouncementSupplyTarget,
  RawComplexAddress,
  RawComplexCurrentAnnouncement,
  RawComplexDetail,
  RawComplexHousingType,
  RawComplexListItem,
  RawComplexPage,
  RawComplexSupplyCondition,
  RawMapComplex,
  RawMapComplexResponse,
  RawRepresentativeAnnouncement,
} from '../model/publicHousing.ts'

export class PublicHousingContractError extends Error {
  readonly path: string

  constructor(path: string) {
    super(`공공주택 API 응답 형식이 올바르지 않습니다: ${path}`)
    this.name = 'PublicHousingContractError'
    this.path = path
  }
}

export function decodeComplexPageEnvelope(value: unknown): RawComplexPage {
  const envelope = recordAt(value, '$')
  return decodeComplexPage(recordField(envelope, 'data', '$'), '$.data')
}

export function decodeAnnouncementPageEnvelope(
  value: unknown,
): RawAnnouncementPage {
  const envelope = recordAt(value, '$')
  const data = recordAt(recordField(envelope, 'data', '$'), '$.data')
  const items = arrayAt(recordField(data, 'items', '$.data'), '$.data.items')

  return {
    items: items.map((item, index) =>
      decodeAnnouncementListItem(item, `$.data.items[${index}]`),
    ),
    nextCursor: nullableStringAt(
      recordField(data, 'nextCursor', '$.data'),
      '$.data.nextCursor',
    ),
    hasNext: booleanAt(
      recordField(data, 'hasNext', '$.data'),
      '$.data.hasNext',
    ),
  }
}

export function decodeAnnouncementDetailEnvelope(
  value: unknown,
): RawAnnouncementDetail {
  const envelope = recordAt(value, '$')
  return decodeAnnouncementDetail(
    recordField(envelope, 'data', '$'),
    '$.data',
  )
}

export function decodeComplexDetailEnvelope(value: unknown): RawComplexDetail {
  const envelope = recordAt(value, '$')
  return decodeComplexDetail(recordField(envelope, 'data', '$'), '$.data')
}

export function decodeMapComplexEnvelope(
  value: unknown,
): RawMapComplexResponse {
  const envelope = recordAt(value, '$')
  const data = recordAt(recordField(envelope, 'data', '$'), '$.data')
  const items = arrayAt(recordField(data, 'items', '$.data'), '$.data.items')

  return {
    items: items.map((item, index) =>
      decodeMapComplex(item, `$.data.items[${index}]`),
    ),
  }
}

function decodeComplexPage(value: unknown, path: string): RawComplexPage {
  const response = recordAt(value, path)
  const items = arrayAt(recordField(response, 'items', path), `${path}.items`)

  return {
    items: items.map((item, index) =>
      decodeComplexListItem(item, `${path}.items[${index}]`),
    ),
    nextCursor: nullableStringAt(
      recordField(response, 'nextCursor', path),
      `${path}.nextCursor`,
    ),
    hasNext: booleanAt(
      recordField(response, 'hasNext', path),
      `${path}.hasNext`,
    ),
  }
}

function decodeAnnouncementDetail(
  value: unknown,
  path: string,
): RawAnnouncementDetail {
  const detail = recordAt(value, path)

  return {
    announcementId: positiveSafeIntegerAt(
      recordField(detail, 'announcementId', path),
      `${path}.announcementId`,
    ),
    publicationType: nullableStringAt(
      recordField(detail, 'publicationType', path),
      `${path}.publicationType`,
    ),
    correctionOrCancellationReason: nullableStringAt(
      recordField(detail, 'correctionOrCancellationReason', path),
      `${path}.correctionOrCancellationReason`,
    ),
    applicationStatus: nullableStringAt(
      recordField(detail, 'applicationStatus', path),
      `${path}.applicationStatus`,
    ),
    rentalType: nullableStringAt(
      recordField(detail, 'rentalType', path),
      `${path}.rentalType`,
    ),
    recruitmentType: nullableStringAt(
      recordField(detail, 'recruitmentType', path),
      `${path}.recruitmentType`,
    ),
    title: nullableStringAt(
      recordField(detail, 'title', path),
      `${path}.title`,
    ),
    regionNames: stringArrayAt(
      recordField(detail, 'regionNames', path),
      `${path}.regionNames`,
    ),
    agency: decodeNullableAgency(
      recordField(detail, 'agency', path),
      `${path}.agency`,
    ),
    publishedAt: nullableDateStringAt(
      recordField(detail, 'publishedAt', path),
      `${path}.publishedAt`,
    ),
    applicationStartAt: nullableDateStringAt(
      recordField(detail, 'applicationStartAt', path),
      `${path}.applicationStartAt`,
    ),
    applicationEndAt: nullableDateStringAt(
      recordField(detail, 'applicationEndAt', path),
      `${path}.applicationEndAt`,
    ),
    dDay: nullableSafeIntegerAt(
      recordField(detail, 'dDay', path),
      `${path}.dDay`,
    ),
    winnerAnnouncementAt: nullableDateStringAt(
      recordField(detail, 'winnerAnnouncementAt', path),
      `${path}.winnerAnnouncementAt`,
    ),
    viewCount: safeIntegerAt(
      recordField(detail, 'viewCount', path),
      `${path}.viewCount`,
    ),
    targets: stringArrayAt(
      recordField(detail, 'targets', path),
      `${path}.targets`,
    ),
    supplyComplexCount: safeIntegerAt(
      recordField(detail, 'supplyComplexCount', path),
      `${path}.supplyComplexCount`,
    ),
    supplyHouseholdCount: nullableSafeIntegerAt(
      recordField(detail, 'supplyHouseholdCount', path),
      `${path}.supplyHouseholdCount`,
    ),
    documentLinkUrl: nullableStringAt(
      recordField(detail, 'documentLinkUrl', path),
      `${path}.documentLinkUrl`,
    ),
    receptionPlaces: decodeAnnouncementReceptionPlaces(
      recordField(detail, 'receptionPlaces', path),
      `${path}.receptionPlaces`,
    ),
    schedules: decodeAnnouncementSchedules(
      recordField(detail, 'schedules', path),
      `${path}.schedules`,
    ),
    attachments: decodeAnnouncementAttachments(
      recordField(detail, 'attachments', path),
      `${path}.attachments`,
    ),
    supplyRows: decodeAnnouncementSupplyRows(
      recordField(detail, 'supplyRows', path),
      `${path}.supplyRows`,
    ),
    competition: decodeNullableAnnouncementCompetition(
      recordField(detail, 'competition', path),
      `${path}.competition`,
    ),
  }
}

function decodeAnnouncementReceptionPlaces(
  value: unknown,
  path: string,
): readonly RawAnnouncementReceptionPlace[] {
  return arrayAt(value, path).map((item, index) =>
    decodeAnnouncementReceptionPlace(item, `${path}[${index}]`),
  )
}

function decodeAnnouncementReceptionPlace(
  value: unknown,
  path: string,
): RawAnnouncementReceptionPlace {
  const place = recordAt(value, path)

  return {
    name: nullableStringAt(recordField(place, 'name', path), `${path}.name`),
    method: nullableStringAt(
      recordField(place, 'method', path),
      `${path}.method`,
    ),
    address: nullableStringAt(
      recordField(place, 'address', path),
      `${path}.address`,
    ),
    phoneNumber: nullableStringAt(
      recordField(place, 'phoneNumber', path),
      `${path}.phoneNumber`,
    ),
    url: nullableStringAt(recordField(place, 'url', path), `${path}.url`),
  }
}

function decodeAnnouncementSchedules(
  value: unknown,
  path: string,
): readonly RawAnnouncementSchedule[] {
  return arrayAt(value, path).map((item, index) =>
    decodeAnnouncementSchedule(item, `${path}[${index}]`),
  )
}

function decodeAnnouncementSchedule(
  value: unknown,
  path: string,
): RawAnnouncementSchedule {
  const schedule = recordAt(value, path)

  return {
    scheduleId: positiveSafeIntegerAt(
      recordField(schedule, 'scheduleId', path),
      `${path}.scheduleId`,
    ),
    type: nullableStringAt(
      recordField(schedule, 'type', path),
      `${path}.type`,
    ),
    name: nullableStringAt(
      recordField(schedule, 'name', path),
      `${path}.name`,
    ),
    startAt: nullableDateTimeStringAt(
      recordField(schedule, 'startAt', path),
      `${path}.startAt`,
    ),
    endAt: nullableDateTimeStringAt(
      recordField(schedule, 'endAt', path),
      `${path}.endAt`,
    ),
  }
}

function decodeAnnouncementAttachments(
  value: unknown,
  path: string,
): readonly RawAnnouncementAttachment[] {
  return arrayAt(value, path).map((item, index) =>
    decodeAnnouncementAttachment(item, `${path}[${index}]`),
  )
}

function decodeAnnouncementAttachment(
  value: unknown,
  path: string,
): RawAnnouncementAttachment {
  const attachment = recordAt(value, path)

  return {
    attachmentId: positiveSafeIntegerAt(
      recordField(attachment, 'attachmentId', path),
      `${path}.attachmentId`,
    ),
    fileName: nullableStringAt(
      recordField(attachment, 'fileName', path),
      `${path}.fileName`,
    ),
    fileType: nullableStringAt(
      recordField(attachment, 'fileType', path),
      `${path}.fileType`,
    ),
    fileUrl: nullableStringAt(
      recordField(attachment, 'fileUrl', path),
      `${path}.fileUrl`,
    ),
  }
}

function decodeAnnouncementSupplyRows(
  value: unknown,
  path: string,
): readonly RawAnnouncementSupplyRow[] {
  return arrayAt(value, path).map((item, index) =>
    decodeAnnouncementSupplyRow(item, `${path}[${index}]`),
  )
}

function decodeAnnouncementSupplyRow(
  value: unknown,
  path: string,
): RawAnnouncementSupplyRow {
  const row = recordAt(value, path)

  return {
    supplyRowId: positiveSafeIntegerAt(
      recordField(row, 'supplyRowId', path),
      `${path}.supplyRowId`,
    ),
    sourceComplexName: nullableStringAt(
      recordField(row, 'sourceComplexName', path),
      `${path}.sourceComplexName`,
    ),
    sourceHousingTypeName: nullableStringAt(
      recordField(row, 'sourceHousingTypeName', path),
      `${path}.sourceHousingTypeName`,
    ),
    complex: decodeNullableAnnouncementSupplyComplex(
      recordField(row, 'complex', path),
      `${path}.complex`,
    ),
    housingType: decodeNullableAnnouncementHousingType(
      recordField(row, 'housingType', path),
      `${path}.housingType`,
    ),
    occupancyExpectedYearMonth: nullableYearMonthStringAt(
      recordField(row, 'occupancyExpectedYearMonth', path),
      `${path}.occupancyExpectedYearMonth`,
    ),
    supplyType: nullableStringAt(
      recordField(row, 'supplyType', path),
      `${path}.supplyType`,
    ),
    totalSupplyHouseholdCount: nullableSafeIntegerAt(
      recordField(row, 'totalSupplyHouseholdCount', path),
      `${path}.totalSupplyHouseholdCount`,
    ),
    targets: decodeAnnouncementSupplyTargets(
      recordField(row, 'targets', path),
      `${path}.targets`,
    ),
  }
}

function decodeNullableAnnouncementSupplyComplex(
  value: unknown,
  path: string,
): RawAnnouncementSupplyComplex | null {
  if (value === null) {
    return null
  }

  const complex = recordAt(value, path)
  return {
    complexId: positiveSafeIntegerAt(
      recordField(complex, 'complexId', path),
      `${path}.complexId`,
    ),
    name: nullableStringAt(
      recordField(complex, 'name', path),
      `${path}.name`,
    ),
    address: nullableStringAt(
      recordField(complex, 'address', path),
      `${path}.address`,
    ),
    totalHouseholdCount: nullableSafeIntegerAt(
      recordField(complex, 'totalHouseholdCount', path),
      `${path}.totalHouseholdCount`,
    ),
    overviewImageUrl: nullableStringAt(
      recordField(complex, 'overviewImageUrl', path),
      `${path}.overviewImageUrl`,
    ),
  }
}

function decodeNullableAnnouncementHousingType(
  value: unknown,
  path: string,
): RawAnnouncementHousingType | null {
  if (value === null) {
    return null
  }

  const housingType = recordAt(value, path)
  return {
    housingTypeId: positiveSafeIntegerAt(
      recordField(housingType, 'housingTypeId', path),
      `${path}.housingTypeId`,
    ),
    name: nullableStringAt(
      recordField(housingType, 'name', path),
      `${path}.name`,
    ),
    exclusiveArea: nullableFiniteNumberAt(
      recordField(housingType, 'exclusiveArea', path),
      `${path}.exclusiveArea`,
    ),
    supplyArea: nullableFiniteNumberAt(
      recordField(housingType, 'supplyArea', path),
      `${path}.supplyArea`,
    ),
    floorPlanImageUrl: nullableStringAt(
      recordField(housingType, 'floorPlanImageUrl', path),
      `${path}.floorPlanImageUrl`,
    ),
    floorPlan3dImageUrl: nullableStringAt(
      recordField(housingType, 'floorPlan3dImageUrl', path),
      `${path}.floorPlan3dImageUrl`,
    ),
  }
}

function decodeAnnouncementSupplyTargets(
  value: unknown,
  path: string,
): readonly RawAnnouncementSupplyTarget[] {
  return arrayAt(value, path).map((item, index) =>
    decodeAnnouncementSupplyTarget(item, `${path}[${index}]`),
  )
}

function decodeAnnouncementSupplyTarget(
  value: unknown,
  path: string,
): RawAnnouncementSupplyTarget {
  const target = recordAt(value, path)

  return {
    supplyTargetId: positiveSafeIntegerAt(
      recordField(target, 'supplyTargetId', path),
      `${path}.supplyTargetId`,
    ),
    target: nullableStringAt(
      recordField(target, 'target', path),
      `${path}.target`,
    ),
    priority: nullableStringAt(
      recordField(target, 'priority', path),
      `${path}.priority`,
    ),
    supplyHouseholdCount: nullableSafeIntegerAt(
      recordField(target, 'supplyHouseholdCount', path),
      `${path}.supplyHouseholdCount`,
    ),
    waitlistCount: nullableSafeIntegerAt(
      recordField(target, 'waitlistCount', path),
      `${path}.waitlistCount`,
    ),
    deposit: nullableSafeIntegerAt(
      recordField(target, 'deposit', path),
      `${path}.deposit`,
    ),
    monthlyRent: nullableSafeIntegerAt(
      recordField(target, 'monthlyRent', path),
      `${path}.monthlyRent`,
    ),
    convertibleDeposit: nullableSafeIntegerAt(
      recordField(target, 'convertibleDeposit', path),
      `${path}.convertibleDeposit`,
    ),
    applicationCondition: nullableStringAt(
      recordField(target, 'applicationCondition', path),
      `${path}.applicationCondition`,
    ),
  }
}

function decodeNullableAnnouncementCompetition(
  value: unknown,
  path: string,
): RawAnnouncementCompetition | null {
  if (value === null) {
    return null
  }

  const competition = recordAt(value, path)
  return {
    actualRate: nullableFiniteNumberAt(
      recordField(competition, 'actualRate', path),
      `${path}.actualRate`,
    ),
    predictedRate: nullableFiniteNumberAt(
      recordField(competition, 'predictedRate', path),
      `${path}.predictedRate`,
    ),
  }
}

function decodeComplexDetail(value: unknown, path: string): RawComplexDetail {
  const detail = recordAt(value, path)

  return {
    complexId: positiveSafeIntegerAt(
      recordField(detail, 'complexId', path),
      `${path}.complexId`,
    ),
    name: nullableStringAt(recordField(detail, 'name', path), `${path}.name`),
    rentalType: nullableStringAt(
      recordField(detail, 'rentalType', path),
      `${path}.rentalType`,
    ),
    agency: decodeNullableAgency(
      recordField(detail, 'agency', path),
      `${path}.agency`,
    ),
    address: decodeNullableComplexAddress(
      recordField(detail, 'address', path),
      `${path}.address`,
    ),
    completionDate: nullableDateStringAt(
      recordField(detail, 'completionDate', path),
      `${path}.completionDate`,
    ),
    buildingType: nullableStringAt(
      recordField(detail, 'buildingType', path),
      `${path}.buildingType`,
    ),
    hasElevator: nullableBooleanAt(
      recordField(detail, 'hasElevator', path),
      `${path}.hasElevator`,
    ),
    heatingType: nullableStringAt(
      recordField(detail, 'heatingType', path),
      `${path}.heatingType`,
    ),
    corridorType: nullableStringAt(
      recordField(detail, 'corridorType', path),
      `${path}.corridorType`,
    ),
    moveOutCountLastYear: nullableSafeIntegerAt(
      recordField(detail, 'moveOutCountLastYear', path),
      `${path}.moveOutCountLastYear`,
    ),
    totalHouseholdCount: nullableSafeIntegerAt(
      recordField(detail, 'totalHouseholdCount', path),
      `${path}.totalHouseholdCount`,
    ),
    totalParkingCount: nullableSafeIntegerAt(
      recordField(detail, 'totalParkingCount', path),
      `${path}.totalParkingCount`,
    ),
    images: stringArrayAt(
      recordField(detail, 'images', path),
      `${path}.images`,
    ),
    overviewImageUrl: nullableStringAt(
      recordField(detail, 'overviewImageUrl', path),
      `${path}.overviewImageUrl`,
    ),
    housingTypes: decodeComplexHousingTypes(
      recordField(detail, 'housingTypes', path),
      `${path}.housingTypes`,
    ),
    currentAnnouncements: decodeComplexCurrentAnnouncements(
      recordField(detail, 'currentAnnouncements', path),
      `${path}.currentAnnouncements`,
    ),
  }
}

function decodeNullableComplexAddress(
  value: unknown,
  path: string,
): RawComplexAddress | null {
  if (value === null) {
    return null
  }

  const address = recordAt(value, path)
  return {
    regionName: nullableStringAt(
      recordField(address, 'regionName', path),
      `${path}.regionName`,
    ),
    roadAddress: nullableStringAt(
      recordField(address, 'roadAddress', path),
      `${path}.roadAddress`,
    ),
    latitude: nullableFiniteNumberAt(
      recordField(address, 'latitude', path),
      `${path}.latitude`,
    ),
    longitude: nullableFiniteNumberAt(
      recordField(address, 'longitude', path),
      `${path}.longitude`,
    ),
  }
}

function decodeComplexHousingTypes(
  value: unknown,
  path: string,
): readonly RawComplexHousingType[] {
  return arrayAt(value, path).map((item, index) =>
    decodeComplexHousingType(item, `${path}[${index}]`),
  )
}

function decodeComplexHousingType(
  value: unknown,
  path: string,
): RawComplexHousingType {
  const housingType = recordAt(value, path)

  return {
    housingTypeId: positiveSafeIntegerAt(
      recordField(housingType, 'housingTypeId', path),
      `${path}.housingTypeId`,
    ),
    name: nullableStringAt(
      recordField(housingType, 'name', path),
      `${path}.name`,
    ),
    exclusiveArea: nullableFiniteNumberAt(
      recordField(housingType, 'exclusiveArea', path),
      `${path}.exclusiveArea`,
    ),
    supplyArea: nullableFiniteNumberAt(
      recordField(housingType, 'supplyArea', path),
      `${path}.supplyArea`,
    ),
    floorPlanImageUrl: nullableStringAt(
      recordField(housingType, 'floorPlanImageUrl', path),
      `${path}.floorPlanImageUrl`,
    ),
    floorPlan3dImageUrl: nullableStringAt(
      recordField(housingType, 'floorPlan3dImageUrl', path),
      `${path}.floorPlan3dImageUrl`,
    ),
    isDuplex: nullableBooleanAt(
      recordField(housingType, 'isDuplex', path),
      `${path}.isDuplex`,
    ),
    maintenanceFee: nullableSafeIntegerAt(
      recordField(housingType, 'maintenanceFee', path),
      `${path}.maintenanceFee`,
    ),
    currentSupplyConditions: decodeComplexSupplyConditions(
      recordField(housingType, 'currentSupplyConditions', path),
      `${path}.currentSupplyConditions`,
    ),
  }
}

function decodeComplexSupplyConditions(
  value: unknown,
  path: string,
): readonly RawComplexSupplyCondition[] {
  return arrayAt(value, path).map((item, index) =>
    decodeComplexSupplyCondition(item, `${path}[${index}]`),
  )
}

function decodeComplexSupplyCondition(
  value: unknown,
  path: string,
): RawComplexSupplyCondition {
  const condition = recordAt(value, path)

  return {
    target: nullableStringAt(
      recordField(condition, 'target', path),
      `${path}.target`,
    ),
    deposit: nullableSafeIntegerAt(
      recordField(condition, 'deposit', path),
      `${path}.deposit`,
    ),
    monthlyRent: nullableSafeIntegerAt(
      recordField(condition, 'monthlyRent', path),
      `${path}.monthlyRent`,
    ),
    convertibleDeposit: nullableSafeIntegerAt(
      recordField(condition, 'convertibleDeposit', path),
      `${path}.convertibleDeposit`,
    ),
  }
}

function decodeComplexCurrentAnnouncements(
  value: unknown,
  path: string,
): readonly RawComplexCurrentAnnouncement[] {
  return arrayAt(value, path).map((item, index) =>
    decodeComplexCurrentAnnouncement(item, `${path}[${index}]`),
  )
}

function decodeComplexCurrentAnnouncement(
  value: unknown,
  path: string,
): RawComplexCurrentAnnouncement {
  const announcement = recordAt(value, path)

  return {
    announcementId: positiveSafeIntegerAt(
      recordField(announcement, 'announcementId', path),
      `${path}.announcementId`,
    ),
    title: nullableStringAt(
      recordField(announcement, 'title', path),
      `${path}.title`,
    ),
    publicationType: nullableStringAt(
      recordField(announcement, 'publicationType', path),
      `${path}.publicationType`,
    ),
    applicationStatus: nullableStringAt(
      recordField(announcement, 'applicationStatus', path),
      `${path}.applicationStatus`,
    ),
    targets: stringArrayAt(
      recordField(announcement, 'targets', path),
      `${path}.targets`,
    ),
    applicationStartAt: nullableDateStringAt(
      recordField(announcement, 'applicationStartAt', path),
      `${path}.applicationStartAt`,
    ),
    applicationEndAt: nullableDateStringAt(
      recordField(announcement, 'applicationEndAt', path),
      `${path}.applicationEndAt`,
    ),
    dDay: nullableSafeIntegerAt(
      recordField(announcement, 'dDay', path),
      `${path}.dDay`,
    ),
    actualCompetitionRate: nullableFiniteNumberAt(
      recordField(announcement, 'actualCompetitionRate', path),
      `${path}.actualCompetitionRate`,
    ),
  }
}

function decodeComplexListItem(
  value: unknown,
  path: string,
): RawComplexListItem {
  const item = recordAt(value, path)

  return {
    complexId: positiveSafeIntegerAt(
      recordField(item, 'complexId', path),
      `${path}.complexId`,
    ),
    thumbnailImageUrl: nullableStringAt(
      recordField(item, 'thumbnailImageUrl', path),
      `${path}.thumbnailImageUrl`,
    ),
    regionName: nullableStringAt(
      recordField(item, 'regionName', path),
      `${path}.regionName`,
    ),
    name: nullableStringAt(recordField(item, 'name', path), `${path}.name`),
    rentalType: nullableStringAt(
      recordField(item, 'rentalType', path),
      `${path}.rentalType`,
    ),
    agency: decodeNullableAgency(
      recordField(item, 'agency', path),
      `${path}.agency`,
    ),
    exclusiveAreaMin: nullableFiniteNumberAt(
      recordField(item, 'exclusiveAreaMin', path),
      `${path}.exclusiveAreaMin`,
    ),
    exclusiveAreaMax: nullableFiniteNumberAt(
      recordField(item, 'exclusiveAreaMax', path),
      `${path}.exclusiveAreaMax`,
    ),
    depositMin: nullableSafeIntegerAt(
      recordField(item, 'depositMin', path),
      `${path}.depositMin`,
    ),
    depositMax: nullableSafeIntegerAt(
      recordField(item, 'depositMax', path),
      `${path}.depositMax`,
    ),
    monthlyRentMin: nullableSafeIntegerAt(
      recordField(item, 'monthlyRentMin', path),
      `${path}.monthlyRentMin`,
    ),
    monthlyRentMax: nullableSafeIntegerAt(
      recordField(item, 'monthlyRentMax', path),
      `${path}.monthlyRentMax`,
    ),
    representativeAnnouncement: decodeNullableRepresentativeAnnouncement(
      recordField(item, 'representativeAnnouncement', path),
      `${path}.representativeAnnouncement`,
    ),
  }
}

function decodeAnnouncementListItem(
  value: unknown,
  path: string,
): RawAnnouncementListItem {
  const item = recordAt(value, path)

  return {
    announcementId: positiveSafeIntegerAt(
      recordField(item, 'announcementId', path),
      `${path}.announcementId`,
    ),
    publicationType: nullableStringAt(
      recordField(item, 'publicationType', path),
      `${path}.publicationType`,
    ),
    applicationStatus: nullableStringAt(
      recordField(item, 'applicationStatus', path),
      `${path}.applicationStatus`,
    ),
    rentalType: nullableStringAt(
      recordField(item, 'rentalType', path),
      `${path}.rentalType`,
    ),
    recruitmentType: nullableStringAt(
      recordField(item, 'recruitmentType', path),
      `${path}.recruitmentType`,
    ),
    title: nullableStringAt(
      recordField(item, 'title', path),
      `${path}.title`,
    ),
    regionNames: stringArrayAt(
      recordField(item, 'regionNames', path),
      `${path}.regionNames`,
    ),
    publishedAt: nullableDateStringAt(
      recordField(item, 'publishedAt', path),
      `${path}.publishedAt`,
    ),
    applicationStartAt: nullableDateStringAt(
      recordField(item, 'applicationStartAt', path),
      `${path}.applicationStartAt`,
    ),
    applicationEndAt: nullableDateStringAt(
      recordField(item, 'applicationEndAt', path),
      `${path}.applicationEndAt`,
    ),
    dDay: nullableSafeIntegerAt(
      recordField(item, 'dDay', path),
      `${path}.dDay`,
    ),
    viewCount: safeIntegerAt(
      recordField(item, 'viewCount', path),
      `${path}.viewCount`,
    ),
    supplyComplexCount: safeIntegerAt(
      recordField(item, 'supplyComplexCount', path),
      `${path}.supplyComplexCount`,
    ),
    supplyHouseholdCount: nullableSafeIntegerAt(
      recordField(item, 'supplyHouseholdCount', path),
      `${path}.supplyHouseholdCount`,
    ),
    agency: decodeNullableAgency(
      recordField(item, 'agency', path),
      `${path}.agency`,
    ),
    actualCompetitionRate: nullableFiniteNumberAt(
      recordField(item, 'actualCompetitionRate', path),
      `${path}.actualCompetitionRate`,
    ),
    predictedCompetitionRate: nullableFiniteNumberAt(
      recordField(item, 'predictedCompetitionRate', path),
      `${path}.predictedCompetitionRate`,
    ),
    thumbnailImageUrl: nullableStringAt(
      recordField(item, 'thumbnailImageUrl', path),
      `${path}.thumbnailImageUrl`,
    ),
  }
}

function decodeMapComplex(value: unknown, path: string): RawMapComplex {
  const item = recordAt(value, path)

  return {
    complexId: positiveSafeIntegerAt(
      recordField(item, 'complexId', path),
      `${path}.complexId`,
    ),
    name: nullableStringAt(recordField(item, 'name', path), `${path}.name`),
    latitude: finiteNumberAt(
      recordField(item, 'latitude', path),
      `${path}.latitude`,
    ),
    longitude: finiteNumberAt(
      recordField(item, 'longitude', path),
      `${path}.longitude`,
    ),
    rentalType: nullableStringAt(
      recordField(item, 'rentalType', path),
      `${path}.rentalType`,
    ),
    agency: decodeNullableAgency(
      recordField(item, 'agency', path),
      `${path}.agency`,
    ),
    exclusiveAreaMin: nullableFiniteNumberAt(
      recordField(item, 'exclusiveAreaMin', path),
      `${path}.exclusiveAreaMin`,
    ),
    exclusiveAreaMax: nullableFiniteNumberAt(
      recordField(item, 'exclusiveAreaMax', path),
      `${path}.exclusiveAreaMax`,
    ),
    depositMin: nullableSafeIntegerAt(
      recordField(item, 'depositMin', path),
      `${path}.depositMin`,
    ),
    depositMax: nullableSafeIntegerAt(
      recordField(item, 'depositMax', path),
      `${path}.depositMax`,
    ),
    monthlyRentMin: nullableSafeIntegerAt(
      recordField(item, 'monthlyRentMin', path),
      `${path}.monthlyRentMin`,
    ),
    monthlyRentMax: nullableSafeIntegerAt(
      recordField(item, 'monthlyRentMax', path),
      `${path}.monthlyRentMax`,
    ),
  }
}

function decodeAgency(value: unknown, path: string): HousingAgency {
  const agency = recordAt(value, path)

  return {
    code: nullableStringAt(recordField(agency, 'code', path), `${path}.code`),
    name: nullableStringAt(recordField(agency, 'name', path), `${path}.name`),
  }
}

function decodeNullableAgency(
  value: unknown,
  path: string,
): HousingAgency | null {
  if (value === null) {
    return null
  }
  return decodeAgency(value, path)
}

function decodeNullableRepresentativeAnnouncement(
  value: unknown,
  path: string,
): RawRepresentativeAnnouncement | null {
  if (value === null) {
    return null
  }

  const announcement = recordAt(value, path)
  return {
    announcementId: positiveSafeIntegerAt(
      recordField(announcement, 'announcementId', path),
      `${path}.announcementId`,
    ),
    publicationType: nullableStringAt(
      recordField(announcement, 'publicationType', path),
      `${path}.publicationType`,
    ),
    applicationStatus: nullableStringAt(
      recordField(announcement, 'applicationStatus', path),
      `${path}.applicationStatus`,
    ),
    applicationEndAt: nullableDateStringAt(
      recordField(announcement, 'applicationEndAt', path),
      `${path}.applicationEndAt`,
    ),
    dDay: nullableSafeIntegerAt(
      recordField(announcement, 'dDay', path),
      `${path}.dDay`,
    ),
  }
}

function recordField(
  record: Record<string, unknown>,
  field: string,
  path: string,
): unknown {
  if (!Object.hasOwn(record, field)) {
    throw new PublicHousingContractError(`${path}.${field}`)
  }
  return record[field]
}

function recordAt(value: unknown, path: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new PublicHousingContractError(path)
  }
  return value as Record<string, unknown>
}

function arrayAt(value: unknown, path: string): readonly unknown[] {
  if (!Array.isArray(value)) {
    throw new PublicHousingContractError(path)
  }
  return value
}

function stringArrayAt(value: unknown, path: string): readonly string[] {
  return arrayAt(value, path).map((item, index) =>
    stringAt(item, `${path}[${index}]`),
  )
}

function stringAt(value: unknown, path: string): string {
  if (typeof value !== 'string') {
    throw new PublicHousingContractError(path)
  }
  return value
}

function nullableStringAt(value: unknown, path: string): string | null {
  if (value === null) {
    return null
  }
  return stringAt(value, path)
}

function booleanAt(value: unknown, path: string): boolean {
  if (typeof value !== 'boolean') {
    throw new PublicHousingContractError(path)
  }
  return value
}

function nullableBooleanAt(value: unknown, path: string): boolean | null {
  if (value === null) {
    return null
  }
  return booleanAt(value, path)
}

function finiteNumberAt(value: unknown, path: string): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    throw new PublicHousingContractError(path)
  }
  return value
}

function nullableFiniteNumberAt(value: unknown, path: string): number | null {
  if (value === null) {
    return null
  }
  return finiteNumberAt(value, path)
}

function positiveSafeIntegerAt(value: unknown, path: string): number {
  const integer = safeIntegerAt(value, path)
  if (integer <= 0) {
    throw new PublicHousingContractError(path)
  }
  return integer
}

function safeIntegerAt(value: unknown, path: string): number {
  if (!Number.isSafeInteger(value)) {
    throw new PublicHousingContractError(path)
  }
  return value as number
}

function nullableSafeIntegerAt(value: unknown, path: string): number | null {
  if (value === null) {
    return null
  }
  return safeIntegerAt(value, path)
}

function dateStringAt(value: unknown, path: string): string {
  const date = stringAt(value, path)
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
    throw new PublicHousingContractError(path)
  }
  return date
}

function nullableDateStringAt(value: unknown, path: string): string | null {
  if (value === null) {
    return null
  }
  return dateStringAt(value, path)
}

function nullableDateTimeStringAt(
  value: unknown,
  path: string,
): string | null {
  if (value === null) {
    return null
  }

  const dateTime = stringAt(value, path)
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?$/.test(dateTime)) {
    throw new PublicHousingContractError(path)
  }
  return dateTime
}

function nullableYearMonthStringAt(
  value: unknown,
  path: string,
): string | null {
  if (value === null) {
    return null
  }

  const yearMonth = stringAt(value, path)
  if (!/^\d{4}-\d{2}$/.test(yearMonth)) {
    throw new PublicHousingContractError(path)
  }
  return yearMonth
}
