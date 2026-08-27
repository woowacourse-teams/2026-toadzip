import type {
  HousingAgency,
  RawAnnouncementListItem,
  RawAnnouncementPage,
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
